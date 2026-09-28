package com.taskforge.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({ JwtProperties.class, RefreshTokenProperties.class, RateLimitProperties.class,
		LoginLockoutProperties.class, CorsProperties.class })
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	// static: Spring Security needs this published before it initializes its
	// own method-security @Configuration classes.
	@Bean
	static MethodSecurityExpressionHandler methodSecurityExpressionHandler(PermissionEvaluator permissionEvaluator) {
		DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
		handler.setPermissionEvaluator(permissionEvaluator);
		return handler;
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
		return config.getAuthenticationManager();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(corsProperties.allowedOrigins());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
		configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		// A bearer token is a header the client attaches itself, not a cookie the
		// browser sends automatically - there's no ambient credential to exchange.
		configuration.setAllowCredentials(false);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
			UserRepository userRepository, LoginRateLimiter loginRateLimiter, ObjectMapper objectMapper,
			CorsConfigurationSource corsConfigurationSource, RateLimitProperties rateLimitProperties)
			throws Exception {
		http
				// No cookie-based session exists for CSRF to protect; auth is a bearer
				// token the client attaches itself. That would change if refresh tokens
				// ever moved into a cookie instead of the response body, since a
				// cookie rides along automatically on every request a browser makes.
				.csrf(AbstractHttpConfigurer::disable)
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// contentTypeOptions/frameOptions/HSTS are Spring Security's own
				// defaults, already present without any of this. Content-Security-
				// Policy is deliberately not set: it's a browser-HTML-rendering
				// protection, and swagger-ui (this app's only HTML surface, permitted
				// below) is trusted bundled tooling, not attacker-influenced content -
				// there's nothing here for a CSP to actually guard. Referrer-Policy
				// isn't on by default, and every response here is JSON with no
				// legitimate reason to leak the referring URL onward.
				.headers(headers -> headers
						.referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
				// No httpBasic()/formLogin() is configured (there's no login page or
				// browser challenge for a JSON API), so without this Spring Security's
				// default falls back to 403 for missing/invalid credentials too. A
				// rejected/missing token is caught here (filter chain), never reaching
				// GlobalExceptionHandler, so the ProblemDetail body has to be written
				// explicitly rather than relying on @ExceptionHandler.
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(new ProblemDetailAuthenticationEntryPoint(objectMapper)))
				.authorizeHttpRequests(auth -> auth
						// A blanket permitAll here is safe, not lax: management.endpoints.web
						// .exposure.include is the real gate, restricted to health/info/
						// metrics/prometheus - anything not in that list 404s regardless of
						// this rule. In prod this port isn't even reachable publicly (see
						// management.server.port), so this only matters for local dev/test.
						.requestMatchers("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/refresh",
								"/api/v1/auth/logout", "/actuator/**", "/swagger-ui/**", "/swagger-ui.html",
								"/v3/api-docs/**")
						.permitAll()
						.anyRequest().authenticated())
				.addFilterBefore(new JwtAuthenticationFilter(jwtService, userRepository),
						UsernamePasswordAuthenticationFilter.class)
				// Before the JWT filter, not just before authentication: a login
				// request carries no bearer token anyway, but a client already over
				// the limit should never reach the BCrypt-verifying authenticate()
				// call in AuthController - that work is deliberately slow, and doing
				// it anyway for a request we're about to reject just wastes it.
				.addFilterBefore(new RateLimitFilter(loginRateLimiter, objectMapper, rateLimitProperties),
						JwtAuthenticationFilter.class);

		return http.build();
	}

}
