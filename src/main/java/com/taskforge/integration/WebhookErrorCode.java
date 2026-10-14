package com.taskforge.integration;

import com.taskforge.common.exception.ErrorCode;

public enum WebhookErrorCode implements ErrorCode {

	DELIVERY_NOT_FAILED("WEBHOOK-001", "Only a delivery that has permanently failed can be redelivered");

	private final String code;
	private final String defaultMessage;

	WebhookErrorCode(String code, String defaultMessage) {
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
