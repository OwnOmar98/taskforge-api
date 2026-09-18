package com.taskforge.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.taskforge.common.exception.UnauthorizedException;
import com.taskforge.user.User;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Test
	void rotatingAnExpiredTokenThrows() {
		RefreshTokenService service = new RefreshTokenService(refreshTokenRepository,
				new RefreshTokenProperties(Duration.ofDays(30)));

		User user = new User("expired@acme.test", "hash", "Someone");
		RefreshToken expired = new RefreshToken(user, "some-hash", Instant.now().minusSeconds(1));

		when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

		assertThrows(UnauthorizedException.class, () -> service.rotate("raw-token-value"));
	}

	@Test
	void rotatingARevokedTokenThrows() {
		RefreshTokenService service = new RefreshTokenService(refreshTokenRepository,
				new RefreshTokenProperties(Duration.ofDays(30)));

		User user = new User("revoked@acme.test", "hash", "Someone");
		RefreshToken revoked = new RefreshToken(user, "some-hash", Instant.now().plusSeconds(3600));
		revoked.revoke(null);

		when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(revoked));

		assertThrows(UnauthorizedException.class, () -> service.rotate("raw-token-value"));
	}

	@Test
	void revokingAnUnknownTokenThrows() {
		RefreshTokenService service = new RefreshTokenService(refreshTokenRepository,
				new RefreshTokenProperties(Duration.ofDays(30)));

		when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

		assertThrows(UnauthorizedException.class, () -> service.revoke("unknown-token"));
	}

}
