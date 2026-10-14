package com.taskforge.integration;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.AuditLog;
import com.taskforge.audit.AuditLogRepository;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

// The outbox's state transitions, each in its own short transaction. The HTTP
// call itself happens between them (in WebhookOutboxRelay), deliberately
// outside any transaction: holding a row lock and a DB connection for the
// length of a third-party call would let one slow endpoint exhaust the pool.
@Component
public class WebhookOutbox {

	// FOR UPDATE SKIP LOCKED is what lets several app instances relay in
	// parallel: each one locks the rows it picks, and the others skip those
	// rows instead of waiting on them, so no row is claimed twice. Claiming
	// flips the row to IN_FLIGHT with a lease, so it stays invisible to other
	// relays after this transaction commits and its locks are released. The
	// second branch reclaims rows whose lease ran out - the instance that
	// claimed them died mid-send, and at-least-once delivery means retrying
	// rather than losing them.
	private static final String CLAIM_SQL = "update webhook_deliveries d "
			+ "set status = 'IN_FLIGHT', claimed_until = :claimedUntil, attempts = d.attempts + 1 "
			+ "where d.id in (select id from webhook_deliveries "
			+ "    where (status = 'PENDING' and next_attempt_at <= :now) "
			+ "       or (status = 'IN_FLIGHT' and claimed_until < :now) "
			+ "    order by next_attempt_at "
			+ "    limit :batchSize "
			+ "    for update skip locked) "
			+ "returning d.id";

	private static final int MAX_ERROR_LENGTH = 1000;

	private static final Logger log = LoggerFactory.getLogger(WebhookOutbox.class);

	private final WebhookDeliveryRepository deliveryRepository;
	private final WebhookRepository webhookRepository;
	private final AuditLogRepository auditLogRepository;
	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final MeterRegistry meterRegistry;
	private final ObjectMapper objectMapper;
	private final WebhookOutboxProperties properties;

	public WebhookOutbox(WebhookDeliveryRepository deliveryRepository, WebhookRepository webhookRepository,
			AuditLogRepository auditLogRepository, NamedParameterJdbcTemplate jdbcTemplate,
			MeterRegistry meterRegistry, ObjectMapper objectMapper, WebhookOutboxProperties properties) {
		this.deliveryRepository = deliveryRepository;
		this.webhookRepository = webhookRepository;
		this.auditLogRepository = auditLogRepository;
		this.jdbcTemplate = jdbcTemplate;
		this.meterRegistry = meterRegistry;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	// No @Transactional of its own: it must join the caller's transaction -
	// that's the entire point of an outbox. The row commits or rolls back
	// together with the change that triggered it.
	public WebhookDelivery enqueue(Webhook webhook, String eventType, String payload) {
		return deliveryRepository.save(new WebhookDelivery(webhook, eventType, payload));
	}

	@Transactional
	public List<ClaimedDelivery> claimDue(int batchSize) {
		Instant now = Instant.now();
		List<UUID> ids = jdbcTemplate.queryForList(CLAIM_SQL,
				new MapSqlParameterSource().addValue("now", utc(now))
						.addValue("claimedUntil", utc(now.plus(properties.claimTimeout())))
						.addValue("batchSize", batchSize),
				UUID.class);
		if (ids.isEmpty()) {
			return List.of();
		}

		// Loaded after the JDBC update, in the same transaction, so these see
		// the just-claimed state. Webhooks come in one query, not one per row.
		List<WebhookDelivery> deliveries = deliveryRepository.findAllById(ids);
		Map<UUID, Webhook> webhooksById = webhookRepository
				.findAllById(deliveries.stream().map(WebhookDelivery::getWebhookId).distinct().toList())
				.stream()
				.collect(Collectors.toMap(Webhook::getId, Function.identity()));

		return deliveries.stream()
				.map(d -> new ClaimedDelivery(d.getId(), d.getAttempts(), webhooksById.get(d.getWebhookId()),
						d.getPayload(), d.getIdempotencyKey()))
				.toList();
	}

	@Transactional
	public void recordSuccess(ClaimedDelivery claimed) {
		currentClaim(claimed).ifPresent(delivery -> delivery.markDelivered(Instant.now()));
	}

	@Transactional
	public void recordFailure(ClaimedDelivery claimed, Throwable failure) {
		currentClaim(claimed).ifPresent(delivery -> {
			String error = describe(failure);
			meterRegistry.counter("webhook.delivery.failures").increment();

			if (delivery.getAttempts() >= properties.maxAttempts()) {
				delivery.markFailed(error);
				meterRegistry.counter("webhook.delivery.dead").increment();
				// Same audit record the old in-memory fallback wrote, now only
				// once a delivery has genuinely given up, not on every attempt.
				auditLogRepository.save(new AuditLog(delivery.getOrganizationId(), null, "WEBHOOK_DELIVERY_FAILED",
						"Webhook", delivery.getWebhookId(), objectMapper.writeValueAsString(Map.of(
								"deliveryId", delivery.getId(), "attempts", delivery.getAttempts(), "error", error))));
				log.warn("Webhook delivery {} failed permanently after {} attempts: {}", delivery.getId(),
						delivery.getAttempts(), error);
			}
			else {
				delivery.scheduleRetry(Instant.now().plus(backoff(delivery.getAttempts())), error);
			}
		});
	}

	// Exponential: base, 2x base, 4x base ... capped. Long-horizon retries
	// live here, in the outbox; WebhookSender's own short in-process retries
	// only smooth over momentary blips within a single attempt.
	Duration backoff(int attemptsSoFar) {
		int exponent = Math.min(Math.max(attemptsSoFar - 1, 0), 20);
		Duration delay = properties.baseBackoff().multipliedBy(1L << exponent);
		return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
	}

	// A result only counts for the claim that produced it. If this instance's
	// lease expired mid-send and another relay already reclaimed the row (its
	// attempts moved on), this late result is stale and must not overwrite
	// the newer attempt's state.
	private Optional<WebhookDelivery> currentClaim(ClaimedDelivery claimed) {
		return deliveryRepository.findById(claimed.deliveryId())
				.filter(d -> d.getStatus() == WebhookDeliveryStatus.IN_FLIGHT && d.getAttempts() == claimed.attempt());
	}

	private String describe(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause()
				: failure;
		String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
		return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
	}

	// What the relay needs to send one delivery, detached from any session.
	public record ClaimedDelivery(UUID deliveryId, int attempt, Webhook webhook, String payload, UUID idempotencyKey) {
	}

}
