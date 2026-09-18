package com.taskforge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.taskforge.common.EmailNormalizer;

public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {

	public LoginRequest {
		email = EmailNormalizer.normalize(email);
	}

}
