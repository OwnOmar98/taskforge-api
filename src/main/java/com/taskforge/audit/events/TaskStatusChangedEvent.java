package com.taskforge.audit.events;

import java.util.UUID;

import com.taskforge.task.TaskStatus;

public record TaskStatusChangedEvent(UUID organizationId, UUID actorId, UUID taskId, TaskStatus oldStatus,
		TaskStatus newStatus) {
}
