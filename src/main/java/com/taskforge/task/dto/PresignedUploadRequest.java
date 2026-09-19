package com.taskforge.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import com.taskforge.storage.MediaVisibility;

public record PresignedUploadRequest(@NotBlank String filename, @NotBlank String contentType,
		@Positive long sizeBytes, MediaVisibility visibility) {

	public PresignedUploadRequest {
		if (visibility == null) {
			visibility = MediaVisibility.PRIVATE;
		}
	}

}
