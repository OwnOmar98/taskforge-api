package com.taskforge.security;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.auth.AuthErrorCode;
import com.taskforge.common.exception.UnauthorizedException;
import com.taskforge.user.User;

@Service
public class RefreshTokenService {

	private final RefreshTokenRepository refreshTokenRepository;
	private final RefreshTokenProperties properties;

	public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, RefreshTokenProperties properties) {
		this.refreshTokenRepository = refreshTokenRepository;
		this.properties = properties;
	}

	// Only a single save, so this is already atomic without an explicit
	// boundary - annotated anyway so every public method here states its
	// transactional intent explicitly rather than by omission.
	@Transactional
	public String issue(User user) {
		return issueNew(user).rawValue();
	}

	// Locked: without it, two concurrent requests racing the same raw token
	// (a legitimate refresh racing a stolen token's use, or two overlapping
	// stolen-token uses) can both pass isActive() before either commits its
	// own revoke(), rotating the same token twice and defeating rotation-based
	// theft detection.
	@Transactional
	public TokenPair rotate(String rawRefreshToken) {
		RefreshToken existing = findActiveOrThrowForUpdate(rawRefreshToken);
		IssuedToken next = issueNew(existing.getUser());
		existing.revoke(next.entity());
		return new TokenPair(existing.getUser(), next.rawValue());
	}

	@Transactional
	public void revoke(String rawRefreshToken) {
		findActiveOrThrow(rawRefreshToken).revoke(null);
	}

	private RefreshToken findActiveOrThrow(String rawToken) {
		return requireActive(refreshTokenRepository.findByTokenHash(SecureTokenGenerator.hash(rawToken)));
	}

	private RefreshToken findActiveOrThrowForUpdate(String rawToken) {
		return requireActive(refreshTokenRepository.findByTokenHashForUpdate(SecureTokenGenerator.hash(rawToken)));
	}

	private RefreshToken requireActive(Optional<RefreshToken> lookup) {
		RefreshToken token = lookup.orElseThrow(() -> new UnauthorizedException(AuthErrorCode.INVALID_REFRESH_TOKEN,
				AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage()));

		if (!token.isActive()) {
			throw new UnauthorizedException(AuthErrorCode.INVALID_REFRESH_TOKEN,
					AuthErrorCode.INVALID_REFRESH_TOKEN.defaultMessage());
		}

		return token;
	}

	private IssuedToken issueNew(User user) {
		String rawValue = SecureTokenGenerator.generateRawToken();
		RefreshToken entity = refreshTokenRepository.save(
				new RefreshToken(user, SecureTokenGenerator.hash(rawValue), Instant.now().plus(properties.ttl())));
		return new IssuedToken(entity, rawValue);
	}

	private record IssuedToken(RefreshToken entity, String rawValue) {
	}

	public record TokenPair(User user, String refreshToken) {
	}

}
