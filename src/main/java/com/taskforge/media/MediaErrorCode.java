package com.taskforge.media;

import com.taskforge.common.exception.ErrorCode;

public enum MediaErrorCode implements ErrorCode {

	EMPTY_FILE("MEDIA-001", "Uploaded file is empty"),
	FILE_TOO_LARGE("MEDIA-002", "File exceeds the maximum allowed size"),
	UNSUPPORTED_CONTENT_TYPE("MEDIA-003", "This file type is not allowed"),
	UPLOAD_NOT_FOUND("MEDIA-004", "No completed direct upload found for this request");

	private final String code;
	private final String defaultMessage;

	MediaErrorCode(String code, String defaultMessage) {
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
