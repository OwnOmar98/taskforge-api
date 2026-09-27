package com.taskforge.organization;

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
import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.dto.ChangeRoleRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static com.taskforge.organization.OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE;
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
class MembershipControllerTest {

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
	void ownerCanListMembers() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		addMember(orgId, "member1@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/members")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)));
	}

	@Test
	void adminCanChangeAMembersRole() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User member = addMember(orgId, "member2@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/members/" + member.getId())
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(MembershipRole.ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void nonAdminCannotChangeAMembersRole() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User memberA = addMember(orgId, "member3a@acme.test", MembershipRole.MEMBER);
		String memberAToken = loginAndGetToken("member3a@acme.test");
		User memberB = addMember(orgId, "member3b@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/members/" + memberB.getId())
						.header("Authorization", "Bearer " + memberAToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(MembershipRole.ADMIN))))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminCannotPromoteAMemberToOwner() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User member = addMember(orgId, "member4@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/members/" + member.getId())
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(MembershipRole.OWNER))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(CANNOT_MODIFY_OWNER_ROLE.code()));
	}

	@Test
	void ownersMembershipCannotBeChangedThroughTheRoleEndpoint() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User owner = userRepository.findByEmail("owner5@acme.test").orElseThrow();

		mockMvc.perform(patch("/api/v1/organizations/" + orgId + "/members/" + owner.getId())
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new ChangeRoleRequest(MembershipRole.ADMIN))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(CANNOT_MODIFY_OWNER_ROLE.code()));
	}

	@Test
	void adminCanRemoveAMember() throws Exception {
		String ownerToken = registerAndGetToken("owner6@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User member = addMember(orgId, "member6@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(delete("/api/v1/organizations/" + orgId + "/members/" + member.getId())
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());
	}

	@Test
	void ownerCannotBeRemoved() throws Exception {
		String ownerToken = registerAndGetToken("owner7@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		User owner = userRepository.findByEmail("owner7@acme.test").orElseThrow();

		mockMvc.perform(delete("/api/v1/organizations/" + orgId + "/members/" + owner.getId())
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(CANNOT_MODIFY_OWNER_ROLE.code()));
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
