package com.taskforge.task.events;

import java.util.UUID;

public record TaskCommentAddedEvent(UUID taskId, UUID commentId, UUID authorId) {
}
