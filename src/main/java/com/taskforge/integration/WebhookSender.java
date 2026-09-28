package com.taskforge.integration;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.taskforge.audit.AuditLog;
import com.taskforge.audit.AuditLogRepository;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

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
// given. The fallback (record the failure, same as the old
// @Retry(fallbackMethod=...)) is attached to the outermost (Retry) future via
// exceptionally() - same effect as requiring the annotation-based fallback to
// sit specifically on @Retry, just achieved by composition position instead
// of annotation placement: anything that reaches here already means retries
// are exhausted, the circuit was open, or the time limit was hit.
@Component
public class WebhookSender {

	private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

	private final RestClient restClient;
	private final AuditLogRepository auditLogRepository;
	private final ObjectMapper objectMapper;
	private final Executor webhookExecutor;
	private final ScheduledExecutorService webhookResilienceScheduler;
	private final MeterRegistry meterRegistry;
	private final CircuitBreakerRegistry circuitBreakerRegistry;
	private final RetryRegistry retryRegistry;
	private final TimeLimiterRegistry timeLimiterRegistry;

	public WebhookSender(AuditLogRepository auditLogRepository, ObjectMapper objectMapper,
			@Qualifier("webhookExecutor") Executor webhookExecutor,
			@Qualifier("webhookResilienceScheduler") ScheduledExecutorService webhookResilienceScheduler,
			MeterRegistry meterRegistry, CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry,
			TimeLimiterRegistry timeLimiterRegistry) {
		this.restClient = RestClient.create();
		this.auditLogRepository = auditLogRepository;
		this.objectMapper = objectMapper;
		this.webhookExecutor = webhookExecutor;
		this.webhookResilienceScheduler = webhookResilienceScheduler;
		this.meterRegistry = meterRegistry;
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

		return retried.get().toCompletableFuture().exceptionally(throwable -> {
			recordFailure(webhook, throwable);
			return null;
		});
	}

	// Same reasoning as PR16 established for audit logging generally: a
	// delivery failure like this must be recorded, not silently swallowed,
	// regardless of which of the three layers above is what ultimately gave up.
	private void recordFailure(Webhook webhook, Throwable throwable) {
		log.warn("Webhook delivery failed for webhook {}: {}", webhook.getId(), throwable.toString());
		meterRegistry.counter("webhook.delivery.failures").increment();

		String metadata = objectMapper
				.writeValueAsString(Map.of("url", webhook.getUrl(), "error", String.valueOf(throwable.getMessage())));
		auditLogRepository.save(new AuditLog(webhook.getOrganizationId(), null, "WEBHOOK_DELIVERY_FAILED", "Webhook",
				webhook.getId(), metadata));
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
