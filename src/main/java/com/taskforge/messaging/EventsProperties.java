package com.taskforge.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.events")
public record EventsProperties(Transport transport, Outbox outbox) {

	// IN_PROCESS runs the handlers straight from the outbox, without a broker:
	// the test profile uses it so only the dedicated broker tests need a
	// RabbitMQ container.
	public enum Transport {
		RABBITMQ, IN_PROCESS
	}

	public record Outbox(@DefaultValue("true") boolean pollEnabled, Duration pollInterval, int batchSize,
			Duration claimTimeout, Duration baseBackoff, Duration maxBackoff) {
	}

}
