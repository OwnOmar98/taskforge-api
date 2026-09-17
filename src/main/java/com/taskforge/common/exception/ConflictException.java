package com.taskforge.common.exception;

public class ConflictException extends DomainException {

	public ConflictException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

}
