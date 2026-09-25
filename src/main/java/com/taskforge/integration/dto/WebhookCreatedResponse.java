package com.taskforge.integration.dto;

import java.time.Instant;
import java.util.UUID;

// Separate from WebhookResponse deliberately: secret is only ever present
// here, on the single response that follows creation - it's never returned
// again afterward (not even to the org that owns it), same reasoning as any
// other secret shown once at issuance.
public record WebhookCreatedResponse(UUID id, String url, String secret, Instant createdAt) {
}
