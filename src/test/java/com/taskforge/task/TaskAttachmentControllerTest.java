package com.taskforge.task;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.localstack.LocalStackContainer.Service;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.dto.ConfirmPresignedUploadRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.PresignedUploadRequest;
import com.taskforge.task.dto.PresignedUploadResponse;

import tools.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskAttachmentControllerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	// MinIO (the previous backend here) locked down anonymous image pulls on
	// every public registry in September 2026, breaking CI outright.
	// LocalStack is purpose-built for exactly this - mocking AWS services in
	// tests - and the app needs no code change to use it: it already talks to
	// S3 through a generic, endpoint-configurable AWS SDK client, the same
	// one already pointed at real DO Spaces in prod. Pinned to 3.8
	// specifically (not a current tag): newer LocalStack releases added a
	// license-activation gate that now refuses to start any service,
	// including community-tier S3, without a paid auth token - confirmed
	// directly by running the current tag and watching it exit immediately
	// with that error. 3.8 predates that gate and starts with no credentials
	// at all, which is all a plain S3 mock for tests needs.
	@Container
	static LocalStackContainer localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
			.withServices(Service.S3);

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.endpoint", () -> localstack.getEndpointOverride(Service.S3).toString());
		// Small enough to exercise the size-rejection path without uploading a
		// real multi-megabyte file in every test run.
		registry.add("app.storage.max-file-size-bytes", () -> "1024");
	}

	@BeforeAll
	static void createBucket() {
		try (S3Client client = S3Client.builder()
				.endpointOverride(localstack.getEndpointOverride(Service.S3))
				.region(Region.of(localstack.getRegion()))
				.credentialsProvider(StaticCredentialsProvider
						.create(AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
				.forcePathStyle(true)
				.build()) {
			client.createBucket(CreateBucketRequest.builder().bucket("taskforge-test").build());

			// A canned per-object ACL alone isn't enough for every S3-compatible
			// backend to actually serve an object anonymously - a bucket policy is
			// the one mechanism guaranteed to work everywhere, which is what the
			// public-visibility test below actually needs to verify.
			String publicReadPolicy = """
					{
					  "Version": "2012-10-17",
					  "Statement": [
					    {
					      "Effect": "Allow",
					      "Principal": "*",
					      "Action": "s3:GetObject",
					      "Resource": "arn:aws:s3:::taskforge-test/*"
					    }
					  ]
					}
					""";
			client.putBucketPolicy(
					PutBucketPolicyRequest.builder().bucket("taskforge-test").policy(publicReadPolicy).build());
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void uploadingThenDownloadingAnAttachmentRoundTrips() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

		MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "hello.txt", "text/plain", content))
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.filename").exists())
				.andExpect(jsonPath("$.sizeBytes").value(content.length))
				.andReturn();

		UUID attachmentId = UUID.fromString(
				objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("id").stringValue());

		MvcResult downloadResult = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId
						+ "/download")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isFound())
				.andExpect(header().exists("Location"))
				.andReturn();

		String presignedUrl = downloadResult.getResponse().getHeader("Location");
		HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(presignedUrl)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, fetched.statusCode());
		assertEquals("hello world", new String(fetched.body(), StandardCharsets.UTF_8));
	}

	@Test
	void publicAttachmentIsServedViaAStableUrlNotAPresignedOne() throws Exception {
		String ownerToken = registerAndGetToken("owner7@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme7");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "public.txt", "text/plain",
								"public content".getBytes(StandardCharsets.UTF_8)))
						.param("visibility", "PUBLIC")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.visibility").value("PUBLIC"))
				.andReturn();
		UUID attachmentId = UUID.fromString(
				objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("id").stringValue());

		MvcResult firstDownload = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId
						+ "/download")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isFound())
				.andReturn();
		String firstUrl = firstDownload.getResponse().getHeader("Location");

		// A second request returns the exact same URL - no signature, no
		// expiry, unlike a presigned one which differs (and expires) every call.
		MvcResult secondDownload = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId
						+ "/download")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isFound())
				.andReturn();
		String secondUrl = secondDownload.getResponse().getHeader("Location");

		assertEquals(firstUrl, secondUrl);
		assertTrue(!firstUrl.contains("X-Amz-Expires") && !firstUrl.contains("X-Amz-Signature"));

		HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(firstUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, fetched.statusCode());
		assertEquals("public content", new String(fetched.body(), StandardCharsets.UTF_8));
	}

	@Test
	void clientDirectUploadRoundTrips() throws Exception {
		String ownerToken = registerAndGetToken("owner8@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme8");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		byte[] content = "hello direct".getBytes(StandardCharsets.UTF_8);

		MvcResult presignResult = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new PresignedUploadRequest("direct.txt", "text/plain", content.length, null))))
				.andExpect(status().isCreated())
				.andReturn();

		PresignedUploadResponse ticket = objectMapper.readValue(presignResult.getResponse().getContentAsString(),
				PresignedUploadResponse.class);

		// The client uploads directly to storage here - this server is never in
		// the data path for this request at all.
		HttpRequest.Builder putRequest = HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
				.PUT(HttpRequest.BodyPublishers.ofByteArray(content));
		// java.net.http.HttpClient refuses to let callers set "Host" explicitly -
		// it derives it from the URI itself, which already matches what was
		// signed since we're PUTting to the exact signed URL.
		ticket.requiredHeaders().forEach((name, value) -> {
			if (!"host".equalsIgnoreCase(name)) {
				putRequest.header(name, value);
			}
		});
		HttpResponse<Void> putResponse = HttpClient.newHttpClient().send(putRequest.build(),
				HttpResponse.BodyHandlers.discarding());
		assertEquals(200, putResponse.statusCode());

		MvcResult completeResult = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned/complete")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ConfirmPresignedUploadRequest(ticket.storageKey(), "direct.txt", null))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.filename").value("direct.txt"))
				.andExpect(jsonPath("$.sizeBytes").value(content.length))
				.andExpect(jsonPath("$.visibility").value("PRIVATE"))
				.andReturn();

		UUID attachmentId = UUID.fromString(
				objectMapper.readTree(completeResult.getResponse().getContentAsString()).get("id").stringValue());

		MvcResult downloadResult = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId
						+ "/download")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isFound())
				.andReturn();

		String downloadUrl = downloadResult.getResponse().getHeader("Location");
		HttpResponse<byte[]> fetched = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

		assertEquals(200, fetched.statusCode());
		assertEquals("hello direct", new String(fetched.body(), StandardCharsets.UTF_8));
	}

	@Test
	void confirmingBeforeTheDirectUploadHappensIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner9@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme9");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		MvcResult presignResult = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new PresignedUploadRequest("never.txt", "text/plain", 10, null))))
				.andExpect(status().isCreated())
				.andReturn();
		PresignedUploadResponse ticket = objectMapper.readValue(presignResult.getResponse().getContentAsString(),
				PresignedUploadResponse.class);

		// Never actually PUT anything to ticket.uploadUrl() - confirm should
		// verify the object exists rather than trust the client's word for it.
		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned/complete")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ConfirmPresignedUploadRequest(ticket.storageKey(), "never.txt", null))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-004"));
	}

	@Test
	void confirmingAStorageKeyFromADifferentTaskIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner10@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme10");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		UUID otherTaskId = createTask(projectId, ownerToken, "Other task");

		MvcResult presignResult = mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new PresignedUploadRequest("file.txt", "text/plain", 10, null))))
				.andExpect(status().isCreated())
				.andReturn();
		PresignedUploadResponse ticket = objectMapper.readValue(presignResult.getResponse().getContentAsString(),
				PresignedUploadResponse.class);

		mockMvc.perform(post("/api/v1/tasks/" + otherTaskId + "/attachments/presigned/complete")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new ConfirmPresignedUploadRequest(ticket.storageKey(), "file.txt", null))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-004"));
	}

	@Test
	void requestingAPresignedUploadThatDeclaresTooLargeIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner11@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme11");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new PresignedUploadRequest("big.txt", "text/plain", 2048, null))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-002"));
	}

	@Test
	void nonProjectMemberCannotRequestAPresignedUpload() throws Exception {
		String ownerToken = registerAndGetToken("owner12@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme12");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String outsiderToken = registerAndGetToken("outsider12@acme.test");

		mockMvc.perform(post("/api/v1/tasks/" + taskId + "/attachments/presigned")
						.header("Authorization", "Bearer " + outsiderToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new PresignedUploadRequest("file.txt", "text/plain", 10, null))))
				.andExpect(status().isForbidden());
	}

	@Test
	void presignedDownloadUrlHonorsConfiguredTtl() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "hi.txt", "text/plain",
								"hi".getBytes(StandardCharsets.UTF_8)))
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isCreated())
				.andReturn();
		UUID attachmentId = UUID.fromString(
				objectMapper.readTree(uploadResult.getResponse().getContentAsString()).get("id").stringValue());

		MvcResult downloadResult = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/attachments/" + attachmentId
						+ "/download")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isFound())
				.andReturn();

		String presignedUrl = URLDecoder.decode(downloadResult.getResponse().getHeader("Location"),
				StandardCharsets.UTF_8);

		// application.yml configures a 10-minute presigned-url-ttl.
		assertTrue(presignedUrl.contains("X-Amz-Expires=600"));
	}

	@Test
	void emptyFileIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]))
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-001"));
	}

	@Test
	void fileExceedingMaxSizeIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme4");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		byte[] tooBig = new byte[2048];

		mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "big.txt", "text/plain", tooBig))
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-002"));
	}

	@Test
	void unsupportedContentTypeIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme5");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");

		mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "malware.exe", "application/x-msdownload",
								"hi".getBytes(StandardCharsets.UTF_8)))
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("MEDIA-003"));
	}

	@Test
	void nonProjectMemberCannotUploadOrDownload() throws Exception {
		String ownerToken = registerAndGetToken("owner6@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme6");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		UUID taskId = createTask(projectId, ownerToken, "Ship it");
		String outsiderToken = registerAndGetToken("outsider6@acme.test");

		mockMvc.perform(multipart("/api/v1/tasks/" + taskId + "/attachments")
						.file(new MockMultipartFile("file", "hi.txt", "text/plain",
								"hi".getBytes(StandardCharsets.UTF_8)))
						.header("Authorization", "Bearer " + outsiderToken))
				.andExpect(status().isForbidden());
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
