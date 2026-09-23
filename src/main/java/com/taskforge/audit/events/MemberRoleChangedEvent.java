package com.taskforge.audit.events;

import java.util.UUID;

import com.taskforge.organization.MembershipRole;

public record MemberRoleChangedEvent(UUID organizationId, UUID actorId, UUID targetUserId, MembershipRole oldRole,
		MembershipRole newRole) {
}
