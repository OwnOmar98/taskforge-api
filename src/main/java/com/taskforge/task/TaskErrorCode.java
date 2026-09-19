package com.taskforge.task;

import com.taskforge.common.exception.ErrorCode;

public enum TaskErrorCode implements ErrorCode {

	ASSIGNEE_NOT_A_PROJECT_MEMBER("TASK-001", "The assignee must be a member of this project"),
	STALE_TASK_VERSION("TASK-002", "This task was modified since you last read it");

	private final String code;
	private final String defaultMessage;

	TaskErrorCode(String code, String defaultMessage) {
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
