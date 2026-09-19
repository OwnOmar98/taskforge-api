package com.taskforge.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateProjectRequest(@NotBlank String name, @NotNull Long version) {
}
