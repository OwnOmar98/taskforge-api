package com.taskforge.task.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskCommentResponse(UUID id, UUID authorId, String authorEmail, String body, Instant createdAt) {
}
