package com.taskforge.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Empty by default: no frontend origin exists for this project yet, and an
// empty allow-list is the correct secure default (every cross-origin
// browser request rejected) rather than an accidental wildcard.
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

	public CorsProperties {
		allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
	}

}
