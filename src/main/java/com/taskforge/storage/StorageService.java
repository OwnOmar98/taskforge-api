package com.taskforge.storage;

import java.io.InputStream;
import java.net.URL;
import java.util.Optional;

public interface StorageService {

	void upload(String key, String contentType, long contentLength, InputStream content,
			MediaVisibility visibility);

	URL generatePresignedDownloadUrl(String key);

	URL getPublicUrl(String key);

	PresignedUpload generatePresignedUploadUrl(String key, String contentType, MediaVisibility visibility);

	// Empty means nothing has been uploaded to this key yet - used to verify a
	// client-direct upload actually happened before trusting anything about it.
	Optional<StoredObjectMetadata> headObject(String key);

}
