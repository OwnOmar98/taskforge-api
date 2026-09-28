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
import com.taskforge.auth.dto.RegisterRequest;

import static com.taskforge.auth.AuthErrorCode.ACCOUNT_LOCKED;
import static com.taskforge.auth.AuthErrorCode.INVALID_CREDENTIALS;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A dedicated class, same reasoning as LoginRateLimitTest: the expiry test
// below needs a much shorter lockoutDuration than AuthControllerTest's
// shared value, overridden per-class via @DynamicPropertySource.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginLockoutTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@DynamicPropertySource
	static void lockoutProperties(DynamicPropertyRegistry registry) {
		registry.add("app.login-lockout.max-failures", () -> "4");
		registry.add("app.login-lockout.lockout-duration", () -> "2s");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void accountIsLockedAfterTooManyFailedLoginAttempts() throws Exception {
		register("lockout@acme.test");

		LoginRequest badLogin = new LoginRequest("lockout@acme.test", "wrongpassword");
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(badLogin)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value(INVALID_CREDENTIALS.code()));
		}

		// Even the correct password is rejected now - the account itself is
		// locked, not just this particular (wrong) credential.
		// lockoutDuration is 2s here, and the TTL was set on the first
		// failure - so the caller should be told to wait somewhere in (0, 2].
		LoginRequest correctLogin = new LoginRequest("lockout@acme.test", "supersecret");
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(correctLogin)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.errorCode").value(ACCOUNT_LOCKED.code()))
				.andExpect(jsonPath("$.retryAfterSeconds", greaterThan(0)))
				.andExpect(jsonPath("$.retryAfterSeconds", lessThanOrEqualTo(2)))
				.andExpect(header().exists("Retry-After"));
	}

	@Test
	void successfulLoginClearsPriorFailedAttempts() throws Exception {
		register("resetcount@acme.test");

		LoginRequest badLogin = new LoginRequest("resetcount@acme.test", "wrongpassword");
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(badLogin)))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("resetcount@acme.test", "supersecret"))))
				.andExpect(status().isOk());

		// Another 3 failures right after a successful login stays under the
		// lockout threshold (4) - if the earlier streak hadn't been cleared on
		// success, this cumulative 6th failure would have tripped it.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(badLogin)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.errorCode").value(INVALID_CREDENTIALS.code()));
		}
	}

	@Test
	void lockoutExpiresAfterTheConfiguredDuration() throws Exception {
		register("expiry@acme.test");

		LoginRequest badLogin = new LoginRequest("expiry@acme.test", "wrongpassword");
		for (int i = 0; i < 4; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(badLogin)))
					.andExpect(status().isUnauthorized());
		}

		LoginRequest correctLogin = new LoginRequest("expiry@acme.test", "supersecret");
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(correctLogin)))
				.andExpect(status().isTooManyRequests());

		// lockoutDuration is 2s here; wait past it and the same correct
		// credentials must be accepted again without any manual reset.
		Thread.sleep(2100);

		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(correctLogin)))
				.andExpect(status().isOk());
	}

	private void register(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest(email, "supersecret", "Owner"))))
				.andExpect(status().isCreated());
	}

}
