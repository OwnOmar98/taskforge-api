package com.taskforge.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.auth.dto.LoginRequest;

import static com.taskforge.common.exception.GeneralErrorCode.RATE_LIMIT_EXCEEDED;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A dedicated class rather than adding these to AuthControllerTest: it needs
// a much lower max-attempts than every other test that incidentally calls
// /login to get a token, and overriding that per-class via
// @DynamicPropertySource keeps this test's threshold from leaking into (or
// being crowded out by) anyone else's login calls.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginRateLimitTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@DynamicPropertySource
	static void rateLimitProperties(DynamicPropertyRegistry registry) {
		registry.add("app.rate-limit.login.max-attempts", () -> "3");
		registry.add("app.rate-limit.login.window", () -> "10s");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void theRequestAfterTheLimitIsRejectedRegardlessOfCredentials() throws Exception {
		// A garbage login (never a real account) still counts against the
		// per-IP limit - the limiter guards the endpoint itself, not any one
		// account, and doesn't care whether the credentials would ever work.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new LoginRequest("nobody" + i + "@acme.test", "whatever123"))))
					.andExpect(status().isUnauthorized());
		}

		// window is 10s, so the caller should be told to wait somewhere in
		// (0, 10] seconds - not left to guess or poll.
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("nobody-over-limit@acme.test", "whatever123"))))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.errorCode").value(RATE_LIMIT_EXCEEDED.code()))
				.andExpect(jsonPath("$.retryAfterSeconds", greaterThan(0)))
				.andExpect(jsonPath("$.retryAfterSeconds", lessThanOrEqualTo(10)))
				.andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));
	}

	@Test
	void aDifferentClientIpIsNotAffectedByAnotherIpsRateLimit() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(request -> {
								request.setRemoteAddr("10.0.0.1");
								return request;
							})
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new LoginRequest("ip-a-" + i + "@acme.test", "whatever123"))))
					.andExpect(status().isUnauthorized());
		}

		// 10.0.0.1 is now over its limit, but a request from a different IP
		// must still be evaluated against its own, separate budget.
		mockMvc.perform(post("/api/v1/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.0.0.2");
							return request;
						})
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("ip-b@acme.test", "whatever123"))))
				.andExpect(status().isUnauthorized());
	}

	// app.rate-limit.login.trust-forwarded-for isn't set here, so it's false
	// (RateLimitProperties' own default) - a client-supplied X-Forwarded-For
	// must be ignored entirely, or an attacker could spoof a unique value per
	// request to bypass the limit outright. See
	// LoginRateLimitTrustForwardedForTest for the opt-in behavior.
	@Test
	void aSpoofedForwardedForHeaderIsIgnoredByDefault() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(request -> {
								request.setRemoteAddr("10.0.0.3");
								return request;
							})
							.header("X-Forwarded-For", "1.2.3." + i)
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new LoginRequest("spoof" + i + "@acme.test", "whatever123"))))
					.andExpect(status().isUnauthorized());
		}

		// A 4th, still-different X-Forwarded-For value from the same
		// (simulated) remote address must still be rejected - proving the
		// header had no effect on which bucket these requests landed in.
		mockMvc.perform(post("/api/v1/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.0.0.3");
							return request;
						})
						.header("X-Forwarded-For", "1.2.3.99")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("spoof-over-limit@acme.test", "whatever123"))))
				.andExpect(status().isTooManyRequests());
	}

}
