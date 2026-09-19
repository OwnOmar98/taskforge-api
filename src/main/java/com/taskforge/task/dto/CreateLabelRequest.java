package com.taskforge.task.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateLabelRequest(@NotBlank String name) {
}
