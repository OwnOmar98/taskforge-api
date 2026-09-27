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
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.organization.dto.UpdateOrganizationRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class OrganizationControllerTest {

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
	private MembershipRepository membershipRepository;

	@Test
	void creatingAnOrganizationMakesTheCallerItsOwner() throws Exception {
		String token = registerAndGetToken("owner1@acme.test");

		UUID orgId = createOrganization(token, "Acme Ownership Test", "acme-ownership-test");
		User owner = userRepository.findByEmail("owner1@acme.test").orElseThrow();

		assertEquals(MembershipRole.OWNER,
				membershipRepository.findByOrganization_IdAndUser_Id(orgId, owner.getId()).orElseThrow().getRole());
	}

	@Test
	void organizationNameCollisionProducesDistinctSlugs() throws Exception {
		String firstToken = registerAndGetToken("owner2@acme.test");
		String secondToken = registerAndGetToken("owner3@acme.test");

		createOrganization(firstToken, "Acme Collision Test", "acme-collision-test");
		createOrganization(secondToken, "Acme Collision Test", "acme-collision-test-2");
	}

	@Test
	void ownerCanRenameOrganizationThroughTheApi() throws Exception {
		String token = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(token, "Acme Rename Test", "acme-rename-test");

		mockMvc.perform(patch("/api/v1/organizations/" + orgId)
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateOrganizationRequest("Acme Renamed"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Acme Renamed"));
	}

	@Test
	void nonMemberCannotRenameOrganizationThroughTheApi() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme NonMember Test", "acme-nonmember-test");

		String outsiderToken = registerAndGetToken("outsider1@acme.test");

		mockMvc.perform(patch("/api/v1/organizations/" + orgId)
						.header("Authorization", "Bearer " + outsiderToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new UpdateOrganizationRequest("Should not happen"))))
				.andExpect(status().isForbidden());
	}

	private UUID createOrganization(String token, String name, String expectedSlug) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateOrganizationRequest(name))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.slug").value(expectedSlug))
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
