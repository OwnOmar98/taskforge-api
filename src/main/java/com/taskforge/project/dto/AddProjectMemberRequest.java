package com.taskforge.project.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

import com.taskforge.project.ProjectMemberRole;

public record AddProjectMemberRequest(@NotNull UUID userId, @NotNull ProjectMemberRole role) {
}
