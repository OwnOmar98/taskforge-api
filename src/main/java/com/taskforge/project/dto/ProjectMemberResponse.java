package com.taskforge.project.dto;

import java.util.UUID;

import com.taskforge.project.ProjectMemberRole;

public record ProjectMemberResponse(UUID userId, String email, String fullName, ProjectMemberRole role) {
}
