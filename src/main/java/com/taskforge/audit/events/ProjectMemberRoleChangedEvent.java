package com.taskforge.audit.events;

import java.util.UUID;

import com.taskforge.project.ProjectMemberRole;

public record ProjectMemberRoleChangedEvent(UUID organizationId, UUID actorId, UUID projectId, UUID targetUserId,
		ProjectMemberRole oldRole, ProjectMemberRole newRole) {
}
