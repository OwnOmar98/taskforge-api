package com.taskforge.common.exception;

public class TooManyRequestsException extends DomainException {

	private final long retryAfterSeconds;

	public TooManyRequestsException(ErrorCode errorCode, String message, long retryAfterSeconds) {
		super(errorCode, message);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}

}
