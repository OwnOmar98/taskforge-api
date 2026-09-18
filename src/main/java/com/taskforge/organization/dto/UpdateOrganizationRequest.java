package com.taskforge.organization.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateOrganizationRequest(@NotBlank String name) {
}
