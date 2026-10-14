package com.taskforge.integration;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;

// Retry/CircuitBreaker/TimeLimiter are composed programmatically here, not
// via @Retry/@CircuitBreaker/@TimeLimiter: those annotations take a `name`
// that must be a compile-time constant, so a single shared "webhook" instance
// - one circuit breaker, retry budget, and sliding failure window for every
// organization's webhooks combined - meant one organization's failing
// endpoint could trip the breaker for every other organization's healthy one
// too. Each instance here is keyed by organization id instead, resolved from
// its registry (creating it on first use, configured from the same
// resilience4j.*.configs.default block the single shared instance used to
// read from directly). Composition order is unchanged - Retry wraps
// CircuitBreaker wraps TimeLimiter wraps the call - built from the inside out
// since each decorateCompletionStage() call wraps whatever supplier it's
// given.
//
// No fallback here anymore: the returned future completes exceptionally once
// these short in-process retries are exhausted (or the circuit is open, or the
// time limit hit), and the caller - WebhookOutboxRelay - decides what that
// means. Recording the failure here would be wrong now that a failed attempt
// isn't the end of a delivery: the outbox reschedules it with backoff, and
// only records a permanent failure once its own attempt budget runs out.
@Component
public class WebhookSender {

	private final RestClient restClient;
	private final Executor webhookExecutor;
	private final ScheduledExecutorService webhookResilienceScheduler;
	private final CircuitBreakerRegistry circuitBreakerRegistry;
	private final RetryRegistry retryRegistry;
	private final TimeLimiterRegistry timeLimiterRegistry;

	public WebhookSender(@Qualifier("webhookExecutor") Executor webhookExecutor,
			@Qualifier("webhookResilienceScheduler") ScheduledExecutorService webhookResilienceScheduler,
			CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
			TimeLimiterRegistry timeLimiterRegistry) {
		this.restClient = RestClient.create();
		this.webhookExecutor = webhookExecutor;
		this.webhookResilienceScheduler = webhookResilienceScheduler;
		this.circuitBreakerRegistry = circuitBreakerRegistry;
		this.retryRegistry = retryRegistry;
		this.timeLimiterRegistry = timeLimiterRegistry;
	}

	public CompletableFuture<Void> send(Webhook webhook, String payload, String idempotencyKey) {
		String instanceName = "webhook-" + webhook.getOrganizationId();

		Supplier<CompletionStage<Void>> call = () -> CompletableFuture.runAsync(() -> {
			String signature = sign(webhook.getSecret(), payload);

			restClient.post()
					.uri(webhook.getUrl())
					.contentType(MediaType.APPLICATION_JSON)
					.header("X-Webhook-Signature", signature)
					.header("X-Idempotency-Key", idempotencyKey)
					.body(payload)
					.retrieve()
					.toBodilessEntity();
		}, webhookExecutor);

		Supplier<CompletionStage<Void>> timeLimited = TimeLimiter.decorateCompletionStage(
				timeLimiterRegistry.timeLimiter(instanceName), webhookResilienceScheduler, call);

		Supplier<CompletionStage<Void>> circuitBroken = CircuitBreaker
				.decorateCompletionStage(circuitBreakerRegistry.circuitBreaker(instanceName), timeLimited);

		Supplier<CompletionStage<Void>> retried = Retry.decorateCompletionStage(retryRegistry.retry(instanceName),
				webhookResilienceScheduler, circuitBroken);

		return retried.get().toCompletableFuture();
	}

	private String sign(String secret, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException("HmacSHA256 should always be available", e);
		}
	}

}
