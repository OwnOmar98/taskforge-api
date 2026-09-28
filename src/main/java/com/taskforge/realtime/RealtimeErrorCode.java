package com.taskforge.realtime;

import com.taskforge.common.exception.ErrorCode;

public enum RealtimeErrorCode implements ErrorCode {

	TOO_MANY_STREAMS("REALTIME-001", "Too many open event streams for this user");

	private final String code;
	private final String defaultMessage;

	RealtimeErrorCode(String code, String defaultMessage) {
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
