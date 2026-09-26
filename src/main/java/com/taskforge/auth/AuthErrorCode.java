package com.taskforge.auth;

import com.taskforge.common.exception.ErrorCode;

public enum AuthErrorCode implements ErrorCode {

	INVALID_CREDENTIALS("AUTH-001", "Invalid email or password"),
	EMAIL_IN_USE("AUTH-002", "Email already registered"),
	INVALID_ACCESS_TOKEN("AUTH-003", "Invalid or expired access token"),
	INVALID_REFRESH_TOKEN("AUTH-004", "Invalid or expired refresh token"),
	ACCOUNT_LOCKED("AUTH-005", "Too many failed login attempts. Try again later.");

	private final String code;
	private final String defaultMessage;

	AuthErrorCode(String code, String defaultMessage) {
		this.code = code;
		this.defaultMessage = defaultMessage;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public String defaultMessage() {
		return defaultMessage;
	}

}
