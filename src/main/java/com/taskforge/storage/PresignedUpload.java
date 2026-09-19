package com.taskforge.storage;

import java.net.URL;
import java.util.Map;

// requiredHeaders: headers the client's direct PUT must send exactly as
// given (Content-Type, and x-amz-acl for PUBLIC) - they're part of what was
// signed, so the storage backend rejects the PUT if they're altered or missing.
public record PresignedUpload(URL url, Map<String, String> requiredHeaders) {
}
