package com.taskforge.common.exception;

public class ResourceNotFoundException extends DomainException {

	public ResourceNotFoundException(ErrorCode errorCode, String message) {
		super(errorCode, message);
	}

}
