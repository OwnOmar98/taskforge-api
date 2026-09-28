package com.taskforge.audit.events;

import java.util.UUID;

import com.taskforge.organization.MembershipRole;

public record MembershipRemovedEvent(UUID organizationId, UUID actorId, UUID targetUserId, MembershipRole role) {
}
