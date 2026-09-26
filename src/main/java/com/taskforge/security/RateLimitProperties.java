package com.taskforge.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rate-limit.login")
public record RateLimitProperties(int maxAttempts, Duration window) {
}
