package com.taskforge.task;

import java.net.URI;
import java.net.URL;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.taskforge.security.CurrentUserId;
import com.taskforge.storage.MediaVisibility;
import com.taskforge.task.dto.ConfirmPresignedUploadRequest;
import com.taskforge.task.dto.PresignedUploadRequest;
import com.taskforge.task.dto.PresignedUploadResponse;
import com.taskforge.task.dto.TaskAttachmentResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/attachments")
public class TaskAttachmentController {

	private final TaskAttachmentService taskAttachmentService;

	public TaskAttachmentController(TaskAttachmentService taskAttachmentService) {
		this.taskAttachmentService = taskAttachmentService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskAttachmentResponse upload(@PathVariable UUID taskId, @RequestParam("file") MultipartFile file,
			@RequestParam(name = "visibility", defaultValue = "PRIVATE") MediaVisibility visibility,
			@CurrentUserId UUID currentUserId) {
		return taskAttachmentService.uploadAttachment(taskId, file, visibility, currentUserId);
	}

	@GetMapping("/{attachmentId}/download")
	public ResponseEntity<Void> download(@PathVariable UUID taskId, @PathVariable UUID attachmentId) {
		URL presignedUrl = taskAttachmentService.getDownloadUrl(taskId, attachmentId);
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(presignedUrl.toString())).build();
	}

	// Client-direct upload, step 1: request a signed PUT URL. The client then
	// uploads the raw bytes straight to storage, bypassing this server entirely.
	@PostMapping("/presigned")
	@ResponseStatus(HttpStatus.CREATED)
	public PresignedUploadResponse requestPresignedUpload(@PathVariable UUID taskId,
			@Valid @RequestBody PresignedUploadRequest request) {
		return taskAttachmentService.requestPresignedUpload(taskId, request);
	}

	// Client-direct upload, step 2: after the direct PUT succeeds, the client
	// calls this to confirm it and create the actual attachment record.
	@PostMapping("/presigned/complete")
	@ResponseStatus(HttpStatus.CREATED)
	public TaskAttachmentResponse completePresignedUpload(@PathVariable UUID taskId,
			@Valid @RequestBody ConfirmPresignedUploadRequest request, @CurrentUserId UUID currentUserId) {
		return taskAttachmentService.confirmPresignedUpload(taskId, request, currentUserId);
	}

}
