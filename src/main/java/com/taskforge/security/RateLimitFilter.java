package com.taskforge.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

import com.taskforge.common.exception.GeneralErrorCode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

// Not a @Component, same reasoning as JwtAuthenticationFilter: registered
// only inside Spring Security's own chain, and specifically before it, so a
// client that's already over the limit never reaches the expensive
// BCrypt-verifying authentication attempt in the first place.
public class RateLimitFilter extends OncePerRequestFilter {

	private static final String PROTECTED_PATH = "/api/v1/auth/login";

	private final LoginRateLimiter rateLimiter;
	private final ObjectMapper objectMapper;

	public RateLimitFilter(LoginRateLimiter rateLimiter, ObjectMapper objectMapper) {
		this.rateLimiter = rateLimiter;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (PROTECTED_PATH.equals(request.getRequestURI())) {
			RateLimitResult result = rateLimiter.tryAcquire(request.getRemoteAddr());
			if (!result.allowed()) {
				writeTooManyRequests(response, result.retryAfterSeconds());
				return;
			}
		}

		filterChain.doFilter(request, response);
	}

	private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
		ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
				GeneralErrorCode.RATE_LIMIT_EXCEEDED.defaultMessage());
		problemDetail.setProperty("errorCode", GeneralErrorCode.RATE_LIMIT_EXCEEDED.code());
		problemDetail.setProperty("retryAfterSeconds", retryAfterSeconds);

		// This filter runs outside DispatcherServlet, same reason as
		// ProblemDetailAuthenticationEntryPoint: GlobalExceptionHandler never
		// sees a request rejected here, so Retry-After has to be set directly
		// rather than through the exception-carried value it uses for lockout.
		response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
		response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
		response.setContentType("application/problem+json");
		objectMapper.writeValue(response.getWriter(), problemDetail);
	}

}
