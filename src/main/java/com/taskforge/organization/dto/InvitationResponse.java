package com.taskforge.organization.dto;

import java.time.Instant;
import java.util.UUID;

import com.taskforge.organization.MembershipRole;

public record InvitationResponse(UUID id, String email, MembershipRole role, String token, Instant expiresAt) {
}
