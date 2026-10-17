package com.taskforge.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.integration.Webhook;
import com.taskforge.integration.WebhookRepository;
import com.taskforge.notification.NotificationRepository;
import com.taskforge.organization.Organization;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.Task;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.events.TaskAssignedEvent;
import com.taskforge.user.User;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = { "app.events.transport=rabbitmq", "management.health.rabbit.enabled=true" })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitEventTransportTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Container
	@ServiceConnection
	static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4-management");

	@Autowired
	private EventOutbox outbox;

	@Autowired
	private OutboxEventRelay relay;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private WebhookRepository webhookRepository;

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

	private TransactionTemplate tx;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
	}

	@Test
	void aTaskEventReachesEveryConsumerThroughTheBroker() {
		Task task = assignedTask();
		User assignee = task.getAssignee();
		UUID organizationId = task.getProject().getOrganization().getId();
		Webhook webhook = webhookRepository.saveAndFlush(new Webhook(organizationId, "https://example.test/hook", "s"));

		User actor = testData.user();

		tx.executeWithoutResult(status -> eventPublisher.publishEvent(new TaskStatusChangedEvent(organizationId,
				actor.getId(), task.getId(), TaskStatus.IN_PROGRESS, TaskStatus.DONE)));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertEquals("PUBLISHED", eventStatus(task));
			// The notifications queue's consumer...
			assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
			// ...and the webhooks queue's consumer each got their own copy.
			assertEquals(1, jdbcTemplate.queryForObject(
					"select count(*) from webhook_deliveries where webhook_id = ?", Integer.class, webhook.getId()));
		});
	}

	@Test
	void aMessageDeliveredTwiceIsHandledOnce() {
		Task task = assignedTask();
		User assignee = task.getAssignee();
		tx.executeWithoutResult(status -> eventPublisher
				.publishEvent(new TaskAssignedEvent(task.getId(), assignee.getId(), UUID.randomUUID())));
		await().atMost(Duration.ofSeconds(10))
				.untilAsserted(() -> assertEquals(1, notificationRepository.countByUserId(assignee.getId())));

		// What a broker redelivery, or a republish after an expired lease,
		// looks like to the consumer: the very same event again.
		UUID eventId = jdbcTemplate.queryForObject("select id from outbox_events where payload ->> 'taskId' = ?",
				UUID.class, task.getId().toString());
		jdbcTemplate.update("update outbox_events set status = 'PENDING', next_attempt_at = now() where id = ?",
				eventId);
		assertEquals(1, relay.relayOnce());
		assertEquals("PUBLISHED", eventStatus(task));

		await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertEquals(1, notificationRepository.countByUserId(assignee.getId())));
	}

	@Test
	void anUnreadableMessageIsDeadLettered() {
		Message garbage = MessageBuilder.withBody("not json".getBytes(StandardCharsets.UTF_8))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.build();
		rabbitTemplate.send(RabbitTopology.EXCHANGE, DomainEventTypes.TASK_ASSIGNED, garbage);

		Message deadLettered = rabbitTemplate.receive(RabbitTopology.NOTIFICATIONS_QUEUE + ".dlq", 10_000);
		assertNotNull(deadLettered, "the rejected message should be in the notifications dead-letter queue");
		assertEquals("not json", new String(deadLettered.getBody(), StandardCharsets.UTF_8));
	}

	@Test
	void anEventNoQueueIsBoundForStaysInTheOutboxForRetry() {
		UUID eventId = tx.execute(status -> outbox.append("task.unknown", Map.of("n", 1)));

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertEquals("PENDING",
					jdbcTemplate.queryForObject("select status from outbox_events where id = ?", String.class, eventId));
			assertTrue(jdbcTemplate.queryForObject("select last_error from outbox_events where id = ?", String.class,
					eventId).contains("No queue is bound"));
		});
	}

	private Task assignedTask() {
		Organization organization = testData.organization();
		Project project = testData.project(organization);
		User assignee = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		Task task = new Task(project, "Ship it", null, TaskPriority.MEDIUM, null);
		task.assignTo(assignee);
		return taskRepository.saveAndFlush(task);
	}

	private String eventStatus(Task task) {
		return jdbcTemplate.queryForObject(
				"select status from outbox_events where payload ->> 'taskId' = ? order by occurred_at limit 1",
				String.class, task.getId().toString());
	}

}
