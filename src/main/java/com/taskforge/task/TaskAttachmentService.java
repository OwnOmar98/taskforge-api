package com.taskforge.task;

import java.net.URL;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.media.Media;
import com.taskforge.media.MediaErrorCode;
import com.taskforge.media.MediaRepository;
import com.taskforge.media.MediaService;
import com.taskforge.media.PresignedUploadTicket;
import com.taskforge.storage.MediaVisibility;
import com.taskforge.task.dto.ConfirmPresignedUploadRequest;
import com.taskforge.task.dto.PresignedUploadRequest;
import com.taskforge.task.dto.PresignedUploadResponse;
import com.taskforge.task.dto.TaskAttachmentResponse;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class TaskAttachmentService {

	private final TaskRepository taskRepository;
	private final MediaService mediaService;
	private final MediaRepository mediaRepository;
	private final UserRepository userRepository;

	public TaskAttachmentService(TaskRepository taskRepository, MediaService mediaService,
			MediaRepository mediaRepository, UserRepository userRepository) {
		this.taskRepository = taskRepository;
		this.mediaService = mediaService;
		this.mediaRepository = mediaRepository;
		this.userRepository = userRepository;
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'CONTRIBUTE')")
	@Transactional
	public TaskAttachmentResponse uploadAttachment(UUID taskId, MultipartFile file, MediaVisibility visibility,
			UUID uploadedById) {
		Task task = findTaskOrThrow(taskId);
		User uploadedBy = userRepository.findById(uploadedById).orElseThrow();

		// mediaService.upload() runs in its own REQUIRES_NEW transaction and
		// returns an already-committed but now-detached Media. Adding a detached
		// entity straight into a cascade=ALL collection makes Hibernate try to
		// cascade-persist it as if it were new, which fails since it already
		// has an id. Re-fetching a reference attached to THIS session fixes
		// that without an extra round trip - getReferenceById is a lazy proxy,
		// not a real query.
		Media media = mediaService.upload("tasks/" + taskId, file, visibility, uploadedBy);
		Media managedMedia = mediaRepository.getReferenceById(media.getId());
		task.getAttachments().add(managedMedia);

		return toResponse(media);
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'MEMBER')")
	@Transactional(readOnly = true)
	public URL getDownloadUrl(UUID taskId, UUID attachmentId) {
		Task task = findTaskOrThrow(taskId);
		Media media = findAttachmentOrThrow(task, attachmentId);

		return mediaService.getAccessUrl(media);
	}

	// Client-direct upload, step 1: no DB write here at all - just hands back a
	// signed PUT URL scoped to this task, so we don't even need to load the
	// Task entity itself, only confirm the caller is allowed to touch it.
	@PreAuthorize("hasPermission(#taskId, 'Task', 'CONTRIBUTE')")
	public PresignedUploadResponse requestPresignedUpload(UUID taskId, PresignedUploadRequest request) {
		PresignedUploadTicket ticket = mediaService.requestPresignedUpload("tasks/" + taskId, request.contentType(),
				request.sizeBytes(), request.visibility());

		return new PresignedUploadResponse(ticket.uploadUrl().toString(), ticket.storageKey(),
				ticket.requiredHeaders());
	}

	// Client-direct upload, step 2: confirm the direct upload actually
	// happened and link it to this task. The storage-key prefix check stops a
	// member from confirming a key they were only ever issued for a different
	// task.
	@PreAuthorize("hasPermission(#taskId, 'Task', 'CONTRIBUTE')")
	@Transactional
	public TaskAttachmentResponse confirmPresignedUpload(UUID taskId, ConfirmPresignedUploadRequest request,
			UUID uploadedById) {
		String expectedPrefix = "tasks/" + taskId + "/";
		if (!request.storageKey().startsWith(expectedPrefix)) {
			throw new ResourceNotFoundException(MediaErrorCode.UPLOAD_NOT_FOUND,
					MediaErrorCode.UPLOAD_NOT_FOUND.defaultMessage());
		}

		Task task = findTaskOrThrow(taskId);
		User uploadedBy = userRepository.findById(uploadedById).orElseThrow();

		Media media = mediaService.confirmPresignedUpload(request.storageKey(), request.filename(),
				request.visibility(), uploadedBy);
		task.getAttachments().add(media);

		return toResponse(media);
	}

	private Task findTaskOrThrow(UUID taskId) {
		return taskRepository.findById(taskId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Task not found"));
	}

	private Media findAttachmentOrThrow(Task task, UUID mediaId) {
		return task.getAttachments().stream()
				.filter(media -> media.getId().equals(mediaId))
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Attachment not found"));
	}

	private TaskAttachmentResponse toResponse(Media media) {
		return new TaskAttachmentResponse(media.getId(), media.getFilename(), media.getContentType(),
				media.getSizeBytes(), media.getVisibility(), media.getCreatedAt());
	}

}
