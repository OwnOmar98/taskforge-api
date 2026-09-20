package com.taskforge.task.dto;

import java.util.UUID;

import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;

public record TaskFilter(TaskStatus status, TaskPriority priority, UUID assigneeId, UUID labelId) {
}
