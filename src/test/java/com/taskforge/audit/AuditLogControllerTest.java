package com.taskforge.audit;

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

import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.organization.dto.ChangeRoleRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class AuditLogControllerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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
	void changingATaskStatusCreatesAnAuditLogEntry() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest(null, null, TaskStatus.DONE, null, null, null, 0L))))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/audit-logs")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].action").value("TASK_STATUS_CHANGED"))
				.andExpect(jsonPath("$.content[0].entityId").value(taskId.toString()))
				.andExpect(jsonPath("$.content[0].metadata.oldStatus").value("TODO"))
				.andExpect(jsonPath("$.content[0].metadata.newStatus").value("DONE"));
	}

	@Test
	void changingAMembersRoleCreatesAnAuditLogEntry() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		User member = addMember(orgId, "member2@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/members/" + member.getId())
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(MembershipRole.ADMIN))))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/audit-logs")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].action").value("MEMBER_ROLE_CHANGED"))
				.andExpect(jsonPath("$.content[0].entityId").value(member.getId().toString()))
				.andExpect(jsonPath("$.content[0].metadata.oldRole").value("MEMBER"))
				.andExpect(jsonPath("$.content[0].metadata.newRole").value("ADMIN"));
	}

	@Test
	void nonAdminCannotListAuditLogs() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		addMember(orgId, "member3@acme.test", MembershipRole.MEMBER);
		String memberToken = loginAndGetToken("member3@acme.test");

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/audit-logs")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void settingTheSameStatusAgainDoesNotCreateAnAuditLogEntry() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest(null, null, TaskStatus.TODO, null, null, null, 0L))))
				.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/audit-logs")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	private UUID createTask(UUID projectId, String token, String title) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper
								.writeValueAsString(new CreateTaskRequest(title, null, TaskPriority.MEDIUM, null, null))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
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

	private User addMember(UUID organizationId, String email, MembershipRole role) throws Exception {
		registerAndGetToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Organization organization = organizationRepository.findById(organizationId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, user, role));
		return user;
	}

	private String registerAndGetToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest(email, "supersecret", "Name"))))
				.andExpect(status().isCreated())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

	private String loginAndGetToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, "supersecret"))))
				.andExpect(status().isOk())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

}
