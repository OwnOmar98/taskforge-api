package com.taskforge.task.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.taskforge.task.TaskPriority;
import com.taskforge.task.validation.FutureOrPresentDueDate;

public record CreateTaskRequest(
		@NotBlank String title,
		String description,
		@NotNull TaskPriority priority,
		@FutureOrPresentDueDate LocalDate dueDate,
		UUID assigneeId) {
}
