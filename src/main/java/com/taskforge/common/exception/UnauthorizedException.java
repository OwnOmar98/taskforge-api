package com.taskforge.common.exception;

public class UnauthorizedException extends DomainException {

	public UnauthorizedException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

}
