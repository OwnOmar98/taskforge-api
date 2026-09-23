package com.taskforge.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditLogResponse(UUID id, String action, String entityType, UUID entityId, UUID actorId,
		Object metadata, Instant createdAt) {
}
