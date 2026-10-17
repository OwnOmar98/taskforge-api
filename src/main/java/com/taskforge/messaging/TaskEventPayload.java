package com.taskforge.messaging;

import java.util.UUID;

import com.taskforge.task.TaskStatus;

// Shared by the three task events; fields that don't apply to an event are
// null. assigneeId is captured when the event is written, so consumers act on
// who was assigned at the time, not whoever is assigned when they get to it.
public record TaskEventPayload(UUID taskId, UUID organizationId, UUID actorId, UUID assigneeId, TaskStatus oldStatus,
		TaskStatus newStatus, UUID commentId) {
}
