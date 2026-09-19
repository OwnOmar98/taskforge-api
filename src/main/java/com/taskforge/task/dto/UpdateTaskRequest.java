package com.taskforge.task.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.validation.FutureOrPresentDueDate;

// Every field but version is optional: null means "leave this field
// unchanged", not "clear it". A real consequence of that convention -
// description, dueDate, and assigneeId can never be explicitly cleared back
// to null through this endpoint.
public record UpdateTaskRequest(
		String title,
		String description,
		TaskStatus status,
		TaskPriority priority,
		@FutureOrPresentDueDate LocalDate dueDate,
		UUID assigneeId,
		@NotNull Long version) {
}
