package com.taskforge.organization.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.taskforge.common.EmailNormalizer;
import com.taskforge.organization.MembershipRole;

public record CreateInvitationRequest(@Email @NotBlank String email, @NotNull MembershipRole role) {

	public CreateInvitationRequest {
		email = EmailNormalizer.normalize(email);
	}

}
