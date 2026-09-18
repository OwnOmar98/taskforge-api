package com.taskforge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class SecureTokenGenerator {

	private static final SecureRandom secureRandom = new SecureRandom();

	private SecureTokenGenerator() {
	}

	public static String generateRawToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// SHA-256, not BCrypt: the input is 256 bits of our own randomness, not a
	// human-chosen secret, so there's nothing low-entropy here for a slow hash
	// to protect against - a fast cryptographic hash is the correct tool.
	public static String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(hashed);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available on the JVM", e);
		}
	}

}
