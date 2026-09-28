package com.taskforge.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
	void extractsTheExpirationTheTokenWasIssuedWith() {
		Instant before = Instant.now();

		String token = jwtService.generateAccessToken(UUID.randomUUID());

		// JWT exp has whole-second precision, so allow for the truncation.
		Instant expiration = jwtService.extractExpiration(token);
		Instant expected = before.plus(properties.accessTokenTtl());
		assertTrue(!expiration.isBefore(expected.minusSeconds(1)) && !expiration.isAfter(expected.plusSeconds(1)));
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

	// generateAccessToken can never produce a non-UUID subject, so this builds
	// one directly with the same key to simulate a validly-signed token minted
	// by different code (a future service/ops token, say) whose subject was
	// never a UUID - documents exactly what JwtAuthenticationFilter's catch
	// block needs to handle alongside JwtException.
	@Test
	void aNonUuidSubjectThrowsIllegalArgumentExceptionNotAJwtException() {
		SecretKey key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
		Instant now = Instant.now();
		String token = Jwts.builder()
				.subject("not-a-uuid")
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(properties.accessTokenTtl())))
				.signWith(key)
				.compact();

		assertThrows(IllegalArgumentException.class, () -> jwtService.extractUserId(token));
	}

	private void await(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

}
