package com.taskforge.task.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;

public record TaskResponse(
		UUID id,
		UUID projectId,
		String title,
		String description,
		TaskStatus status,
		TaskPriority priority,
		LocalDate dueDate,
		UUID assigneeId,
		String assigneeEmail,
		long version,
		Instant createdAt) {
}
