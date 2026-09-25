package com.taskforge.integration.dto;

import java.time.Instant;
import java.util.UUID;

public record WebhookResponse(UUID id, String url, Instant createdAt) {
}
