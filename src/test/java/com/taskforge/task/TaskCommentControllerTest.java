package com.taskforge.task;

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
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

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
class TaskCommentControllerTest {

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

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectMemberRepository projectMemberRepository;

	@Test
	void addingAndListingAndDeletingAComment() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		MvcResult result = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateCommentRequest("Looks good"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.body").value("Looks good"))
				.andReturn();

		UUID commentId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(get("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)));

		mockMvc.perform(delete("/api/v1/tasks/" + taskId + "/comments/" + commentId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void authorCanDeleteTheirOwnCommentWithoutAnyPrivilegedRole() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String viewerToken = addProjectMemberAndGetToken(orgId, projectId, "viewer3@acme.test",
				ProjectMemberRole.VIEWER);

		MvcResult result = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + viewerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateCommentRequest("My own comment"))))
				.andExpect(status().isCreated())
				.andReturn();
		UUID commentId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(delete("/api/v1/tasks/" + taskId + "/comments/" + commentId)
						.header("Authorization", "Bearer " + viewerToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void nonAuthorWithoutManagePermissionCannotDeleteSomeoneElsesComment() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String authorToken = addProjectMemberAndGetToken(orgId, projectId, "author4@acme.test",
				ProjectMemberRole.CONTRIBUTOR);
		String otherMemberToken = addProjectMemberAndGetToken(orgId, projectId, "other4@acme.test",
				ProjectMemberRole.CONTRIBUTOR);

		MvcResult result = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + authorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateCommentRequest("Author's comment"))))
				.andExpect(status().isCreated())
				.andReturn();
		UUID commentId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(delete("/api/v1/tasks/" + taskId + "/comments/" + commentId)
						.header("Authorization", "Bearer " + otherMemberToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void projectLeadCanDeleteSomeoneElsesCommentAsModeration() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String authorToken = addProjectMemberAndGetToken(orgId, projectId, "author5@acme.test",
				ProjectMemberRole.CONTRIBUTOR);
		String leadToken = addProjectMemberAndGetToken(orgId, projectId, "lead5@acme.test", ProjectMemberRole.LEAD);

		MvcResult result = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + authorToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateCommentRequest("Author's comment"))))
				.andExpect(status().isCreated())
				.andReturn();
		UUID commentId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());

		mockMvc.perform(delete("/api/v1/tasks/" + taskId + "/comments/" + commentId)
						.header("Authorization", "Bearer " + leadToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void nonProjectMemberCannotAccessTaskComments() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String outsiderToken = registerAndGetToken("outsider2@acme.test");

		mockMvc.perform(get("/api/v1/tasks/" + taskId + "/comments")
						.header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden());
	}

	private String addProjectMemberAndGetToken(UUID orgId, UUID projectId, String email, ProjectMemberRole role)
			throws Exception {
		String token = registerAndGetToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Organization organization = organizationRepository.findById(orgId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, user, MembershipRole.MEMBER));
		Project project = projectRepository.findById(projectId).orElseThrow();
		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, role));
		return token;
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
