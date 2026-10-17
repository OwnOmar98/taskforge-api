package com.taskforge.messaging;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// The consumer-side "inbox". Must run inside the handler's own transaction:
// if the handler's work rolls back, so does the mark, and the redelivered
// message is processed for real.
@Component
public class ProcessedEvents {

	private final JdbcTemplate jdbcTemplate;

	public ProcessedEvents(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	// False when this consumer already handled the event. A concurrent
	// duplicate blocks on the primary key until the first commits, then sees
	// the conflict, so two copies can't both get through.
	public boolean markProcessed(String consumer, UUID eventId) {
		return jdbcTemplate.update(
				"insert into processed_events (consumer, event_id) values (?, ?) on conflict do nothing", consumer,
				eventId) == 1;
	}

}
