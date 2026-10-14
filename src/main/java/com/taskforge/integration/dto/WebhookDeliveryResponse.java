package com.taskforge.integration.dto;

import java.time.Instant;
import java.util.UUID;

import com.taskforge.integration.WebhookDeliveryStatus;

public record WebhookDeliveryResponse(UUID id, String eventType, WebhookDeliveryStatus status, int attempts,
		Instant nextAttemptAt, String lastError, UUID idempotencyKey, Object payload, Instant createdAt,
		Instant deliveredAt) {
}
