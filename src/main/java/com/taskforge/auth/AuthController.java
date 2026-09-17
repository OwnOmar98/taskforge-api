package com.taskforge.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.auth.dto.AuthResponse;
import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.MeResponse;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.security.CurrentUserId;
import com.taskforge.security.JwtService;
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

	public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder,
			AuthenticationManager authenticationManager, JwtService jwtService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.authenticationManager = authenticationManager;
		this.jwtService = jwtService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
		if (userRepository.findByEmail(request.email()).isPresent()) {
			throw new ConflictException(AuthErrorCode.EMAIL_IN_USE, AuthErrorCode.EMAIL_IN_USE.defaultMessage());
		}

		User user = userRepository.save(
				new User(request.email(), passwordEncoder.encode(request.password()), request.fullName()));

		return new AuthResponse(jwtService.generateAccessToken(user.getId()));
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody LoginRequest request) {
		authenticationManager.authenticate(
				new UsernamePasswordAuthenticationToken(request.email(), request.password()));

		User user = userRepository.findByEmail(request.email()).orElseThrow();

		return new AuthResponse(jwtService.generateAccessToken(user.getId()));
	}

	@GetMapping("/me")
	public MeResponse me(@CurrentUserId UUID userId) {
		User user = userRepository.findById(userId).orElseThrow();

		return new MeResponse(user.getId(), user.getEmail(), user.getFullName());
	}

}
