package com.taskforge.project.dto;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(UUID id, UUID organizationId, String key, String name, long version,
		Instant createdAt) {
}
