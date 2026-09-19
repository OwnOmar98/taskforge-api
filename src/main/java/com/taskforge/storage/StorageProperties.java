package com.taskforge.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
		URI endpoint,
		String region,
		String bucket,
		String accessKey,
		String secretKey,
		// MinIO needs path-style (endpoint/bucket/key); DO Spaces uses
		// virtual-hosted-style (bucket.endpoint/key) like AWS S3 does.
		boolean forcePathStyle,
		Duration presignedUrlTtl,
		long maxFileSizeBytes,
		Set<String> allowedContentTypes) {
}
