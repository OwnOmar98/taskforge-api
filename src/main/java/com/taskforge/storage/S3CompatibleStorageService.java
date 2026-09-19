package com.taskforge.storage;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import jakarta.annotation.PreDestroy;

// Single implementation for every S3-compatible backend (DO Spaces, MinIO):
// they all speak the same API, so per-environment behavior is a matter of
// configuration (endpoint, region, path-style), not a second implementation.
@Service
public class S3CompatibleStorageService implements StorageService {

	private final StorageProperties properties;
	private final S3Client s3Client;
	private final S3Presigner s3Presigner;

	public S3CompatibleStorageService(StorageProperties properties) {
		this.properties = properties;

		AwsCredentialsProvider credentials = StaticCredentialsProvider
				.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));

		this.s3Client = S3Client.builder()
				.endpointOverride(properties.endpoint())
				.region(Region.of(properties.region()))
				.credentialsProvider(credentials)
				.forcePathStyle(properties.forcePathStyle())
				.build();

		this.s3Presigner = S3Presigner.builder()
				.endpointOverride(properties.endpoint())
				.region(Region.of(properties.region()))
				.credentialsProvider(credentials)
				.serviceConfiguration(S3Configuration.builder()
						.pathStyleAccessEnabled(properties.forcePathStyle())
						.build())
				.build();
	}

	@Override
	public void upload(String key, String contentType, long contentLength, InputStream content,
			MediaVisibility visibility) {
		PutObjectRequest.Builder request = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.contentType(contentType)
				.contentLength(contentLength);
		applyVisibility(request, visibility);

		s3Client.putObject(request.build(), RequestBody.fromInputStream(content, contentLength));
	}

	@Override
	public PresignedUpload generatePresignedUploadUrl(String key, String contentType, MediaVisibility visibility) {
		PutObjectRequest.Builder putRequest = PutObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.contentType(contentType);
		applyVisibility(putRequest, visibility);

		PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
				.signatureDuration(properties.presignedUrlTtl())
				.putObjectRequest(putRequest.build())
				.build();

		PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

		// The client's direct PUT must echo these back exactly - they're part of
		// what got signed, so the storage backend rejects the request (this is
		// what stops a client from uploading with a different ACL than we agreed
		// to sign for) if they're missing or altered.
		Map<String, String> requiredHeaders = new LinkedHashMap<>();
		presigned.signedHeaders().forEach((name, values) -> requiredHeaders.put(name, String.join(",", values)));

		return new PresignedUpload(presigned.url(), requiredHeaders);
	}

	@Override
	public Optional<StoredObjectMetadata> headObject(String key) {
		try {
			HeadObjectResponse response = s3Client
					.headObject(HeadObjectRequest.builder().bucket(properties.bucket()).key(key).build());
			return Optional.of(new StoredObjectMetadata(response.contentType(), response.contentLength()));
		} catch (NoSuchKeyException e) {
			return Optional.empty();
		} catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return Optional.empty();
			}
			throw e;
		}
	}

	// PRIVATE sets no ACL at all (the service default), not some "deny" ACL -
	// there's no canned ACL that means "private", absence of one is what
	// private means here. PUBLIC is the only case that sets one explicitly.
	private void applyVisibility(PutObjectRequest.Builder request, MediaVisibility visibility) {
		if (visibility == MediaVisibility.PUBLIC) {
			request.acl(ObjectCannedACL.PUBLIC_READ);
		}
	}

	@Override
	public URL generatePresignedDownloadUrl(String key) {
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
				.bucket(properties.bucket())
				.key(key)
				.build();

		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
				.signatureDuration(properties.presignedUrlTtl())
				.getObjectRequest(getObjectRequest)
				.build();

		return s3Presigner.presignGetObject(presignRequest).url();
	}

	@Override
	public URL getPublicUrl(String key) {
		URI endpoint = properties.endpoint();
		String portSuffix = endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "";

		String urlString = properties.forcePathStyle()
				? endpoint.getScheme() + "://" + endpoint.getHost() + portSuffix + "/" + properties.bucket() + "/"
						+ key
				: endpoint.getScheme() + "://" + properties.bucket() + "." + endpoint.getHost() + portSuffix + "/"
						+ key;

		try {
			return URI.create(urlString).toURL();
		} catch (MalformedURLException e) {
			throw new IllegalStateException("Failed to build public URL for key " + key, e);
		}
	}

	@PreDestroy
	void close() {
		s3Client.close();
		s3Presigner.close();
	}

}
