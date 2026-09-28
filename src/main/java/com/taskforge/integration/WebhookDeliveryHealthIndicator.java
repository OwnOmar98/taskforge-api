package com.taskforge.integration;

import java.util.List;

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
//
// WebhookSender uses one circuit breaker per organization ("webhook-{orgId}",
// created lazily on that org's first delivery), not one shared "webhook"
// instance - checking every instance whose name has that prefix is what
// makes this indicator still mean something after that change: a single
// hardcoded lookup would either never find anything, or find a breaker some
// unrelated code path happened to create under the same name.
@Component
public class WebhookDeliveryHealthIndicator implements HealthIndicator {

	private static final String INSTANCE_NAME_PREFIX = "webhook-";

	private final CircuitBreakerRegistry circuitBreakerRegistry;

	public WebhookDeliveryHealthIndicator(CircuitBreakerRegistry circuitBreakerRegistry) {
		this.circuitBreakerRegistry = circuitBreakerRegistry;
	}

	@Override
	public Health health() {
		List<String> openFor = circuitBreakerRegistry.getAllCircuitBreakers().stream()
				.filter(circuitBreaker -> circuitBreaker.getName().startsWith(INSTANCE_NAME_PREFIX))
				.filter(circuitBreaker -> circuitBreaker.getState() == CircuitBreaker.State.OPEN)
				.map(CircuitBreaker::getName)
				.toList();

		if (!openFor.isEmpty()) {
			return Health.status("DEGRADED")
					.withDetail("openCircuitBreakers", openFor)
					.build();
		}

		return Health.up().build();
	}

}
