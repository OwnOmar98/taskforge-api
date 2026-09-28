package com.taskforge.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// trustForwardedFor defaults to false (the safe posture): X-Forwarded-For is
// a plain client-settable header, and trusting it without a reverse proxy in
// front that's actually configured to overwrite/strip any client-supplied
// value would make rate limiting trivially bypassable (a spoofed, unique
// value per request) rather than fixing it. Only flip this on for a
// deployment where that's genuinely true.
@ConfigurationProperties(prefix = "app.rate-limit.login")
public record RateLimitProperties(int maxAttempts, Duration window,
		@DefaultValue("false") boolean trustForwardedFor) {
}
