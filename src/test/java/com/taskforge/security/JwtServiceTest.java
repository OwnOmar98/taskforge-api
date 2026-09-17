package com.taskforge.security;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {

	private final JwtProperties properties = new JwtProperties(
			"unit-test-jwt-signing-secret-0123456789-0123456789", Duration.ofMinutes(15));

	private final JwtService jwtService = new JwtService(properties);

	@Test
	void generatedTokenRoundTripsToTheSameUserId() {
		UUID userId = UUID.randomUUID();

		String token = jwtService.generateAccessToken(userId);

		assertEquals(userId, jwtService.extractUserId(token));
	}

	@Test
	void rejectsTokenSignedWithADifferentSecret() {
		String token = jwtService.generateAccessToken(UUID.randomUUID());
		JwtService otherService = new JwtService(
				new JwtProperties("a-completely-different-jwt-signing-secret-abcdef", Duration.ofMinutes(15)));

		assertThrows(SignatureException.class, () -> otherService.extractUserId(token));
	}

	@Test
	void rejectsExpiredToken() {
		JwtService shortLived = new JwtService(new JwtProperties(properties.secret(), Duration.ofMillis(1)));
		String token = shortLived.generateAccessToken(UUID.randomUUID());

		await(50);

		assertThrows(ExpiredJwtException.class, () -> shortLived.extractUserId(token));
	}

	private void await(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
