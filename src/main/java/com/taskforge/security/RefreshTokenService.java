package com.taskforge.security;

import java.time.Instant;

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
		RefreshToken token = refreshTokenRepository.findByTokenHash(SecureTokenGenerator.hash(rawToken))
				.orElseThrow(() -> new UnauthorizedException(AuthErrorCode.INVALID_REFRESH_TOKEN,
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
