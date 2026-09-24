package com.taskforge.media;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.taskforge.common.exception.BadRequestException;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.storage.MediaVisibility;
import com.taskforge.storage.PresignedUpload;
import com.taskforge.storage.StorageProperties;
import com.taskforge.storage.StorageService;
import com.taskforge.storage.StoredObjectMetadata;
import com.taskforge.user.User;

@Service
public class MediaService {

	private final MediaRepository mediaRepository;
	private final StorageService storageService;
	private final StorageProperties properties;

	public MediaService(MediaRepository mediaRepository, StorageService storageService,
			StorageProperties properties) {
		this.mediaRepository = mediaRepository;
		this.storageService = storageService;
		this.properties = properties;
	}

	// keyPrefix scopes the storage key to whatever feature is uploading (e.g.
	// "tasks/{taskId}") purely for readability in the bucket - Media itself
	// stores no reference back to that owner.
	//
	// REQUIRES_NEW, not the default: the S3 write below is irreversible the
	// instant it succeeds, but without this the Media row would only join
	// whatever transaction the caller happens to be in. If that caller's
	// transaction later fails for an unrelated reason, its rollback would
	// erase this row too - leaving the real, already-uploaded S3 object with
	// no DB record at all. Committing this in its own transaction means the
	// row is durably saved the moment this method returns, independent of
	// whatever the caller does next.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Media upload(String keyPrefix, MultipartFile file, MediaVisibility visibility, User uploadedBy) {
		if (file.isEmpty()) {
			throw new BadRequestException(MediaErrorCode.EMPTY_FILE, MediaErrorCode.EMPTY_FILE.defaultMessage());
		}
		validateSizeAndType(file.getContentType(), file.getSize());

		String storageKey = keyPrefix + "/" + UUID.randomUUID();

		// Streams straight from the multipart part to the SDK rather than
		// buffering the whole file into a byte[] first - a large upload is only
		// ever held in memory (or spilled to disk by the servlet container) once.
		try (InputStream content = file.getInputStream()) {
			storageService.upload(storageKey, file.getContentType(), file.getSize(), content, visibility);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded file", e);
		}

		return mediaRepository.save(new Media(storageKey, file.getOriginalFilename(), file.getContentType(),
				file.getSize(), visibility, uploadedBy));
	}

	// Client-direct upload, step 1: validate what the client declares up front
	// and hand back a signed PUT URL - no Media row yet, since nothing has
	// actually been uploaded at this point.
	public PresignedUploadTicket requestPresignedUpload(String keyPrefix, String contentType, long sizeBytes,
			MediaVisibility visibility) {
		validateSizeAndType(contentType, sizeBytes);

		String storageKey = keyPrefix + "/" + UUID.randomUUID();
		PresignedUpload presigned = storageService.generatePresignedUploadUrl(storageKey, contentType, visibility);

		return new PresignedUploadTicket(storageKey, presigned.url(), presigned.requiredHeaders());
	}

	// Client-direct upload, step 2: verify the object actually exists in
	// storage and use ITS reported content-type/size, not whatever the client
	// claims here - this is the check himam-nest's equivalent flow skips.
	//
	// Deliberately NOT REQUIRES_NEW like upload() above: headObject() is a
	// read, not a write, so there's no irreversible external side effect here
	// to protect. The client already uploaded the object directly, independent
	// of this method entirely - if this transaction rolls back, the object is
	// still sitting in storage exactly as before, safe to confirm again later.
	public Media confirmPresignedUpload(String storageKey, String filename, MediaVisibility visibility,
			User uploadedBy) {
		StoredObjectMetadata actual = storageService.headObject(storageKey)
				.orElseThrow(() -> new ResourceNotFoundException(MediaErrorCode.UPLOAD_NOT_FOUND,
						MediaErrorCode.UPLOAD_NOT_FOUND.defaultMessage()));

		validateSizeAndType(actual.contentType(), actual.sizeBytes());

		return mediaRepository.save(
				new Media(storageKey, filename, actual.contentType(), actual.sizeBytes(), visibility, uploadedBy));
	}

	public URL getAccessUrl(Media media) {
		if (media.getVisibility() == MediaVisibility.PUBLIC) {
			return storageService.getPublicUrl(media.getStorageKey());
		}

		return storageService.generatePresignedDownloadUrl(media.getStorageKey());
	}

	private void validateSizeAndType(String contentType, long sizeBytes) {
		if (sizeBytes <= 0) {
			throw new BadRequestException(MediaErrorCode.EMPTY_FILE, MediaErrorCode.EMPTY_FILE.defaultMessage());
		}

		if (sizeBytes > properties.maxFileSizeBytes()) {
			throw new BadRequestException(MediaErrorCode.FILE_TOO_LARGE,
					MediaErrorCode.FILE_TOO_LARGE.defaultMessage());
		}

		if (contentType == null || !properties.allowedContentTypes().contains(contentType)) {
			throw new BadRequestException(MediaErrorCode.UNSUPPORTED_CONTENT_TYPE,
					MediaErrorCode.UNSUPPORTED_CONTENT_TYPE.defaultMessage());
		}
	}

}
