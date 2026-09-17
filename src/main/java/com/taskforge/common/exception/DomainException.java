package com.taskforge.common.exception;

/**
 * Base type for expected business-rule failures. Carries no HTTP knowledge itself —
 * status mapping lives only in {@link GlobalExceptionHandler}.
 */
public abstract class DomainException extends RuntimeException {

	protected DomainException(String message) {
		super(message);
	}

}
