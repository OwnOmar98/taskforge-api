package com.taskforge.organization;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.invitation")
public record InvitationProperties(Duration ttl) {
}
