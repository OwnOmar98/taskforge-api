package com.taskforge.e2e;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.redis.testcontainers.RedisContainer;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.integration.dto.CreateWebhookRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskStatus;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.UpdateTaskRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Distinct from WebhookSenderTest (PR24): that one calls WebhookSender.send()
// directly, unit-testing the resilience behavior in isolation. This one
// never touches WebhookSender at all - it marks a task done through the real
// HTTP endpoint and lets the real TaskStatusChangedEvent, the real async
// WebhookEventListener, and the real signing all run on their own, then
// verifies what WireMock actually received. That's the one path no other
// test exercises end-to-end.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskCompletionWebhookDeliveryE2ETest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@RegisterExtension
	static WireMockExtension wireMock = WireMockExtension.newInstance()
			.options(WireMockConfiguration.wireMockConfig().dynamicPort())
			.build();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void completingATaskDeliversACorrectlySignedWebhook() throws Exception {
		wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(200)));

		String ownerToken = register("owner-" + UUID.randomUUID() + "@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		UUID projectId = createProject(orgId, ownerToken, "ENG", "Engine");
		String secret = registerWebhook(orgId, ownerToken, wireMock.baseUrl() + "/hook");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(patch("/api/v1/projects/" + projectId + "/tasks/" + taskId)
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new UpdateTaskRequest(null, null, TaskStatus.DONE, null, null, null, 0L))))
				.andExpect(status().isOk());

		// Delivery is async (PR24) - wait for the real listener to actually
		// fire rather than asserting immediately after the HTTP response.
		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> wireMock.verify(1, postRequestedFor(urlEqualTo("/hook"))));

		LoggedRequest delivered = wireMock.findAll(postRequestedFor(urlEqualTo("/hook"))).get(0);
		String payload = delivered.getBodyAsString();

		JsonNode body = objectMapper.readTree(payload);
		assertEquals("task.completed", body.get("event").stringValue());
		assertEquals(taskId.toString(), body.get("taskId").stringValue());
		assertEquals(orgId.toString(), body.get("organizationId").stringValue());

		// Proves the secret returned at registration is the actual one used to
		// sign this real delivery, not just some secret - recomputing the HMAC
		// independently and comparing is the only way to know the signature
		// wasn't just present, but correct.
		assertEquals(hmacSha256Hex(secret, payload), delivered.getHeader("X-Webhook-Signature"));
	}

	private String hmacSha256Hex(String secret, String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new IllegalStateException(e);
		}
	}

	private String register(String email) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest(email, "supersecret", "Owner"))))
				.andExpect(status().isCreated())
				.andReturn();

		return readJson(result).get("accessToken").stringValue();
	}

	private UUID createOrganization(String token, String name) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/organizations")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateOrganizationRequest(name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private UUID createProject(UUID orgId, String token, String key, String name) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/organizations/" + orgId + "/projects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateProjectRequest(key, name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private String registerWebhook(UUID orgId, String token, String url) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/organizations/" + orgId + "/webhooks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateWebhookRequest(url))))
				.andExpect(status().isCreated())
				.andReturn();

		return readJson(result).get("secret").stringValue();
	}

	private UUID createTask(UUID projectId, String token, String title) throws Exception {
		MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper
								.writeValueAsString(new CreateTaskRequest(title, null, TaskPriority.HIGH, null, null))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(readJson(result).get("id").stringValue());
	}

	private JsonNode readJson(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

}
