package com.taskforge.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.login-lockout")
public record LoginLockoutProperties(int maxFailures, Duration lockoutDuration) {
}
