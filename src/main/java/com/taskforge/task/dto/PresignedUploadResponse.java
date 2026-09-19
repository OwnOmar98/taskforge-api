package com.taskforge.task.dto;

import java.util.Map;

public record PresignedUploadResponse(String uploadUrl, String storageKey, Map<String, String> requiredHeaders) {
}
