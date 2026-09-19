package com.taskforge.task.dto;

import jakarta.validation.constraints.NotBlank;

import com.taskforge.storage.MediaVisibility;

public record ConfirmPresignedUploadRequest(@NotBlank String storageKey, @NotBlank String filename,
		MediaVisibility visibility) {

	public ConfirmPresignedUploadRequest {
		if (visibility == null) {
			visibility = MediaVisibility.PRIVATE;
		}
	}

}
