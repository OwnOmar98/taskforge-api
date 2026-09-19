package com.taskforge.project.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(@NotBlank String key, @NotBlank String name) {
}
