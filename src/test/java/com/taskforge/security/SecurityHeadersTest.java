package com.taskforge.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.ObjectMapper;

import com.taskforge.auth.dto.RegisterRequest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class SecurityHeadersTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@DynamicPropertySource
	static void corsProperties(DynamicPropertyRegistry registry) {
		registry.add("app.cors.allowed-origins[0]", () -> "https://allowed.example.test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void responsesCarryANoReferrerPolicy() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new RegisterRequest("headers@acme.test", "supersecret", "Name"))))
				.andExpect(status().isCreated())
				.andExpect(header().string("Referrer-Policy", "no-referrer"));
	}

	@Test
	void aRequestFromAnAllowedOriginGetsTheCorsHeader() throws Exception {
		mockMvc.perform(get("/actuator/health").header("Origin", "https://allowed.example.test"))
				.andExpect(header().string("Access-Control-Allow-Origin", "https://allowed.example.test"));
	}

	@Test
	void aRequestFromAnUnlistedOriginGetsNoCorsHeader() throws Exception {
		mockMvc.perform(get("/actuator/health").header("Origin", "https://not-allowed.example.test"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

}
