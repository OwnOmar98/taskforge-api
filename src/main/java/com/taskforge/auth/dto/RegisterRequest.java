package com.taskforge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.taskforge.common.EmailNormalizer;

public record RegisterRequest(
		@Email @NotBlank String email,
		// BCrypt only hashes the first 72 bytes of its input; capping length here
		// makes that boundary explicit instead of a silent truncation.
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank String fullName,
		String invitationToken) {

	public RegisterRequest {
		email = EmailNormalizer.normalize(email);
	}

	public RegisterRequest(String email, String password, String fullName) {
		this(email, password, fullName, null);
	}

}
