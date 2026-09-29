package com.taskforge.realtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.TaskforgeApiApplication;
import com.taskforge.notification.Notification;
import com.taskforge.notification.NotificationRepository;
import com.taskforge.notification.NotificationType;
import com.taskforge.notification.OverdueTaskDigestJob;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.security.JwtProperties;
import com.taskforge.security.JwtService;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.Task;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.user.User;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A real server on a real port, not MockMvc: the behavior under test is a
// long-lived HTTP response being written to over time - and, for the
// cross-instance case, a second running app delivering what the first one
// published.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.realtime.max-connections-per-user=2", "app.realtime.replay-limit=3" })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UserEventStreamTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	private static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);

	@LocalServerPort
	private int port;

	@Autowired
	private TestDataFactory testData;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private UserEventStreamRegistry registry;

	@Autowired
	private OverdueTaskDigestJob overdueTaskDigestJob;

	private final HttpClient httpClient = HttpClient.newHttpClient();
	private final List<SseClient> openStreams = new ArrayList<>();

	@AfterEach
	void closeStreams() {
		openStreams.forEach(SseClient::close);
	}

	@Test
	void assigningATaskPushesTheNotificationToTheAssigneesStreamOnly() throws Exception {
		Scenario scenario = scenario();
		User bystander = testData.memberOfProject(scenario.project(), ProjectMemberRole.CONTRIBUTOR);
		SseClient assigneeStream = connect(port, token(scenario.assignee()));
		SseClient bystanderStream = connect(port, token(bystander));

		UUID taskId = createTaskAssignedTo(port, scenario);

		SseEvent event = assigneeStream.nextEvent("notification");
		JsonNode notification = objectMapper.readTree(event.data());
		assertEquals(NotificationType.TASK_ASSIGNED.name(), notification.get("type").asString());
		assertEquals(taskId.toString(), notification.get("payload").get("taskId").asString());
		// The event id is the notification's own id - what Last-Event-ID
		// replay anchors on after a reconnect.
		assertEquals(notification.get("id").asString(), event.id());

		assertNull(bystanderStream.pollEvent("notification", Duration.ofMillis(500)));
	}

	// The reason Redis pub/sub is here at all: the notification is created on
	// instance A (where the request landed), but the assignee's stream is open
	// on instance B.
	@Test
	void aNotificationCreatedOnOneInstanceReachesAStreamOpenOnAnother() throws Exception {
		Scenario scenario = scenario();
		try (ConfigurableApplicationContext instanceB = startSecondInstance()) {
			SseClient streamOnB = connect(portOf(instanceB), token(scenario.assignee()));

			createTaskAssignedTo(port, scenario);

			JsonNode notification = objectMapper.readTree(streamOnB.nextEvent("notification").data());
			assertEquals(NotificationType.TASK_ASSIGNED.name(), notification.get("type").asString());
		}
	}

	// A signal event on the same stream: named by its type, and deliberately
	// without an id, so it never moves the client's Last-Event-ID.
	@Test
	void editingATaskSignalsItsAssigneeButNotTheEditor() throws Exception {
		Scenario scenario = scenario();
		UUID taskId = createTaskAssignedTo(port, scenario);
		SseClient assigneeStream = connect(port, token(scenario.assignee()));
		SseClient editorStream = connect(port, token(scenario.lead()));

		updateTask(scenario, taskId, new UpdateTaskRequest("Renamed", null, null, null, null, null, 0L));

		SseEvent event = assigneeStream.nextEvent("task.updated");
		assertNull(event.id());
		JsonNode data = objectMapper.readTree(event.data());
		assertEquals(taskId.toString(), data.get("taskId").asString());
		assertEquals(scenario.project().getId().toString(), data.get("projectId").asString());
		assertEquals(1, data.get("version").asInt());
		assertNull(editorStream.pollEvent("task.updated", Duration.ofMillis(500)));
	}

	// The previous assignee's task list just lost this task - they need to
	// refetch too, not only the new assignee.
	@Test
	void reassigningATaskAlsoSignalsThePreviousAssignee() throws Exception {
		Scenario scenario = scenario();
		User newAssignee = testData.memberOfProject(scenario.project(), ProjectMemberRole.CONTRIBUTOR);
		UUID taskId = createTaskAssignedTo(port, scenario);
		SseClient previousAssigneeStream = connect(port, token(scenario.assignee()));

		updateTask(scenario, taskId, new UpdateTaskRequest(null, null, null, null, null, newAssignee.getId(), 0L));

		JsonNode data = objectMapper.readTree(previousAssigneeStream.nextEvent("task.updated").data());
		assertEquals(taskId.toString(), data.get("taskId").asString());
	}

	@Test
	void deletingATaskSignalsItsAssignee() throws Exception {
		Scenario scenario = scenario();
		UUID taskId = createTaskAssignedTo(port, scenario);
		SseClient assigneeStream = connect(port, token(scenario.assignee()));

		HttpRequest request = HttpRequest
				.newBuilder(URI.create(
						baseUrl(port) + "/api/v1/projects/" + scenario.project().getId() + "/tasks/" + taskId))
				.header("Authorization", "Bearer " + token(scenario.lead()))
				.DELETE()
				.build();
		assertEquals(204, httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());

		SseEvent event = assigneeStream.nextEvent("task.deleted");
		assertNull(event.id());
		assertEquals(taskId.toString(), objectMapper.readTree(event.data()).get("taskId").asString());
	}

	@Test
	void anUpdateThatChangesNothingSignalsNobody() throws Exception {
		Scenario scenario = scenario();
		UUID taskId = createTaskAssignedTo(port, scenario);
		SseClient assigneeStream = connect(port, token(scenario.assignee()));

		updateTask(scenario, taskId, new UpdateTaskRequest("Ship it", null, null, null, null, null, 0L));

		assertNull(assigneeStream.pollEvent("task.updated", Duration.ofSeconds(1)));
	}

	// A rerun for the same day inserts nothing (ON CONFLICT DO NOTHING), so it
	// must push nothing either - only rows a run actually inserted go out.
	@Test
	void theOverdueDigestIsPushedOnceEvenWhenTheJobRunsTwice() throws Exception {
		Scenario scenario = scenario();
		LocalDate today = LocalDate.now();
		Task overdue = new Task(scenario.project(), "Late", null, TaskPriority.HIGH, today.minusDays(1));
		overdue.assignTo(scenario.assignee());
		taskRepository.saveAndFlush(overdue);
		SseClient stream = connect(port, token(scenario.assignee()));

		overdueTaskDigestJob.runForDate(today);
		overdueTaskDigestJob.runForDate(today);

		JsonNode digest = objectMapper.readTree(stream.nextEvent("notification").data());
		assertEquals(NotificationType.OVERDUE_TASK_DIGEST.name(), digest.get("type").asString());
		assertEquals(1, digest.get("payload").get("taskCount").asInt());
		assertNull(stream.pollEvent("notification", Duration.ofSeconds(1)));
	}

	@Test
	void reconnectingWithLastEventIdReplaysWhatWasMissedInOrder() throws Exception {
		User user = testData.user();
		Organization organization = testData.organization();
		Notification seen = saveNotification(user, organization);
		Notification missed1 = saveNotification(user, organization);
		Notification missed2 = saveNotification(user, organization);

		SseClient stream = connect(port, token(user), seen.getId().toString());

		assertEquals(missed1.getId().toString(), stream.nextEvent("notification").id());
		assertEquals(missed2.getId().toString(), stream.nextEvent("notification").id());
	}

	@Test
	void aBacklogLargerThanTheReplayLimitAsksTheClientToResyncInstead() throws Exception {
		User user = testData.user();
		Organization organization = testData.organization();
		Notification seen = saveNotification(user, organization);
		for (int i = 0; i < 4; i++) {
			saveNotification(user, organization);
		}

		SseClient stream = connect(port, token(user), seen.getId().toString());

		assertNotNull(stream.nextEvent("resync"));
		assertNull(stream.pollEvent("notification", Duration.ofMillis(500)));
	}

	@Test
	void aMalformedLastEventIdIsRejected() throws Exception {
		HttpResponse<Stream<String>> response = openRaw(port, token(testData.user()), "not-a-uuid");

		assertEquals(400, response.statusCode());
	}

	@Test
	void requiresAnAccessToken() throws Exception {
		HttpResponse<Stream<String>> response = openRaw(port, null, null);

		assertEquals(401, response.statusCode());
	}

	@Test
	void opensBeyondThePerUserLimitAreRejectedWithRetryAfter() throws Exception {
		String token = token(testData.user());
		connect(port, token);
		connect(port, token);

		HttpResponse<Stream<String>> response = openRaw(port, token, null);

		assertEquals(429, response.statusCode());
		assertTrue(response.headers().firstValue("Retry-After").isPresent());
	}

	// The stream must not outlive the token that opened it - it ends on its
	// own at the token's exp, with no request or client action involved.
	// Also the regression guard for the ASYNC dispatcher rule in
	// SecurityConfig: without it the stream still ends, but the timeout's
	// async re-dispatch is denied and the container logs a ServletException
	// ("response is already committed") - so assert on the log, not just the
	// client-visible outcome.
	@Test
	void theStreamEndsWhenTheAccessTokenThatOpenedItExpiresWithoutErrors() throws Exception {
		User user = testData.user();
		JwtService shortLived = new JwtService(new JwtProperties(jwtProperties.secret(), Duration.ofSeconds(2)));
		Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		rootLogger.addAppender(appender);

		try {
			SseClient stream = connect(port, shortLived.generateAccessToken(user.getId()));

			assertTrue(stream.awaitEnd(Duration.ofSeconds(8)), "stream should end once its token expires");
			// The re-dispatch runs just after the client sees the stream end.
			Thread.sleep(500);
			assertEquals(List.of(), appender.list.stream()
					.filter(event -> event.getLevel() == Level.ERROR)
					.map(ILoggingEvent::getFormattedMessage)
					.toList());
		}
		finally {
			rootLogger.detachAppender(appender);
		}
	}

	// The normal way a stream ends: the client just goes away (tab closed,
	// network dropped). The server only finds out when its next write fails -
	// that failure must clean up quietly, not surface as a pile of container,
	// security, and exception-handler errors on a response that's already gone.
	@Test
	void writingToAClientThatDisconnectedCleansUpWithoutErrors() throws Exception {
		User user = testData.user();
		Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		rootLogger.addAppender(appender);

		try {
			SseClient stream = connect(port, token(user));
			stream.close();

			// The first write after a disconnect can still land in the socket
			// buffer; it takes a few before the broken pipe actually surfaces.
			for (int i = 0; i < 5; i++) {
				Thread.sleep(200);
				registry.heartbeat();
			}
			Thread.sleep(500);

			assertEquals(List.of(), appender.list.stream()
					.filter(event -> event.getLevel() == Level.ERROR)
					.map(ILoggingEvent::getFormattedMessage)
					.toList());
		}
		finally {
			rootLogger.detachAppender(appender);
		}
	}

	// Graceful shutdown waits for in-flight requests, and an open stream is
	// one that never finishes on its own - without the registry completing
	// streams first, shutdown would sit out its whole 20s timeout.
	@Test
	void shuttingDownAnInstanceEndsItsStreamsPromptly() throws Exception {
		User user = testData.user();
		ConfigurableApplicationContext instance = startSecondInstance();
		SseClient stream = connect(portOf(instance), token(user));

		long started = System.nanoTime();
		CompletableFuture<Void> shutdown = CompletableFuture.runAsync(instance::close);

		assertTrue(stream.awaitEnd(Duration.ofSeconds(5)), "stream should end as soon as shutdown begins");
		shutdown.get(30, TimeUnit.SECONDS);
		assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 10,
				"shutdown shouldn't wait out the graceful-shutdown timeout on open streams");
	}

	private Scenario scenario() {
		Organization organization = testData.organization();
		Project project = testData.project(organization);
		User lead = testData.memberOf(organization, MembershipRole.MEMBER);
		testData.projectMember(project, lead, ProjectMemberRole.LEAD);
		User assignee = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		return new Scenario(project, lead, assignee);
	}

	private UUID createTaskAssignedTo(int targetPort, Scenario scenario) throws Exception {
		String body = objectMapper.writeValueAsString(
				new CreateTaskRequest("Ship it", null, TaskPriority.MEDIUM, null, scenario.assignee().getId()));
		HttpRequest request = HttpRequest
				.newBuilder(URI.create(baseUrl(targetPort) + "/api/v1/projects/" + scenario.project().getId() + "/tasks"))
				.header("Authorization", "Bearer " + token(scenario.lead()))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(201, response.statusCode(), response.body());
		return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
	}

	private void updateTask(Scenario scenario, UUID taskId, UpdateTaskRequest update) throws Exception {
		HttpRequest request = HttpRequest
				.newBuilder(URI.create(
						baseUrl(port) + "/api/v1/projects/" + scenario.project().getId() + "/tasks/" + taskId))
				.header("Authorization", "Bearer " + token(scenario.lead()))
				.header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(update)))
				.build();
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
	}

	private Notification saveNotification(User user, Organization organization) {
		return notificationRepository.saveAndFlush(
				new Notification(user.getId(), organization.getId(), NotificationType.TASK_ASSIGNED, "{}"));
	}

	private String token(User user) {
		return jwtService.generateAccessToken(user.getId());
	}

	private SseClient connect(int targetPort, String token) throws Exception {
		return connect(targetPort, token, null);
	}

	private SseClient connect(int targetPort, String token, String lastEventId) throws Exception {
		HttpResponse<Stream<String>> response = openRaw(targetPort, token, lastEventId);
		assertEquals(200, response.statusCode());
		SseClient client = new SseClient(response);
		openStreams.add(client);
		return client;
	}

	// Returns once response headers arrive - the server commits them
	// immediately with an initial comment, so this doesn't wait for a real event.
	private HttpResponse<Stream<String>> openRaw(int targetPort, String token, String lastEventId) throws Exception {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create(baseUrl(targetPort) + "/api/v1/events/stream"))
				.header("Accept", "text/event-stream");
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		if (lastEventId != null) {
			request.header("Last-Event-ID", lastEventId);
		}
		return httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofLines());
	}

	// A genuinely separate app instance sharing only Postgres and Redis with
	// the test's own - not a second Spring test context reusing anything
	// in-process beyond the JVM.
	private ConfigurableApplicationContext startSecondInstance() {
		return new SpringApplicationBuilder(TaskforgeApiApplication.class).profiles("test")
				.properties("server.port=0", "spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword(),
						"spring.data.redis.host=" + redis.getRedisHost(),
						"spring.data.redis.port=" + redis.getRedisPort())
				.run();
	}

	private static int portOf(ConfigurableApplicationContext context) {
		return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
	}

	private static String baseUrl(int targetPort) {
		return "http://localhost:" + targetPort;
	}

	private record Scenario(Project project, User lead, User assignee) {
	}

	private record SseEvent(String name, String id, String data) {
	}

	// Minimal text/event-stream parser: accumulates field lines until the
	// blank line that terminates each event. Comment lines (":...") are the
	// server's connect/heartbeat keepalives and carry nothing to assert on.
	private static final class SseClient {

		private final HttpResponse<Stream<String>> response;
		private final BlockingQueue<SseEvent> events = new LinkedBlockingQueue<>();
		private final CompletableFuture<Void> ended = new CompletableFuture<>();

		SseClient(HttpResponse<Stream<String>> response) {
			this.response = response;
			Thread reader = new Thread(this::read, "sse-test-reader");
			reader.setDaemon(true);
			reader.start();
		}

		private void read() {
			String name = null;
			String id = null;
			StringBuilder data = new StringBuilder();
			try {
				for (String line : (Iterable<String>) response.body()::iterator) {
					if (line.isEmpty()) {
						if (name != null || !data.isEmpty()) {
							events.add(new SseEvent(name, id, data.toString()));
						}
						name = null;
						id = null;
						data.setLength(0);
					}
					else if (line.startsWith("event:")) {
						name = line.substring(6).strip();
					}
					else if (line.startsWith("id:")) {
						id = line.substring(3).strip();
					}
					else if (line.startsWith("data:")) {
						data.append(line.substring(5).strip());
					}
				}
			}
			catch (RuntimeException e) {
				// The connection was closed underneath the reader - it has ended
				// either way.
			}
			finally {
				ended.complete(null);
			}
		}

		SseEvent nextEvent(String name) throws InterruptedException {
			SseEvent event = pollEvent(name, EVENT_TIMEOUT);
			assertNotNull(event, "expected a '" + name + "' event within " + EVENT_TIMEOUT);
			return event;
		}

		SseEvent pollEvent(String name, Duration timeout) throws InterruptedException {
			long deadline = System.nanoTime() + timeout.toNanos();
			while (true) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					return null;
				}
				SseEvent event = events.poll(remaining, TimeUnit.NANOSECONDS);
				if (event != null && name.equals(event.name())) {
					return event;
				}
			}
		}

		boolean awaitEnd(Duration timeout) {
			try {
				ended.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				return true;
			}
			catch (Exception e) {
				return false;
			}
		}

		void close() {
			response.body().close();
		}

	}

}
