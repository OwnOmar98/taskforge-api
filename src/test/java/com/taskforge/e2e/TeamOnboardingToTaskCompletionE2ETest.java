package com.taskforge.e2e;

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
import com.taskforge.audit.AuditLogRepository;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.notification.NotificationRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.dto.CreateInvitationRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.dto.AddProjectMemberRequest;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A true end-to-end test: every step goes through the real HTTP endpoint
// (never a repository or service call directly), against real Postgres and
// Redis, exercising auth, organization, invitation, project, task, audit,
// and notification together as one continuous story - not one module in
// isolation the way every other test in this suite deliberately is.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TeamOnboardingToTaskCompletionE2ETest {

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
	private AuditLogRepository auditLogRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Test
	void aTeammateIsInvitedAssignedATaskAndCompletingItIsRecorded() throws Exception {
		String ownerEmail = "owner-" + UUID.randomUUID() + "@acme.test";
		String ownerToken = register(ownerEmail, "supersecret", "Owner", null);

		UUID orgId = createOrganization(ownerToken, "Acme");

		String teammateEmail = "teammate-" + UUID.randomUUID() + "@acme.test";
		String invitationToken = invite(orgId, ownerToken, teammateEmail, MembershipRole.MEMBER);

		// A brand-new user, not an existing one accepting separately - the
		// registration itself carries the invitation token and joins the org
		// as part of the same request.
		String teammateToken = register(teammateEmail, "supersecret", "Teammate", invitationToken);
		UUID teammateId = currentUserId(teammateToken);

		UUID projectId = createProject(orgId, ownerToken, "ENG", "Engine");
		addProjectMember(orgId, projectId, ownerToken, teammateId, ProjectMemberRole.CONTRIBUTOR);

		UUID taskId = createTask(projectId, ownerToken, "Ship the release", teammateId);

		// Assignment is async (PR22): wait for the real listener to actually
		// create it rather than asserting immediately after the HTTP response.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> mockMvc
				.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + teammateToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].type").value("TASK_ASSIGNED"))
				.andExpect(jsonPath("$.content[0].payload.taskId").value(taskId.toString())));

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + teammateToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest(null, null, TaskStatus.DONE, null, null, null, 0L))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DONE"));

		assertTrue(auditLogRepository.findAll().stream()
				.anyMatch(entry -> "TASK_STATUS_CHANGED".equals(entry.getAction())
						&& entry.getEntityId().equals(taskId)),
				"completing the task should have left a real audit trail, not just changed the row");
	}

	private String register(String email, String password, String fullName, String invitationToken)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper
								.writeValueAsString(new RegisterRequest(email, password, fullName, invitationToken))))
				.andExpect(status().isCreated())
				.andReturn();

		return readJson(result).get("accessToken").stringValue();
	}

	private UUID currentUserId(String token) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private UUID createOrganization(String token, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateOrganizationRequest(name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private String invite(UUID orgId, String inviterToken, String email, MembershipRole role) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + inviterToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateInvitationRequest(email, role))))
				.andExpect(status().isCreated())
				.andReturn();

		return readJson(result).get("token").stringValue();
	}

	private UUID createProject(UUID orgId, String token, String key, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateProjectRequest(key, name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private void addProjectMember(UUID orgId, UUID projectId, String token, UUID userId, ProjectMemberRole role)
			throws Exception {
		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new AddProjectMemberRequest(userId, role))))
				.andExpect(status().isCreated());
	}

	private UUID createTask(UUID projectId, String token, String title, UUID assigneeId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest(title, null, TaskPriority.HIGH, null, assigneeId))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

}
