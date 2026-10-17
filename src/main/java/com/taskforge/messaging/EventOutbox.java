package com.taskforge.messaging;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

// Same claim/lease/stale-result design as WebhookOutbox, for domain events
// instead of HTTP deliveries. A publish that fails (broker down, message
// unroutable) is retried with backoff indefinitely: there's no dead state,
// because the event itself is fine and only the broker needs to come back.
@Component
public class EventOutbox {

	private static final String CLAIM_SQL = "update outbox_events e "
			+ "set status = 'IN_FLIGHT', claimed_until = :claimedUntil, attempts = e.attempts + 1 "
			+ "where e.id in (select id from outbox_events "
			+ "    where (status = 'PENDING' and next_attempt_at <= :now) "
			+ "       or (status = 'IN_FLIGHT' and claimed_until < :now) "
			+ "    order by next_attempt_at "
			+ "    limit :batchSize "
			+ "    for update skip locked) "
			+ "returning e.id, e.event_type, e.payload::text as payload, e.occurred_at, e.attempts";

	private static final String CURRENT_CLAIM = " where id = :id and status = 'IN_FLIGHT' and attempts = :attempt";

	private static final int MAX_ERROR_LENGTH = 1000;

	private final NamedParameterJdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final EventsProperties.Outbox properties;

	public EventOutbox(NamedParameterJdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
			ApplicationEventPublisher applicationEventPublisher, EventsProperties properties) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
		this.applicationEventPublisher = applicationEventPublisher;
		this.properties = properties.outbox();
	}

	// Joins the caller's transaction, like WebhookOutbox.enqueue. The
	// OutboxEventAppended signal lets the relay publish right after commit
	// instead of waiting for its next poll.
	public UUID append(String eventType, Object payload) {
		UUID id = UUID.randomUUID();
		OffsetDateTime now = utc(Instant.now());
		jdbcTemplate.update("insert into outbox_events (id, event_type, payload, occurred_at, status, next_attempt_at) "
				+ "values (:id, :eventType, cast(:payload as jsonb), :now, 'PENDING', :now)",
				new MapSqlParameterSource().addValue("id", id)
						.addValue("eventType", eventType)
						.addValue("payload", objectMapper.writeValueAsString(payload))
						.addValue("now", now));
		applicationEventPublisher.publishEvent(new OutboxEventAppended(id));
		return id;
	}

	@Transactional
	public List<ClaimedEvent> claimDue(int batchSize) {
		Instant now = Instant.now();
		return jdbcTemplate.query(CLAIM_SQL,
				new MapSqlParameterSource().addValue("now", utc(now))
						.addValue("claimedUntil", utc(now.plus(properties.claimTimeout())))
						.addValue("batchSize", batchSize),
				(rs, rowNum) -> new ClaimedEvent(
						new DomainEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
								rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
								objectMapper.readTree(rs.getString("payload"))),
						rs.getInt("attempts")));
	}

	public void markPublished(ClaimedEvent claimed) {
		jdbcTemplate.update("update outbox_events set status = 'PUBLISHED', published_at = :now, claimed_until = null"
				+ CURRENT_CLAIM, claimParams(claimed).addValue("now", utc(Instant.now())));
	}

	public void scheduleRetry(ClaimedEvent claimed, Throwable failure) {
		jdbcTemplate.update("update outbox_events set status = 'PENDING', next_attempt_at = :next, "
				+ "claimed_until = null, last_error = :error" + CURRENT_CLAIM,
				claimParams(claimed).addValue("next", utc(Instant.now().plus(backoff(claimed.attempt()))))
						.addValue("error", describe(failure)));
	}

	Duration backoff(int attemptsSoFar) {
		int exponent = Math.min(Math.max(attemptsSoFar - 1, 0), 20);
		Duration delay = properties.baseBackoff().multipliedBy(1L << exponent);
		return delay.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : delay;
	}

	private MapSqlParameterSource claimParams(ClaimedEvent claimed) {
		return new MapSqlParameterSource().addValue("id", claimed.event().id()).addValue("attempt", claimed.attempt());
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

	public record ClaimedEvent(DomainEvent event, int attempt) {
	}

	public record OutboxEventAppended(UUID eventId) {
	}

}
