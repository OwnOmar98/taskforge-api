package com.taskforge.integration;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

// Resilience4j's Spring AOP aspect order is fixed, not based on annotation
// declaration order: Retry wraps CircuitBreaker wraps TimeLimiter wraps the
// call. That's exactly the shape a webhook delivery needs - each retry
// attempt re-enters the circuit breaker (so once it's open, further retries
// fail fast instead of making real calls, and "webhook.retry.ignore-
// exceptions" in application.yml stops Retry from burning attempts on that
// fail-fast response), and TimeLimiter bounds each individual attempt's
// duration rather than the whole retry sequence. The fallback belongs on
// @Retry specifically, not @CircuitBreaker or @TimeLimiter: a fallback on
// one of the inner two would swallow the failure into a normally-completed
// future before Retry ever saw it, so there'd be nothing left to retry.
@Component
public class WebhookSender {

	private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

	private final RestClient restClient;
	private final AuditLogRepository auditLogRepository;
	private final ObjectMapper objectMapper;
	private final Executor webhookExecutor;
	private final MeterRegistry meterRegistry;

	public WebhookSender(AuditLogRepository auditLogRepository, ObjectMapper objectMapper,
			@Qualifier("webhookExecutor") Executor webhookExecutor, MeterRegistry meterRegistry) {
		this.restClient = RestClient.create();
		this.auditLogRepository = auditLogRepository;
		this.objectMapper = objectMapper;
		this.webhookExecutor = webhookExecutor;
		this.meterRegistry = meterRegistry;
	}

	@Retry(name = "webhook", fallbackMethod = "sendFallback")
	@CircuitBreaker(name = "webhook")
	@TimeLimiter(name = "webhook")
	public CompletableFuture<Void> send(Webhook webhook, String payload, String idempotencyKey) {
		return CompletableFuture.runAsync(() -> {
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
	}

	// Shared by all three annotations: whichever one gives up first (retries
	// exhausted, circuit open, or the time limit hit) means the same thing -
	// this delivery failed - and PR16 already established that a failure like
	// this must be recorded, not silently swallowed.
	@SuppressWarnings("unused")
	private CompletableFuture<Void> sendFallback(Webhook webhook, String payload, String idempotencyKey,
			Throwable throwable) {
		log.warn("Webhook delivery failed for webhook {}: {}", webhook.getId(), throwable.toString());
		meterRegistry.counter("webhook.delivery.failures").increment();

		String metadata = objectMapper
				.writeValueAsString(Map.of("url", webhook.getUrl(), "error", String.valueOf(throwable.getMessage())));
		auditLogRepository.save(new AuditLog(webhook.getOrganizationId(), null, "WEBHOOK_DELIVERY_FAILED", "Webhook",
				webhook.getId(), metadata));

		return CompletableFuture.completedFuture(null);
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
