package com.taskforge.security;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.taskforge.user.UserRepository;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Not a @Component: registered explicitly (and only) inside SecurityConfig's
// filter chain, so it never gets swept into unrelated @WebMvcTest slices and
// never gets double-registered as a plain servlet filter.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtService jwtService;
	private final UserRepository userRepository;

	public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
		this.jwtService = jwtService;
		this.userRepository = userRepository;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
			@NonNull FilterChain filterChain) throws ServletException, IOException {
		String header = request.getHeader("Authorization");

		if (header != null && header.startsWith("Bearer ")) {
			try {
				UUID userId = jwtService.extractUserId(header.substring(7));
				userRepository.findById(userId).ifPresent(user -> {
					var authentication = new UsernamePasswordAuthenticationToken(user.getId(), null, List.of());
					SecurityContextHolder.getContext().setAuthentication(authentication);
				});
			} catch (JwtException | IllegalArgumentException ignored) {
				// Invalid/expired token (JwtException), or a validly-signed token whose
				// subject isn't a UUID (IllegalArgumentException from UUID.fromString) -
				// either way, leave the request unauthenticated rather than rejecting it
				// here, so authorization (not this filter) decides 401 vs 403. Without
				// catching the second case too, it propagates out of this filter
				// entirely - past both Spring Security's own exception handling and
				// GlobalExceptionHandler, neither of which sees exceptions thrown this
				// early in the chain - as a raw, unhandled error instead of the app's
				// normal JSON error responses.
			}
		}

		filterChain.doFilter(request, response);
	}

}
