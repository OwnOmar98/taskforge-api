package com.taskforge.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.auth.AuthErrorCode;
import com.taskforge.common.exception.UnauthorizedException;
import com.taskforge.user.User;

@Service
public class RefreshTokenService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenProperties properties;
	private final SecureRandom secureRandom = new SecureRandom();

	public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, RefreshTokenProperties properties) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.properties = properties;
	}

	public String issue(User user) {
		return issueNew(user).rawValue();
	}

	@Transactional
	public TokenPair rotate(String rawRefreshToken) {
		RefreshToken existing = findActiveOrThrow(rawRefreshToken);
		IssuedToken next = issueNew(existing.getUser());
		existing.revoke(next.entity());
		return new TokenPair(existing.getUser(), next.rawValue());
	}

	@Transactional
	public void revoke(String rawRefreshToken) {
		findActiveOrThrow(rawRefreshToken).revoke(null);
	}

	private RefreshToken findActiveOrThrow(String rawToken) {
		RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
				.orElseThrow(() -> new UnauthorizedException(AuthErrorCode.INVALID_REFRESH_TOKEN,
						AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage()));

		if (!token.isActive()) {
			throw new UnauthorizedException(AuthErrorCode.INVALID_REFRESH_TOKEN,
					AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage());
		}

		return token;
	}

	private IssuedToken issueNew(User user) {
		String rawValue = generateRawToken();
		RefreshToken entity = refreshTokenRepository.save(
				new RefreshToken(user, hash(rawValue), Instant.now().plus(properties.ttl())));
		return new IssuedToken(entity, rawValue);
	}

	private String generateRawToken() {
		byte[] bytes = new byte[32];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// SHA-256, not BCrypt: the input is 256 bits of our own randomness, not a
	// human-chosen secret, so there's nothing low-entropy here for a slow hash
	// to protect against - a fast cryptographic hash is the correct tool.
	private String hash(String rawToken) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(hashed);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is always available on the JVM", e);
		}
	}

	private record IssuedToken(RefreshToken entity, String rawValue) {
	}

	public record TokenPair(User user, String refreshToken) {
	}

}
