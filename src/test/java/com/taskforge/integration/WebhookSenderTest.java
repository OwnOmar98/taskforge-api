package com.taskforge.integration;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.audit.AuditLogRepository;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookSenderTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(WireMockConfiguration.wireMockConfig().dynamicPort())
			.build();

	@Autowired
	private WebhookSender webhookSender;

	@Autowired
	private WebhookRepository webhookRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	private WebhookDeliveryHealthIndicator webhookDeliveryHealthIndicator;

	@Autowired
	private MeterRegistry meterRegistry;

	private Webhook webhook;

	// Each test gets a fresh, randomly-generated organization, which - since
	// WebhookSender names its circuit breaker "webhook-{organizationId}" -
	// means a brand new, never-before-used breaker name every time. There's
	// no shared "webhook" instance left to reset between tests anymore.
	@BeforeEach
	void setUpWebhook() {
		// wireMock is static (one server for the whole class) - without this,
		// stubs and scenario state from an earlier test method would still be
		// registered when the next one runs.
		wireMock.resetAll();

		Organization organization = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		webhook = webhookRepository
				.saveAndFlush(new Webhook(organization.getId(), wireMock.baseUrl() + "/hook", "test-secret"));
	}

	private CircuitBreaker circuitBreakerFor(Webhook target) {
		return circuitBreakerRegistry.circuitBreaker("webhook-" + target.getOrganizationId());
	}

	@Test
	void aTransientFailureIsRecoveredByRetry() throws ExecutionException, InterruptedException {
		wireMock.stubFor(post(urlEqualTo("/hook")).inScenario("retry")
				.whenScenarioStateIs("Started")
				.willReturn(aResponse().withStatus(500))
				.willSetStateTo("second attempt"));
		wireMock.stubFor(post(urlEqualTo("/hook")).inScenario("retry")
				.whenScenarioStateIs("second attempt")
				.willReturn(aResponse().withStatus(200)));

		webhookSender.send(webhook, "{}", UUID.randomUUID().toString()).get();

		wireMock.verify(2, postRequestedFor(urlEqualTo("/hook")));
		assertEquals("UP", webhookDeliveryHealthIndicator.health().getStatus().getCode());
	}

	@Test
	void aResponseSlowerThanTheTimeLimitIsTreatedAsAFailure() throws ExecutionException, InterruptedException {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200).withFixedDelay(5000)));

		double countBefore = meterRegistry.counter("webhook.delivery.failures").count();

		webhookSender.send(webhook, "{}", UUID.randomUUID().toString()).get();

		assertTrue(auditLogRepository.findAll().stream()
				.anyMatch(entry -> "WEBHOOK_DELIVERY_FAILED".equals(entry.getAction())
						&& entry.getEntityId().equals(webhook.getId())));
		assertEquals(countBefore + 1, meterRegistry.counter("webhook.delivery.failures").count());
	}

	@Test
	void aConsistentlyFailingEndpointTripsTheCircuitBreakerAndStopsRetrying()
			throws ExecutionException, InterruptedException {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));

		// minimum-number-of-calls/sliding-window-size are both 2 in the test
		// profile, so this single delivery's own retries are enough to trip
		// the breaker mid-flight: attempt 1 and attempt 2 both fail and get
		// recorded, opening it before a 3rd attempt would otherwise fire -
		// that 3rd attempt is short-circuited instead of reaching WireMock.
		webhookSender.send(webhook, "{}", UUID.randomUUID().toString()).get();

		wireMock.verify(2, postRequestedFor(urlEqualTo("/hook")));
		assertEquals(CircuitBreaker.State.OPEN, circuitBreakerFor(webhook).getState());
		assertEquals("DEGRADED", webhookDeliveryHealthIndicator.health().getStatus().getCode(),
				"an open circuit breaker should surface as a degraded (not down) health signal");

		int requestCountBefore = wireMock.findAll(postRequestedFor(urlEqualTo("/hook"))).size();
		webhookSender.send(webhook, "{}", UUID.randomUUID().toString()).get();
		int requestCountAfter = wireMock.findAll(postRequestedFor(urlEqualTo("/hook"))).size();

		assertEquals(requestCountBefore, requestCountAfter,
				"a call while the breaker is open should never reach the real endpoint at all");
	}

	// The actual bug this fix addresses: a single shared "webhook" circuit
	// breaker meant one organization's failing endpoint could trip delivery
	// for every other organization too. Two organizations, two endpoints on
	// the same WireMock server, one consistently failing and one healthy -
	// proves the failing org's breaker opening has no effect on the other.
	@Test
	void oneOrganizationsTrippedBreakerDoesNotAffectAnothers() throws ExecutionException, InterruptedException {
		Organization healthyOrg = organizationRepository
				.saveAndFlush(new Organization("Acme Healthy", "acme-healthy-" + UUID.randomUUID()));
		Webhook healthyWebhook = webhookRepository
				.saveAndFlush(new Webhook(healthyOrg.getId(), wireMock.baseUrl() + "/healthy-hook", "test-secret"));

		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
		wireMock.stubFor(post(urlEqualTo("/healthy-hook")).willReturn(aResponse().withStatus(200)));

		// Same reasoning as aConsistentlyFailingEndpointTripsTheCircuitBreaker
		// AndStopsRetrying: one delivery's own retries are enough to trip this
		// org's breaker in the test profile.
		webhookSender.send(webhook, "{}", UUID.randomUUID().toString()).get();
		assertEquals(CircuitBreaker.State.OPEN, circuitBreakerFor(webhook).getState());

		webhookSender.send(healthyWebhook, "{}", UUID.randomUUID().toString()).get();

		assertEquals(CircuitBreaker.State.CLOSED, circuitBreakerFor(healthyWebhook).getState(),
				"a different organization's breaker must be unaffected by this one tripping");
		wireMock.verify(1, postRequestedFor(urlEqualTo("/healthy-hook")));
		assertTrue(auditLogRepository.findAll().stream()
				.noneMatch(entry -> "WEBHOOK_DELIVERY_FAILED".equals(entry.getAction())
						&& entry.getEntityId().equals(healthyWebhook.getId())),
				"the healthy organization's delivery must not be recorded as failed");
	}

}
