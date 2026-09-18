package com.taskforge.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.RefreshRequest;
import com.taskforge.auth.dto.RegisterRequest;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuthControllerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void registerThenLoginThenAccessProtectedEndpoint() throws Exception {
		RegisterRequest registerRequest = new RegisterRequest("owner@acme.test", "supersecret", "Owner");

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(registerRequest)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty());

		LoginRequest loginRequest = new LoginRequest("owner@acme.test", "supersecret");

		MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(loginRequest)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn();

		String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
				.get("accessToken").stringValue();

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("owner@acme.test"))
				.andExpect(jsonPath("$.fullName").value("Owner"));
	}

	@Test
	void loginWithWrongPasswordReturns401() throws Exception {
		RegisterRequest registerRequest = new RegisterRequest("wrongpass@acme.test", "supersecret", "Owner");
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(registerRequest)))
				.andExpect(status().isCreated());

		LoginRequest badLogin = new LoginRequest("wrongpass@acme.test", "wrongpassword");
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(badLogin)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-001"));
	}

	@Test
	void loginWithUnknownEmailReturns401() throws Exception {
		LoginRequest unknown = new LoginRequest("nobody@acme.test", "whatever123");
		mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(unknown)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void registeringDuplicateEmailReturns409() throws Exception {
		RegisterRequest request = new RegisterRequest("dup@acme.test", "supersecret", "Someone");

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(request)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("AUTH-002"));
	}

	@Test
	void meWithoutTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-003"));
	}

	@Test
	void meWithGarbageTokenReturns401() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer not-a-real-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-003"));
	}

	@Test
	void refreshRotatesTokensAndOldTokenIsRejectedOnReuse() throws Exception {
		String initialRefreshToken = registerAndGetRefreshToken("rotate@acme.test");

		MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(initialRefreshToken))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty())
				.andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andReturn();

		String rotatedRefreshToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
				.get("refreshToken").stringValue();

		assertNotEquals(initialRefreshToken, rotatedRefreshToken);

		// Reusing the now-rotated original token must be rejected.
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(initialRefreshToken))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-004"));

		// The new token from rotation must still work.
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(rotatedRefreshToken))))
				.andExpect(status().isOk());
	}

	@Test
	void refreshWithInvalidTokenReturns401() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest("not-a-real-refresh-token"))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-004"));
	}

	@Test
	void logoutRevokesTheRefreshToken() throws Exception {
		String refreshToken = registerAndGetRefreshToken("logout@acme.test");

		mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("AUTH-004"));
	}

	private String registerAndGetRefreshToken(String email) throws Exception {
		RegisterRequest registerRequest = new RegisterRequest(email, "supersecret", "Owner");

		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(registerRequest)))
				.andExpect(status().isCreated())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("refreshToken").stringValue();
	}

}
