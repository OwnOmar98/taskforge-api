package com.taskforge.media;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.storage.MediaVisibility;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The real bug this proves a fix for: MediaService.upload() writes the file
// to S3 (irreversible - S3 has no idea what a JDBC transaction is) and then
// saves the Media row. If it only joins whatever transaction the caller
// happens to be in (the default), a later failure in that same caller
// transaction rolls the Media row back too, even though the S3 object it
// describes already exists for good - an orphaned object with no DB record
// pointing at it. REQUIRES_NEW fixes this: once upload() returns, the Media
// row is durably committed regardless of what the caller does afterward.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MediaServiceTransactionBoundaryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	static GenericContainer<?> minio = new GenericContainer<>("quay.io/minio/minio")
			.withCommand("server", "/data")
			.withEnv("MINIO_ROOT_USER", "minioadmin")
			.withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
			.withExposedPorts(9000)
			.waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("app.storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
	}

	@BeforeAll
	static void createBucket() {
		try (S3Client client = S3Client.builder()
				.endpointOverride(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000)))
				.region(Region.US_EAST_1)
				.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create("minioadmin", "minioadmin")))
				.forcePathStyle(true)
				.build()) {
			client.createBucket(CreateBucketRequest.builder().bucket("taskforge-test").build());
		}
	}

	@Autowired
	private MediaService mediaService;

	@Autowired
	private MediaRepository mediaRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void mediaRowSurvivesARollbackOfTheCallersEnclosingTransaction() {
		User uploader = userRepository.saveAndFlush(new User("uploader-" + UUID.randomUUID() + "@acme.test", "hash",
				"Uploader"));
		MockMultipartFile file = new MockMultipartFile("file", "hello.txt", "text/plain",
				"hello".getBytes());

		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		UUID[] mediaId = new UUID[1];

		// Simulates a caller whose own transaction fails for an unrelated reason
		// after the upload already happened and returned successfully.
		assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(status -> {
			Media media = mediaService.upload("boundary-test", file, MediaVisibility.PRIVATE, uploader);
			mediaId[0] = media.getId();
			throw new RuntimeException("simulated failure elsewhere in the caller's transaction");
		}));

		boolean mediaRowPersisted = mediaRepository.findById(mediaId[0]).isPresent();
		assertTrue(mediaRowPersisted,
				"the Media row should have survived the caller's rollback, since the S3 object it describes "
						+ "already exists for good and can never be rolled back itself");
	}

}
