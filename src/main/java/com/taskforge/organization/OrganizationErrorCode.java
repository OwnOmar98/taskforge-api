package com.taskforge.organization;

import com.taskforge.common.exception.ErrorCode;

public enum OrganizationErrorCode implements ErrorCode {

	CANNOT_MODIFY_OWNER_ROLE("ORG-001", "The OWNER role cannot be assigned or changed through this action"),
	ALREADY_MEMBER("ORG-002", "This user is already a member of the organization"),
	INVITATION_ALREADY_PENDING("ORG-003", "An active invitation already exists for this email"),
	INVALID_OR_EXPIRED_INVITATION("ORG-004", "Invitation not found or no longer valid");

	private final String code;
	private final String defaultMessage;

	OrganizationErrorCode(String code, String defaultMessage) {
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
