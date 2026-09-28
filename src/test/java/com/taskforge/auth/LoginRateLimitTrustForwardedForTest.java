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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Separate from LoginRateLimitTest: trust-forwarded-for needs its own
// @DynamicPropertySource override (true here, false - the real default -
// there), and mixing both postures in one class/context would make it easy
// to lose track of which test relies on which value.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoginRateLimitTrustForwardedForTest {

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
		registry.add("app.rate-limit.login.trust-forwarded-for", () -> "true");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void differentForwardedForValuesBehindTheSameProxyGetSeparateBudgets() throws Exception {
		// Same simulated remote address for every request - as if every one
		// of these passed through the same reverse proxy - but a different
		// X-Forwarded-For value each time. With trust-forwarded-for enabled,
		// that header (not the shared proxy address) is what determines the
		// bucket, so client A being over its limit must not affect client B.
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(request -> {
								request.setRemoteAddr("10.0.0.1");
								return request;
							})
							.header("X-Forwarded-For", "203.0.113.10")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new LoginRequest("client-a-" + i + "@acme.test", "whatever123"))))
					.andExpect(status().isUnauthorized());
		}

		mockMvc.perform(post("/api/v1/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.0.0.1");
							return request;
						})
						.header("X-Forwarded-For", "203.0.113.10")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("client-a-over-limit@acme.test", "whatever123"))))
				.andExpect(status().isTooManyRequests());

		mockMvc.perform(post("/api/v1/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.0.0.1");
							return request;
						})
						.header("X-Forwarded-For", "203.0.113.20")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("client-b@acme.test", "whatever123"))))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void takesOnlyTheFirstAddressWhenForwardedForHasAProxyChain() throws Exception {
		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/api/v1/auth/login")
							.with(request -> {
								request.setRemoteAddr("10.0.0.1");
								return request;
							})
							.header("X-Forwarded-For", "203.0.113.30, 10.0.0.5, 10.0.0.1")
							.contentType(MediaType.APPLICATION_JSON)
							.content(objectMapper.writeValueAsString(
									new LoginRequest("chain-" + i + "@acme.test", "whatever123"))))
					.andExpect(status().isUnauthorized());
		}

		// Same original client (203.0.113.30), a differently-ordered/spaced
		// chain string - still the same bucket, over the limit now.
		mockMvc.perform(post("/api/v1/auth/login")
						.with(request -> {
							request.setRemoteAddr("10.0.0.1");
							return request;
						})
						.header("X-Forwarded-For", "203.0.113.30,10.0.0.5,10.0.0.1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new LoginRequest("chain-over-limit@acme.test", "whatever123"))))
				.andExpect(status().isTooManyRequests());
	}

}
