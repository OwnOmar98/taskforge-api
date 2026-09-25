package com.taskforge.notification;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import com.taskforge.common.exception.BadRequestException;
import com.taskforge.common.exception.GeneralErrorCode;

// Keyset pagination needs a tiebreaker alongside createdAt: two notifications
// can share the same instant, and createdAt alone isn't unique. id is the
// tiebreaker, not a substitute for it - id is a random UUID, not an
// insertion-ordered value, so sorting by id alone would be meaningless.
// Encoded as opaque base64 rather than exposing createdAt/id as separate
// query params, so nothing about the row shape leaks into - or has to stay
// stable in - the API contract.
record NotificationCursor(Instant createdAt, UUID id) {

	String encode() {
		String raw = createdAt.toString() + '|' + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	static NotificationCursor decode(String cursor) {
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int separator = raw.indexOf('|');
			return new NotificationCursor(Instant.parse(raw.substring(0, separator)),
					UUID.fromString(raw.substring(separator + 1)));
		}
		catch (RuntimeException e) {
			throw new BadRequestException(GeneralErrorCode.VALIDATION_FAILED, "Invalid cursor");
		}
	}

}
