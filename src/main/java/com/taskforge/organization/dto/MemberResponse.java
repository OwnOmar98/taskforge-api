package com.taskforge.organization.dto;

import java.util.UUID;

import com.taskforge.organization.MembershipRole;

public record MemberResponse(UUID userId, String email, String fullName, MembershipRole role) {
}
