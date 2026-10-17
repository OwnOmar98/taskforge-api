package com.taskforge.messaging;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

// The envelope every consumer receives, whichever transport carried it. The
// id is the outbox row's id and doubles as the message id consumers dedupe on.
public record DomainEvent(UUID id, String type, Instant occurredAt, JsonNode data) {
}
