package com.taskforge.common.exception;

public enum GeneralErrorCode implements ErrorCode {

	VALIDATION_FAILED("VALIDATION-001", "Validation failed"),
	RESOURCE_NOT_FOUND("RESOURCE-001", "Resource not found"),
	RESOURCE_CONFLICT("RESOURCE-002", "Resource conflict"),
	SERVER_UNEXPECTED_ERROR("SERVER-001", "An unexpected error occurred");

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
