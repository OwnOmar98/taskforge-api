package com.taskforge.common;

import java.util.Locale;

public final class EmailNormalizer {

	private EmailNormalizer() {
	}

	// null passes through so callers relying on a NOT NULL DB constraint to
	// reject a missing email still get that constraint violation, not an NPE.
	public static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

}
