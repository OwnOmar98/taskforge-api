package com.taskforge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@Email @NotBlank String email,
		// BCrypt only hashes the first 72 bytes of its input; capping length here
		// makes that boundary explicit instead of a silent truncation.
		@NotBlank @Size(min = 8, max = 72) String password,
		@NotBlank String fullName) {
}
