package com.taskforge.task;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TaskControllerTest {

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

	@Test
	void fullTaskLifecycle() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");

		MvcResult createResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest("Write docs", "Initial draft", TaskPriority.MEDIUM, null, null))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("TODO"))
				.andExpect(jsonPath("$.version").value(0))
				.andReturn();

		UUID taskId = UUID.fromString(
				objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(get("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.totalElements").value(1));

		mockMvc.perform(get("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Write docs"));

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateTaskRequest(null, null, TaskStatus.DONE,
								null, null, null, 0L))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("DONE"))
				.andExpect(jsonPath("$.title").value("Write docs"))
				.andExpect(jsonPath("$.version").value(1));

		mockMvc.perform(delete("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void creatingATaskWithAPastDueDateIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");

		mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateTaskRequest("Late task", null,
								TaskPriority.LOW, LocalDate.now().minusDays(1), null))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION-001"));
	}

	@Test
	void assigningToANonProjectMemberIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		registerAndGetToken("outsider3@acme.test");
		User outsider = userRepository.findByEmail("outsider3@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateTaskRequest("Ship it", null,
								TaskPriority.HIGH, null, outsider.getId()))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("TASK-001"));
	}

	@Test
	void updatingWithAStaleVersionIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");

		MvcResult createResult = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest("Task", null, TaskPriority.LOW, null, null))))
				.andReturn();
		UUID taskId = UUID.fromString(
				objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest("First", null, null, null, null, null, 0L))))
				.andExpect(status().isOk());

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest("Stale", null, null, null, null, null, 0L))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("TASK-002"));
	}

	@Test
	void nonProjectMemberCannotAccessTasksAtAll() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		String outsiderToken = registerAndGetToken("outsider5@acme.test");

		mockMvc.perform(get("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void orgMemberWhoIsNotAProjectMemberCannotAccessTasks() throws Exception {
		String ownerToken = registerAndGetToken("owner6@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme6");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		String memberToken = registerAndGetToken("member6@acme.test");
		User member = userRepository.findByEmail("member6@acme.test").orElseThrow();
		Organization organization = organizationRepository.findById(orgId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, member, MembershipRole.MEMBER));

		mockMvc.perform(get("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isForbidden());
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
