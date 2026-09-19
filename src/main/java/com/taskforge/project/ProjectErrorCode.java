package com.taskforge.project;

import com.taskforge.common.exception.ErrorCode;

public enum ProjectErrorCode implements ErrorCode {

	PROJECT_KEY_IN_USE("PROJECT-001", "A project with this key already exists in the organization"),
	NOT_AN_ORGANIZATION_MEMBER("PROJECT-002", "This user is not a member of the organization"),
	ALREADY_PROJECT_MEMBER("PROJECT-003", "This user is already a member of the project"),
	STALE_PROJECT_VERSION("PROJECT-004", "This project was modified since you last read it");

	private final String code;
	private final String defaultMessage;

	ProjectErrorCode(String code, String defaultMessage) {
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
