package com.taskforge.task.dto;

import java.time.Instant;
import java.util.UUID;

public record LabelResponse(UUID id, UUID organizationId, String name, Instant createdAt) {
}
