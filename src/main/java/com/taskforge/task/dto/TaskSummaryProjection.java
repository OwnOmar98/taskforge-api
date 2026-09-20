package com.taskforge.task.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;

// Leaner than TaskResponse on purpose: list views don't need description,
// version, or createdAt - callers already know the project from the URL.
public record TaskSummaryProjection(UUID id, String title, TaskStatus status, TaskPriority priority,
		LocalDate dueDate, UUID assigneeId, String assigneeEmail, List<String> labelNames) {
}
