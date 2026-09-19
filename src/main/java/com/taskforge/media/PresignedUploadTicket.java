package com.taskforge.media;

import java.net.URL;
import java.util.Map;

public record PresignedUploadTicket(String storageKey, URL uploadUrl, Map<String, String> requiredHeaders) {
}
