package com.taskforge.project;

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
import com.taskforge.project.dto.AddProjectMemberRequest;
import com.taskforge.project.dto.ChangeProjectMemberRoleRequest;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static com.taskforge.project.ProjectErrorCode.ALREADY_PROJECT_MEMBER;
import static com.taskforge.project.ProjectErrorCode.NOT_AN_ORGANIZATION_MEMBER;
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
class ProjectMemberControllerTest {

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
	void projectLeadCanAddAnOrgMemberToTheProject() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		String memberToken = addOrgMemberAndGetToken(orgId, "member1@acme.test", MembershipRole.MEMBER);
		User member = userRepository.findByEmail("member1@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(member.getId(), ProjectMemberRole.CONTRIBUTOR))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("CONTRIBUTOR"));

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void cannotAddSomeoneWhoIsNotAnOrgMemberToAProject() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		registerAndGetToken("outsider2@acme.test");
		User outsider = userRepository.findByEmail("outsider2@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(outsider.getId(), ProjectMemberRole.VIEWER))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(NOT_AN_ORGANIZATION_MEMBER.code()));
	}

	@Test
	void cannotAddTheSameProjectMemberTwice() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		addOrgMemberAndGetToken(orgId, "member3@acme.test", MembershipRole.MEMBER);
		User member = userRepository.findByEmail("member3@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(member.getId(), ProjectMemberRole.VIEWER))))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(member.getId(), ProjectMemberRole.CONTRIBUTOR))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(ALREADY_PROJECT_MEMBER.code()));
	}

	@Test
	void orgMemberWithoutProjectRoleCannotManageProjectMembers() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		String memberToken = addOrgMemberAndGetToken(orgId, "member4@acme.test", MembershipRole.MEMBER);
		String otherMemberToken = addOrgMemberAndGetToken(orgId, "other4@acme.test", MembershipRole.MEMBER);
		User otherMember = userRepository.findByEmail("other4@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + memberToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(otherMember.getId(), ProjectMemberRole.VIEWER))))
				.andExpect(status().isForbidden());
	}

	@Test
	void projectLeadCanChangeRoleAndRemoveAMember() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		addOrgMemberAndGetToken(orgId, "member5@acme.test", MembershipRole.MEMBER);
		User member = userRepository.findByEmail("member5@acme.test").orElseThrow();

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new AddProjectMemberRequest(member.getId(), ProjectMemberRole.VIEWER))))
				.andExpect(status().isCreated());

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members/"
						+ member.getId())
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ChangeProjectMemberRoleRequest(ProjectMemberRole.CONTRIBUTOR))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("CONTRIBUTOR"));

		mockMvc.perform(delete("/api/v1/organizations/" + orgId + "/projects/" + projectId + "/members/"
						+ member.getId())
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());
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
