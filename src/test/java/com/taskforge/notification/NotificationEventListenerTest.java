package com.taskforge.notification;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMember;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectRepository;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationEventListenerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectMemberRepository projectMemberRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Test
	void assigningATaskCreatesANotificationForTheAssigneeWithoutBlockingTheRequest() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee1@acme.test", ProjectMemberRole.CONTRIBUTOR);

		mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest("Ship it", null, TaskPriority.MEDIUM, null, assignee.getId()))))
				.andExpect(status().isCreated());

		// The listener runs AFTER_COMMIT on a background thread - the row
		// genuinely doesn't exist yet the instant the response comes back,
		// proving the request didn't wait on it.
		assertEquals(0, notificationRepository.countByUserId(assignee.getId()));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
			var page = notificationRepository.findFirstPageByUserId(assignee.getId(), 10);
			assertEquals(NotificationType.TASK_ASSIGNED, page.get(0).getType());
		});
	}

	@Test
	void changingTaskStatusCreatesANotificationForTheAssignee() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee2@acme.test", ProjectMemberRole.CONTRIBUTOR);
		UUID taskId = createAssignedTask(projectId, ownerToken, "Ship it", assignee.getId());
		// The assignment itself already produces a TASK_ASSIGNED notification -
		// wait for that to settle first so the count below reflects only the
		// status change this test is actually about.
		await().atMost(Duration.ofSeconds(5))
				.until(() -> notificationRepository.countByUserId(assignee.getId()) == 1);

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest(null, null, TaskStatus.DONE, null, null, null, 0L))))
				.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals(2, notificationRepository.countByUserId(assignee.getId()));
			var page = notificationRepository.findFirstPageByUserId(assignee.getId(), 10);
			assertTrue(page.stream().anyMatch(n -> n.getType() == NotificationType.TASK_STATUS_CHANGED));
		});
	}

	@Test
	void addingACommentCreatesANotificationForTheAssignee() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee3@acme.test", ProjectMemberRole.CONTRIBUTOR);
		UUID taskId = createAssignedTask(projectId, ownerToken, "Ship it", assignee.getId());
		await().atMost(Duration.ofSeconds(5))
				.until(() -> notificationRepository.countByUserId(assignee.getId()) == 1);

		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateCommentRequest("Looks good"))))
				.andExpect(status().isCreated());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertEquals(2, notificationRepository.countByUserId(assignee.getId()));
			var page = notificationRepository.findFirstPageByUserId(assignee.getId(), 10);
			assertTrue(page.stream().anyMatch(n -> n.getType() == NotificationType.TASK_COMMENT_ADDED));
		});
	}

	@Test
	void assigningATaskToYourselfDoesNotCreateANotification() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User owner = userRepository.findByEmail("owner4@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest("Ship it", null, TaskPriority.MEDIUM, null, owner.getId()))))
				.andExpect(status().isCreated());

		await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertEquals(0, notificationRepository.countByUserId(owner.getId())));
	}

	private UUID createAssignedTask(UUID projectId, String token, String title, UUID assigneeId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper
								.writeValueAsString(new CreateTaskRequest(title, null, TaskPriority.MEDIUM, null, assigneeId))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private User addProjectMember(UUID orgId, UUID projectId, String email, ProjectMemberRole role) throws Exception {
		registerAndGetToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Organization organization = organizationRepository.findById(orgId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, user, MembershipRole.MEMBER));
		Project project = projectRepository.findById(projectId).orElseThrow();
		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, role));
		return user;
	}

	private UUID createProject(UUID orgId, String token, String key, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateProjectRequest(key, name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private UUID createOrganization(String token, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateOrganizationRequest(name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private String registerAndGetToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest(email, "supersecret", "Name"))))
				.andExpect(status().isCreated())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

}
