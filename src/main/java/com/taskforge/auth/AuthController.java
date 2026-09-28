package com.taskforge.auth;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Authentication")
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
	@Operation(summary = "Register a new user, optionally accepting an invitation")
	@SecurityRequirements
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		if (userRepository.findByEmail(request.email()).isPresent()) {
			throw new ConflictException(AuthErrorCode.EMAIL_IN_USE, AuthErrorCode.EMAIL_IN_USE.defaultMessage());
		}

		User user;
		try {
			// The check above is a point-in-time read, not a lock - two concurrent
			// registrations for the same email can both pass it before either
			// commits. The unique constraint on users.email is the real backstop;
			// catching its violation here keeps the specific EMAIL_IN_USE code
			// instead of falling through to GlobalExceptionHandler's generic
			// DataIntegrityViolationException handler.
			user = userRepository.saveAndFlush(
					new User(request.email(), passwordEncoder.encode(request.password()), request.fullName()));
		}
		catch (DataIntegrityViolationException e) {
			throw new ConflictException(AuthErrorCode.EMAIL_IN_USE, AuthErrorCode.EMAIL_IN_USE.defaultMessage());
		}

		if (request.invitationToken() != null && !request.invitationToken().isBlank()) {
			invitationService.acceptInvitation(request.invitationToken(), user);
		}

		return new AuthResponse(jwtService.generateAccessToken(user.getId()), refreshTokenService.issue(user));
	}

	@Operation(summary = "Log in with email and password")
	@ApiResponse(responseCode = "401", description = "Invalid credentials",
			content = @Content(schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
	@SecurityRequirements
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

	@Operation(summary = "Exchange a refresh token for a new token pair")
	@SecurityRequirements
	@PostMapping("/refresh")
	public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
		RefreshTokenService.TokenPair rotated = refreshTokenService.rotate(request.refreshToken());

		return new AuthResponse(jwtService.generateAccessToken(rotated.user().getId()), rotated.refreshToken());
	}

	@Operation(summary = "Revoke a refresh token")
	@SecurityRequirements
	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@Valid @RequestBody RefreshRequest request) {
		refreshTokenService.revoke(request.refreshToken());
	}

	@Operation(summary = "Get the current authenticated user")
	@GetMapping("/me")
	public MeResponse me(@CurrentUserId UUID userId) {
		User user = userRepository.findById(userId).orElseThrow();

		return new MeResponse(user.getId(), user.getEmail(), user.getFullName());
	}

}
