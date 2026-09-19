package com.taskforge.task.dto;

import java.time.Instant;
import java.util.UUID;

import com.taskforge.storage.MediaVisibility;

public record TaskAttachmentResponse(UUID id, String filename, String contentType, long sizeBytes,
		MediaVisibility visibility, Instant createdAt) {
}
