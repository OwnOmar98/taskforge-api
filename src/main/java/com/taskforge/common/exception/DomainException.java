package com.taskforge.common.exception;

/**
 * Base type for expected business-rule failures. Carries no HTTP knowledge itself —
 * status mapping lives only in {@link GlobalExceptionHandler}.
 */
public abstract class DomainException extends RuntimeException {

	private final ErrorCode errorCode;

	protected DomainException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
