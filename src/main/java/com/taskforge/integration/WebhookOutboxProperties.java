package com.taskforge.integration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// relayEnabled exists so tests that drive WebhookOutboxRelay.relayOnce()
// directly can switch the scheduled poll off and not race it.
@ConfigurationProperties(prefix = "app.webhook.outbox")
public record WebhookOutboxProperties(@DefaultValue("true") boolean relayEnabled, Duration pollInterval,
		int batchSize, int maxAttempts, Duration baseBackoff, Duration maxBackoff, Duration claimTimeout) {
}
