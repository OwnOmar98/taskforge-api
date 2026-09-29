package com.taskforge.audit.events;

import java.util.Map;
import java.util.UUID;

// Generic over the soft-deletable entity rather than one event per type:
// deletion and restoration mean the same thing for tasks and projects, and
// the audit row is shaped identically either way. metadata carries enough
// (a task's title, a project's key and name) to keep the log readable even
// if the row is ever purged for real.
public record EntityDeletedEvent(UUID organizationId, UUID actorId, String entityType, UUID entityId,
		Map<String, ?> metadata) {
}
