package com.taskforge.organization.dto;

import jakarta.validation.constraints.NotNull;

import com.taskforge.organization.MembershipRole;

public record ChangeRoleRequest(@NotNull MembershipRole role) {
}
