package com.taskforge.common.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.taskforge.auth.AuthErrorCode;

import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ResourceNotFoundException.class)
	public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
		return problemDetail(HttpStatus.NOT_FOUND, ex.getErrorCode(), ex.getMessage());
	}

	// Spring MVC throws this for a request with no matching route at all -
	// without this, it would fall through to the catch-all Exception handler
	// below and be misreported as a 500 instead of a 404.
	@ExceptionHandler(NoResourceFoundException.class)
	public ProblemDetail handleNoResourceFound(NoResourceFoundException ex) {
		return problemDetail(HttpStatus.NOT_FOUND, GeneralErrorCode.RESOURCE_NOT_FOUND,
				GeneralErrorCode.RESOURCE_NOT_FOUND.defaultMessage());
	}

	@ExceptionHandler(ConflictException.class)
	public ProblemDetail handleConflict(ConflictException ex) {
		return problemDetail(HttpStatus.CONFLICT, ex.getErrorCode(), ex.getMessage());
	}

	@ExceptionHandler(BadRequestException.class)
	public ProblemDetail handleBadRequest(BadRequestException ex) {
		return problemDetail(HttpStatus.BAD_REQUEST, ex.getErrorCode(), ex.getMessage());
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

	@ExceptionHandler(TooManyRequestsException.class)
	public ProblemDetail handleTooManyRequests(TooManyRequestsException ex, HttpServletResponse response) {
		// A 429 that doesn't say when to try again forces the client to guess
		// or poll - Retry-After (RFC 9110) is the standard way to answer that,
		// and it's here as a real header, not just a body field, so a generic
		// HTTP client can act on it without knowing this API's error schema.
		response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));

		ProblemDetail problemDetail = problemDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getErrorCode(), ex.getMessage());
		problemDetail.setProperty("retryAfterSeconds", ex.getRetryAfterSeconds());
		return problemDetail;
	}

	// Backstop for the genuine race the manual version check in ProjectService
	// doesn't cover: a concurrent write slipping in between that check and this
	// flush. Hibernate's own @Version-driven UPDATE...WHERE clause still
	// catches that at the DB level and throws this.
	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ProblemDetail handleOptimisticLocking(OptimisticLockingFailureException ex) {
		return problemDetail(HttpStatus.CONFLICT, GeneralErrorCode.RESOURCE_CONFLICT,
				GeneralErrorCode.RESOURCE_CONFLICT.defaultMessage());
	}

	// Backstop for the same class of race as OptimisticLockingFailureException
	// above, but for a unique constraint rather than @Version: an existence
	// check (e.g. "is this email/key/name already taken?") and the insert that
	// follows it are two separate statements, so two concurrent requests can
	// both pass the check before either commits. Call sites with a specific,
	// known-likely constraint (email, project key, label name, ...) should
	// still catch this locally and rethrow their own ConflictException for a
	// precise error code - this is the backstop for whatever doesn't.
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		return problemDetail(HttpStatus.CONFLICT, GeneralErrorCode.RESOURCE_CONFLICT,
				GeneralErrorCode.RESOURCE_CONFLICT.defaultMessage());
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
