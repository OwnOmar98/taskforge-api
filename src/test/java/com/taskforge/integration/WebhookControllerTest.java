package com.taskforge.integration;

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
import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.integration.dto.CreateWebhookRequest;
import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

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
class WebhookControllerTest {

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
	void creatingAWebhookReturnsTheSecretOnce() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateWebhookRequest("https://example.test/hook"))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.url").value("https://example.test/hook"))
				.andExpect(jsonPath("$.secret").exists());
	}

	@Test
	void listingWebhooksDoesNotIncludeTheSecret() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		createWebhook(orgId, ownerToken, "https://example.test/hook");

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].url").value("https://example.test/hook"))
				.andExpect(jsonPath("$[0].secret").doesNotExist());
	}

	@Test
	void deletingAWebhookRemovesItFromTheList() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID webhookId = createWebhook(orgId, ownerToken, "https://example.test/hook");

		mockMvc.perform(delete("/api/v1/organizations/" + orgId + "/webhooks/" + webhookId)
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void nonAdminCannotCreateAWebhook() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		addMember(orgId, "member4@acme.test", MembershipRole.MEMBER);
		String memberToken = loginAndGetToken("member4@acme.test");

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + memberToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateWebhookRequest("https://example.test/hook"))))
				.andExpect(status().isForbidden());
	}

	@Test
	void nonAdminCannotListWebhooks() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		addMember(orgId, "member5@acme.test", MembershipRole.MEMBER);
		String memberToken = loginAndGetToken("member5@acme.test");

		mockMvc.perform(get("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void nonAdminCannotDeleteAWebhook() throws Exception {
		String ownerToken = registerAndGetToken("owner6@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme6");
		UUID webhookId = createWebhook(orgId, ownerToken, "https://example.test/hook");
		addMember(orgId, "member6@acme.test", MembershipRole.MEMBER);
		String memberToken = loginAndGetToken("member6@acme.test");

		mockMvc.perform(delete("/api/v1/organizations/" + orgId + "/webhooks/" + webhookId)
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isForbidden());
	}

	private UUID createWebhook(UUID orgId, String token, String url) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateWebhookRequest(url))))
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
