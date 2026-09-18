package com.taskforge.common.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.taskforge.auth.AuthErrorCode;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
		return problemDetail(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage());
	}

	@ExceptionHandler(ConflictException.class)
	public ProblemDetail handleConflict(ConflictException ex) {
		return problemDetail(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage());
	}

	@ExceptionHandler(UnauthorizedException.class)
	public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
		return problemDetail(HttpStatus.UNAUTHORIZED, ex.getErrorCode(), ex.getMessage());
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
		return problemDetail(HttpStatus.FORBIDDEN, GeneralErrorCode.INSUFFICIENT_PERMISSIONS,
				GeneralErrorCode.INSUFFICIENT_PERMISSIONS.defaultMessage());
	}

	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail handleAuthentication(AuthenticationException ex) {
		// Same message regardless of whether the email or the password was wrong -
		// Spring Security already normalizes "user not found" to this for us, so
		// don't undo that by branching on exception subtype here.
		return problemDetail(HttpStatus.UNAUTHORIZED, AuthErrorCode.INVALID_CREDENTIALS,
				AuthErrorCode.INVALID_CREDENTIALS.defaultMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		ProblemDetail problemDetail = problemDetail(HttpStatus.BAD_REQUEST, GeneralErrorCode.VALIDATION_FAILED,
				GeneralErrorCode.VALIDATION_FAILED.defaultMessage());
		List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(this::toFieldError)
				.toList();
		problemDetail.setProperty("errors", errors);
		return problemDetail;
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleUnexpected(Exception ex) {
		log.error("Unexpected error", ex);
		return problemDetail(HttpStatus.INTERNAL_SERVER_ERROR, GeneralErrorCode.SERVER_UNEXPECTED_ERROR,
				GeneralErrorCode.SERVER_UNEXPECTED_ERROR.defaultMessage());
	}

	private ProblemDetail problemDetail(HttpStatus status, ErrorCode errorCode, String detail) {
		ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
		problemDetail.setProperty("errorCode", errorCode.code());
		return problemDetail;
	}

	private Map<String, String> toFieldError(FieldError fieldError) {
		return Map.of("field", fieldError.getField(), "message", fieldError.getDefaultMessage());
	}

}
