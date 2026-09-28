package com.taskforge.task;

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
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.dto.CreateLabelRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static com.taskforge.task.LabelErrorCode.LABEL_NAME_IN_USE;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LabelControllerTest {

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
	void adminCreatesALabelAndAnyOrgMemberCanListIt() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		String memberToken = addOrgMemberAndGetToken(orgId, "member1@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateLabelRequest("bug"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value("bug"));

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)));
	}

	@Test
	void nonAdminCannotCreateALabel() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		String memberToken = addOrgMemberAndGetToken(orgId, "member2@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + memberToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateLabelRequest("bug"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void duplicateLabelNameInSameOrgIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateLabelRequest("bug"))))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateLabelRequest("bug"))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(LABEL_NAME_IN_USE.code()));
	}

	@Test
	void projectMemberCanAttachAndDetachALabelFromATask() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		UUID labelId = createLabel(orgId, ownerToken, "bug");

		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/labels/" + labelId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(delete("/api/v1/tasks/" + taskId + "/labels/" + labelId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void attachingALabelFromADifferentOrganizationIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		String otherOwnerToken = registerAndGetToken("owner6@acme.test");
		UUID otherOrgId = createOrganization(otherOwnerToken, "Acme6");
		UUID foreignLabelId = createLabel(otherOrgId, otherOwnerToken, "bug");

		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/labels/" + foreignLabelId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNotFound());
	}

	private UUID createLabel(UUID orgId, String token, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/labels")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateLabelRequest(name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private UUID createTask(UUID projectId, String token, String title) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateTaskRequest(title, null, TaskPriority.MEDIUM, null, null))))
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

	private String addOrgMemberAndGetToken(UUID organizationId, String email, MembershipRole role) throws Exception {
		String token = registerAndGetToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Organization organization = organizationRepository.findById(organizationId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, user, role));
		return token;
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
