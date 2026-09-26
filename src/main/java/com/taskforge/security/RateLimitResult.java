package com.taskforge.security;

public record RateLimitResult(boolean allowed, long retryAfterSeconds) {

	public static RateLimitResult allow() {
		return new RateLimitResult(true, 0);
	}

}
