package com.taskforge.task;

import com.taskforge.common.exception.ErrorCode;

public enum LabelErrorCode implements ErrorCode {

	LABEL_NAME_IN_USE("LABEL-001", "A label with this name already exists in the organization");

	private final String code;
	private final String defaultMessage;

	LabelErrorCode(String code, String defaultMessage) {
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
