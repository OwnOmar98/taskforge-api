package com.taskforge.common.exception;

public class BadRequestException extends DomainException {

	public BadRequestException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

}
