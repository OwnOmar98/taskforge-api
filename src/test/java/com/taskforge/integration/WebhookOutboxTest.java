package com.taskforge.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.audit.AuditLogRepository;
import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.integration.WebhookOutbox.ClaimedDelivery;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.security.JwtService;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.TaskStatus;
import com.taskforge.user.User;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

// The scheduled poll is off (test profile default); relay cycles are driven
// through relayOnce()/claimDue() so nothing races the assertions.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookOutboxTest {

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
	private WebhookOutbox outbox;

	@Autowired
	private WebhookOutboxRelay relay;

	@Autowired
	private WebhookRepository webhookRepository;

	@Autowired
	private WebhookDeliveryRepository deliveryRepository;

	@Autowired
	private AuditLogRepository auditLogRepository;

	@Autowired
	private TestDataFactory testData;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private ObjectMapper objectMapper;

	private Organization organization;
	private User owner;
	private Webhook webhook;
	private TransactionTemplate tx;

	// A fresh organization per test means a fresh per-organization circuit
	// breaker too, so no breaker state leaks between tests.
	@BeforeEach
	void setUp() {
		wireMock.resetAll();
		organization = testData.organization();
		owner = testData.memberOf(organization, MembershipRole.OWNER);
		webhook = webhookRepository
				.saveAndFlush(new Webhook(organization.getId(), wireMock.baseUrl() + "/hook", "test-secret"));
		tx = new TransactionTemplate(transactionManager);
		// Other tests' leftover rows must not be claimed by this test's relay cycles.
		jdbcTemplate.update("update webhook_deliveries set status = 'DELIVERED' where status <> 'DELIVERED'");
	}

	@Test
	void theOutboxRowCommitsWithTheChangeAndRollsBackWithIt() {
		tx.executeWithoutResult(status -> {
			publishTaskCompleted();
			status.setRollbackOnly();
		});
		assertEquals(0, deliveryCount(), "a rolled-back change must leave no delivery behind");

		tx.executeWithoutResult(status -> publishTaskCompleted());
		assertEquals(1, deliveryCount());
		assertEquals(WebhookDeliveryStatus.PENDING, onlyDelivery().getStatus());
	}

	@Test
	void aSuccessfulSendMarksTheDeliveryDeliveredAndCarriesItsIdempotencyKey() {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
		WebhookDelivery delivery = enqueue();

		assertEquals(1, relay.relayOnce());

		WebhookDelivery after = reload(delivery);
		assertEquals(WebhookDeliveryStatus.DELIVERED, after.getStatus());
		assertNotNull(after.getDeliveredAt());
		assertEquals(delivery.getIdempotencyKey().toString(), onlyRequest().getHeader("X-Idempotency-Key"));
	}

	// Two outbox attempts, three HTTP requests (the first attempt's in-process
	// retry included) - and every one of them carries the same key, so the
	// receiver can tell they're one logical delivery.
	@Test
	void aFailedAttemptIsRescheduledWithBackoffAndRetriedWithTheSameKey() {
		wireMock.stubFor(post(urlEqualTo("/hook")).inScenario("flaky").whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(500)).willSetStateTo("failed once"));
		wireMock.stubFor(post(urlEqualTo("/hook")).inScenario("flaky").whenScenarioStateIs("failed once")
				.willReturn(aResponse().withStatus(500)).willSetStateTo("recovered"));
		wireMock.stubFor(post(urlEqualTo("/hook")).inScenario("flaky").whenScenarioStateIs("recovered")
				.willReturn(aResponse().withStatus(200)));
		WebhookDelivery delivery = enqueue();

		Instant beforeFirstAttempt = Instant.now();
		relay.relayOnce();

		WebhookDelivery afterFailure = reload(delivery);
		assertEquals(WebhookDeliveryStatus.PENDING, afterFailure.getStatus());
		assertEquals(1, afterFailure.getAttempts());
		assertNotNull(afterFailure.getLastError());
		assertTrue(afterFailure.getNextAttemptAt().isAfter(beforeFirstAttempt.plusMillis(900)),
				"the retry must be scheduled one base-backoff (1s in tests) later, not immediately");
		assertEquals(0, relay.relayOnce(), "nothing is due before the backoff elapses");

		makeDueAgain(delivery);
		relay.relayOnce();

		WebhookDelivery delivered = reload(delivery);
		assertEquals(WebhookDeliveryStatus.DELIVERED, delivered.getStatus());
		assertEquals(2, delivered.getAttempts());
		Set<String> keys = new HashSet<>();
		wireMock.findAll(postRequestedFor(urlEqualTo("/hook"))).forEach(r -> keys.add(r.getHeader("X-Idempotency-Key")));
		assertEquals(Set.of(delivery.getIdempotencyKey().toString()), keys);
	}

	@Test
	void exhaustingTheAttemptBudgetMarksTheDeliveryFailedAndAuditsIt() {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
		WebhookDelivery delivery = enqueue();
		double deadBefore = meterRegistry.counter("webhook.delivery.dead").count();

		for (int attempt = 1; attempt <= 3; attempt++) {   // max-attempts is 3 in the test profile
			makeDueAgain(delivery);
			relay.relayOnce();
		}

		WebhookDelivery dead = reload(delivery);
		assertEquals(WebhookDeliveryStatus.FAILED, dead.getStatus());
		assertEquals(3, dead.getAttempts());
		assertTrue(auditLogRepository.findAll().stream()
				.anyMatch(entry -> "WEBHOOK_DELIVERY_FAILED".equals(entry.getAction())
						&& entry.getEntityId().equals(webhook.getId())));
		assertEquals(deadBefore + 1, meterRegistry.counter("webhook.delivery.dead").count());

		makeDueAgain(delivery);
		assertEquals(0, relay.relayOnce(), "a FAILED delivery is never picked up again on its own");
	}

	// The property that makes the relay safe on every instance at once: while
	// one relay holds its claimed rows locked (its transaction still open), a
	// second relay neither waits on those rows nor claims them - it takes the
	// others. Without SKIP LOCKED the second claim would block until the
	// first committed, and this test's 5-second bound would fail it.
	@Test
	void aConcurrentRelaySkipsLockedRowsInsteadOfWaitingOrDoubleClaiming() throws Exception {
		for (int i = 0; i < 4; i++) {
			enqueue();
		}
		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);

		CompletableFuture<List<ClaimedDelivery>> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
			List<ClaimedDelivery> claimed = outbox.claimDue(2);
			firstClaimed.countDown();
			await(releaseFirst);                          // hold the transaction (and its row locks) open
			return claimed;
		}));
		assertTrue(firstClaimed.await(5, TimeUnit.SECONDS));

		List<ClaimedDelivery> second = CompletableFuture.supplyAsync(() -> outbox.claimDue(10))
				.get(5, TimeUnit.SECONDS);
		releaseFirst.countDown();
		List<ClaimedDelivery> firstClaims = first.get(5, TimeUnit.SECONDS);

		Set<UUID> firstIds = ids(firstClaims);
		Set<UUID> secondIds = ids(second);
		assertEquals(2, firstIds.size());
		assertEquals(2, secondIds.size());
		assertTrue(firstIds.stream().noneMatch(secondIds::contains), "no delivery may be claimed twice");
	}

	@Test
	void aDeliveryWhoseLeaseExpiredIsReclaimed() {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));
		WebhookDelivery delivery = enqueue();
		// Simulates an instance that claimed the row and then died mid-send.
		jdbcTemplate.update("update webhook_deliveries set status = 'IN_FLIGHT', attempts = 1, "
				+ "claimed_until = now() - interval '1 minute' where id = ?", delivery.getId());

		assertEquals(1, relay.relayOnce());

		WebhookDelivery after = reload(delivery);
		assertEquals(WebhookDeliveryStatus.DELIVERED, after.getStatus());
		assertEquals(2, after.getAttempts());
	}

	// If an instance's lease expires mid-send and another relay reclaims the
	// row, the first instance's late result must not overwrite the newer attempt.
	@Test
	void aLateResultFromAnExpiredClaimIsIgnored() {
		WebhookDelivery delivery = enqueue();
		ClaimedDelivery staleClaim = outbox.claimDue(10).getFirst();
		jdbcTemplate.update("update webhook_deliveries set claimed_until = now() - interval '1 minute' where id = ?",
				delivery.getId());
		ClaimedDelivery currentClaim = outbox.claimDue(10).getFirst();

		outbox.recordFailure(staleClaim, new IllegalStateException("late failure from a dead instance"));
		assertEquals(WebhookDeliveryStatus.IN_FLIGHT, reload(delivery).getStatus());
		assertNull(reload(delivery).getLastError());

		outbox.recordSuccess(currentClaim);
		assertEquals(WebhookDeliveryStatus.DELIVERED, reload(delivery).getStatus());
	}

	@Test
	void backoffDoublesPerAttemptUpToTheCap() {
		// test profile: base 1s, cap 5s
		assertEquals(Duration.ofSeconds(1), outbox.backoff(1));
		assertEquals(Duration.ofSeconds(2), outbox.backoff(2));
		assertEquals(Duration.ofSeconds(4), outbox.backoff(3));
		assertEquals(Duration.ofSeconds(5), outbox.backoff(4));
		assertEquals(Duration.ofSeconds(5), outbox.backoff(30));
	}

	@Test
	void anAdminCanListFailedDeliveriesAndRedeliverOneWithTheSameKey() throws Exception {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(500)));
		WebhookDelivery delivery = enqueue();
		for (int attempt = 1; attempt <= 3; attempt++) {
			makeDueAgain(delivery);
			relay.relayOnce();
		}

		String listed = body(get(deliveriesUrl() + "?status=FAILED"), owner);
		assertEquals(delivery.getId().toString(), objectMapper.readTree(listed).get("content").get(0).get("id").asString());

		String redelivered = body(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post(deliveriesUrl() + "/" + delivery.getId() + "/redeliver"), owner);
		assertEquals("PENDING", objectMapper.readTree(redelivered).get("status").asString());
		WebhookDelivery requeued = reload(delivery);
		assertEquals(0, requeued.getAttempts());
		assertEquals(delivery.getIdempotencyKey(), requeued.getIdempotencyKey());
	}

	@Test
	void onlyAFailedDeliveryCanBeRedeliveredAndOnlyByAnAdmin() throws Exception {
		WebhookDelivery pending = enqueue();
		User member = testData.memberOf(organization, MembershipRole.MEMBER);
		String redeliverUrl = deliveriesUrl() + "/" + pending.getId() + "/redeliver";

		assertEquals(409, status(postTo(redeliverUrl), owner));
		assertEquals(403, status(postTo(redeliverUrl), member));
		assertEquals(403, status(get(deliveriesUrl()), member));
	}

	private void publishTaskCompleted() {
		eventPublisher.publishEvent(new TaskStatusChangedEvent(organization.getId(), owner.getId(), UUID.randomUUID(),
				TaskStatus.IN_PROGRESS, TaskStatus.DONE));
	}

	private WebhookDelivery enqueue() {
		return tx.execute(status -> outbox.enqueue(webhook, "task.completed", "{\"event\":\"task.completed\"}"));
	}

	// Skips the backoff wait and closes this organization's breaker, so the
	// next relay cycle makes a genuine, fresh attempt.
	private void makeDueAgain(WebhookDelivery delivery) {
		jdbcTemplate.update("update webhook_deliveries set next_attempt_at = now() where id = ?", delivery.getId());
		circuitBreakerRegistry.circuitBreaker("webhook-" + organization.getId()).reset();
	}

	private WebhookDelivery reload(WebhookDelivery delivery) {
		return deliveryRepository.findById(delivery.getId()).orElseThrow();
	}

	private WebhookDelivery onlyDelivery() {
		List<WebhookDelivery> mine = deliveryRepository.findAll().stream()
				.filter(d -> d.getWebhookId().equals(webhook.getId()))
				.toList();
		assertEquals(1, mine.size());
		return mine.getFirst();
	}

	private long deliveryCount() {
		return deliveryRepository.findAll().stream().filter(d -> d.getWebhookId().equals(webhook.getId())).count();
	}

	private LoggedRequest onlyRequest() {
		List<LoggedRequest> requests = wireMock.findAll(postRequestedFor(urlEqualTo("/hook")));
		assertEquals(1, requests.size());
		return requests.getFirst();
	}

	private static Set<UUID> ids(List<ClaimedDelivery> claims) {
		Set<UUID> ids = new HashSet<>();
		claims.forEach(c -> ids.add(c.deliveryId()));
		return ids;
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// MockMvc's post(), named apart from the WireMock post() imported statically above.
	private static MockHttpServletRequestBuilder postTo(String url) {
		return MockMvcRequestBuilders.post(url);
	}

	private String deliveriesUrl() {
		return "/api/v1/organizations/" + organization.getId() + "/webhooks/" + webhook.getId() + "/deliveries";
	}

	private String body(MockHttpServletRequestBuilder request, User actor) throws Exception {
		return mockMvc.perform(authed(request, actor)).andReturn().getResponse().getContentAsString();
	}

	private int status(MockHttpServletRequestBuilder request, User actor) throws Exception {
		return mockMvc.perform(authed(request, actor)).andReturn().getResponse().getStatus();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User actor) {
		return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(actor.getId()));
	}

}
