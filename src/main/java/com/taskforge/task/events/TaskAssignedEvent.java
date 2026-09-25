package com.taskforge.task.events;

import java.util.UUID;

public record TaskAssignedEvent(UUID taskId, UUID assigneeId, UUID actorId) {
}
