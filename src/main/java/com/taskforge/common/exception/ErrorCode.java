package com.taskforge.common.exception;

/**
 * Implemented by domain-specific enums (e.g. AuthErrorCode) so each domain owns
 * its own set of scenario-specific codes, rather than every exception type
 * sharing one flat, generic code regardless of what actually went wrong.
 */
public interface ErrorCode {

	String code();

	String defaultMessage();

}
