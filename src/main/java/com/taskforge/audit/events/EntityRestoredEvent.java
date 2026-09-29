package com.taskforge.audit.events;

import java.util.Map;
import java.util.UUID;

public record EntityRestoredEvent(UUID organizationId, UUID actorId, String entityType, UUID entityId,
		Map<String, ?> metadata) {
}
