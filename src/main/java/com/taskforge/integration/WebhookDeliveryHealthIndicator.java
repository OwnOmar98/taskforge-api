package com.taskforge.integration;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

// A genuinely custom signal, unlike DB/Redis connectivity - Spring Boot
// Actuator already ships correct, connection-pool-aware health indicators
// for both of those, so writing our own would just duplicate them. This one
// reports "DEGRADED", not "DOWN": an open circuit breaker means outbound
// webhook delivery to some org's endpoint is currently failing, not that
// this instance is unfit to serve traffic - the default status aggregator
// only recognizes DOWN/OUT_OF_SERVICE/UP/UNKNOWN, so an unrecognized
// "DEGRADED" is safely excluded from the overall aggregate status/HTTP code
// while still showing up as its own component. It's also deliberately left
// out of the readiness group in application.yml for the same reason.
@Component
public class WebhookDeliveryHealthIndicator implements HealthIndicator {

	private final CircuitBreakerRegistry circuitBreakerRegistry;

	public WebhookDeliveryHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
		this.circuitBreakerRegistry = circuitBreakerRegistry;
	}

	@Override
	public Health health() {
		CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("webhook");

		if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
			return Health.status("DEGRADED")
					.withDetail("circuitBreaker", "webhook")
					.withDetail("state", circuitBreaker.getState().name())
					.build();
		}

		return Health.up().build();
	}

}
