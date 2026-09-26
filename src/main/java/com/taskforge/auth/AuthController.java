package com.taskforge.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.auth.dto.AuthResponse;
import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.MeResponse;
import com.taskforge.auth.dto.RefreshRequest;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.organization.InvitationService;
import com.taskforge.security.CurrentUserId;
import com.taskforge.security.JwtService;
import com.taskforge.security.LoginAttemptService;
import com.taskforge.security.RefreshTokenService;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	private final JwtService jwtService;
	private final RefreshTokenService refreshTokenService;
	private final InvitationService invitationService;
	private final LoginAttemptService loginAttemptService;

	public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, JwtService jwtService,
			RefreshTokenService refreshTokenService, InvitationService invitationService,
			LoginAttemptService loginAttemptService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
		this.refreshTokenService = refreshTokenService;
		this.invitationService = invitationService;
		this.loginAttemptService = loginAttemptService;
	}

	// @Transactional so a bad/expired/mismatched invitationToken rolls back the
	// User creation too - registering with a token is one atomic operation,
	// not "create the account regardless, then maybe join the org".
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		if (userRepository.findByEmail(request.email()).isPresent()) {
			throw new ConflictException(AuthErrorCode.EMAIL_IN_USE, AuthErrorCode.EMAIL_IN_USE.defaultMessage());
		}

		User user = userRepository.save(
				new User(request.email(), passwordEncoder.encode(request.password()), request.fullName()));

		if (request.invitationToken() != null && !request.invitationToken().isBlank()) {
			invitationService.acceptInvitation(request.invitationToken(), user);
		}

		return new AuthResponse(jwtService.generateAccessToken(user.getId()), refreshTokenService.issue(user));
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		loginAttemptService.checkNotLocked(request.email());

		try {
			authenticationManager.authenticate(
					new UsernamePasswordAuthenticationToken(request.email(), request.password()));
		}
		catch (AuthenticationException e) {
			loginAttemptService.recordFailure(request.email());
			throw e;
		}
		loginAttemptService.recordSuccess(request.email());

		User user = userRepository.findByEmail(request.email()).orElseThrow();

		return new AuthResponse(jwtService.generateAccessToken(user.getId()), refreshTokenService.issue(user));
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
		RefreshTokenService.TokenPair rotated = refreshTokenService.rotate(request.refreshToken());

		return new AuthResponse(jwtService.generateAccessToken(rotated.user().getId()), rotated.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		refreshTokenService.revoke(request.refreshToken());
	}

	@GetMapping("/me")
	public MeResponse me(@CurrentUserId UUID userId) {
		User user = userRepository.findById(userId).orElseThrow();

		return new MeResponse(user.getId(), user.getEmail(), user.getFullName());
	}

}
