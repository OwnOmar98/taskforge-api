package com.taskforge.project.dto;

import jakarta.validation.constraints.NotNull;

import com.taskforge.project.ProjectMemberRole;

public record ChangeProjectMemberRoleRequest(@NotNull ProjectMemberRole role) {
}
