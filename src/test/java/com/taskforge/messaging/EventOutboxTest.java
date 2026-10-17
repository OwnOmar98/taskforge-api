package com.taskforge.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.notification.NotificationEventHandler;
import com.taskforge.notification.NotificationRepository;
import com.taskforge.organization.Organization;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.Task;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.events.TaskAssignedEvent;
import com.taskforge.user.User;

import tools.jackson.databind.ObjectMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs on the in-process transport (the test profile's default): the outbox,
// relay and handlers are the same ones the RabbitMQ transport uses, minus the
// broker hop, which RabbitEventTransportTest covers.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EventOutboxTest {

	private static final String FLAKY = "test.flaky";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private EventOutbox outbox;

	@Autowired
	private OutboxEventRelay relay;

	@Autowired
	private NotificationEventHandler notificationEventHandler;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private TestDataFactory testData;

	@Autowired
	private ApplicationEventPublisher eventPublisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FlakyHandler flakyHandler;

	private TransactionTemplate tx;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		flakyHandler.failuresLeft.set(0);
		flakyHandler.calls.set(0);
	}

	@Test
	void anEventIsWrittenWithTheChangeRolledBackWithItAndHandledAfterCommit() {
		Task task = assignedTask();
		User assignee = task.getAssignee();

		tx.executeWithoutResult(status -> {
			eventPublisher.publishEvent(new TaskAssignedEvent(task.getId(), assignee.getId(), UUID.randomUUID()));
			status.setRollbackOnly();
		});
		assertEquals(0, eventCount(task), "a rolled-back change must leave no event behind");

		tx.executeWithoutResult(
				status -> eventPublisher.publishEvent(new TaskAssignedEvent(task.getId(), assignee.getId(), UUID.randomUUID())));
		assertEquals(1, eventCount(task));

		// No poll in the test profile: only the post-commit nudge can have
		// published it.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals("PUBLISHED", eventStatus(task));
			assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
		});
	}

	@Test
	void aFailedPublishIsRetriedWithBackoffAndNotLost() {
		flakyHandler.failuresLeft.set(1);
		UUID eventId = tx.execute(status -> outbox.append(FLAKY, Map.of("n", 1)));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals("PENDING", jdbcTemplate.queryForObject("select status from outbox_events where id = ?",
					String.class, eventId));
			assertTrue(jdbcTemplate.queryForObject("select last_error from outbox_events where id = ?", String.class,
					eventId).contains("simulated handler failure"));
		});
		assertEquals(0, relay.relayOnce(), "nothing is due before the backoff elapses");

		jdbcTemplate.update("update outbox_events set next_attempt_at = now() where id = ?", eventId);
		assertEquals(1, relay.relayOnce());

		assertEquals("PUBLISHED",
				jdbcTemplate.queryForObject("select status from outbox_events where id = ?", String.class, eventId));
		assertEquals(2, flakyHandler.calls.get());
	}

	@Test
	void aConsumerHandlesTheSameEventOnlyOnce() {
		Task task = assignedTask();
		User assignee = task.getAssignee();
		TaskEventPayload payload = new TaskEventPayload(task.getId(), task.getProject().getOrganization().getId(),
				UUID.randomUUID(), assignee.getId(), null, null, null);
		DomainEvent event = new DomainEvent(UUID.randomUUID(), DomainEventTypes.TASK_ASSIGNED, Instant.now(),
				objectMapper.valueToTree(payload));

		notificationEventHandler.handle(event);
		notificationEventHandler.handle(event);

		assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
	}

	private Task assignedTask() {
		Organization organization = testData.organization();
		Project project = testData.project(organization);
		User assignee = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		Task task = new Task(project, "Ship it", null, TaskPriority.MEDIUM, null);
		task.assignTo(assignee);
		return taskRepository.saveAndFlush(task);
	}

	private int eventCount(Task task) {
		return jdbcTemplate.queryForObject("select count(*) from outbox_events where payload ->> 'taskId' = ?",
				Integer.class, task.getId().toString());
	}

	private String eventStatus(Task task) {
		return jdbcTemplate.queryForObject("select status from outbox_events where payload ->> 'taskId' = ?",
				String.class, task.getId().toString());
	}

	@TestConfiguration
	static class FlakyHandlerConfig {

		@Bean
		FlakyHandler flakyHandler() {
			return new FlakyHandler();
		}

	}

	static class FlakyHandler implements DomainEventHandler {

		final AtomicInteger failuresLeft = new AtomicInteger();
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public String consumerName() {
			return "flaky";
		}

		@Override
		public Set<String> eventTypes() {
			return Set.of(FLAKY);
		}

		@Override
		public void handle(DomainEvent event) {
			calls.incrementAndGet();
			if (failuresLeft.getAndDecrement() > 0) {
				throw new IllegalStateException("simulated handler failure");
			}
		}

	}

}
