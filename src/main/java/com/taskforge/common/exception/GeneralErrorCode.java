package com.taskforge.common.exception;

public enum GeneralErrorCode implements ErrorCode {

	VALIDATION_FAILED("VALIDATION-001", "Validation failed"),
	RESOURCE_NOT_FOUND("RESOURCE-001", "Resource not found"),
	RESOURCE_CONFLICT("RESOURCE-002", "Resource conflict"),
	INSUFFICIENT_PERMISSIONS("ACCESS-001", "You do not have permission to perform this action"),
	SERVER_UNEXPECTED_ERROR("SERVER-001", "An unexpected error occurred"),
	RATE_LIMIT_EXCEEDED("RATE-001", "Too many requests. Try again later.");

	private final String code;
	private final String defaultMessage;

	GeneralErrorCode(String code, String defaultMessage) {
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
