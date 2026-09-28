package com.taskforge.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class JwtAuthenticationFilterTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	// Matches application-test.yml's app.jwt.secret. A real JwtService can
	// never produce a non-UUID subject (generateAccessToken only ever takes a
	// UUID), so this builds a token directly with the same key to simulate one
	// minted by different code - a future service/ops token, say - whose
	// subject was never a UUID to begin with.
	private static final SecretKey KEY = Keys
			.hmacShaKeyFor("test-only-jwt-signing-secret-0123456789-0123456789".getBytes(StandardCharsets.UTF_8));

	@Autowired
	private MockMvc mockMvc;

	@Test
	void aValidlySignedTokenWithANonUuidSubjectIsUnauthenticatedNotA500() throws Exception {
		Instant now = Instant.now();
		String token = Jwts.builder()
				.subject("not-a-uuid")
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plusSeconds(900)))
				.signWith(KEY)
				.compact();

		mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

}
