package com.taskforge.realtime;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.realtime")
public record RealtimeProperties(Duration heartbeatInterval, int maxConnectionsPerUser, int replayLimit) {
}
