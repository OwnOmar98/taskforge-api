package com.taskforge.security;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.taskforge.auth.AuthErrorCode;
import com.taskforge.common.EmailNormalizer;
import com.taskforge.common.exception.TooManyRequestsException;

// Distinct from LoginRateLimiter: that one throttles a client (by IP)
// regardless of which account it's hitting, protecting the endpoint itself
// from abuse. This locks an account (by email) regardless of which client
// is hitting it, protecting one target from distributed credential
// stuffing across many source IPs - the two are complementary, not
// redundant.
@Component
public class LoginAttemptService {

	private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

	private final StringRedisTemplate redisTemplate;
	private final LoginLockoutProperties properties;

	public LoginAttemptService(StringRedisTemplate redisTemplate, LoginLockoutProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	// Fails open on a Redis outage, same tradeoff as LoginRateLimiter: a
	// lockout check that can't reach its store should let login attempts
	// through uninterrupted rather than lock everyone out because Redis is
	// having a bad day.
	public void checkNotLocked(String email) {
		String key = failureKey(email);
		int failures;
		try {
			String raw = redisTemplate.opsForValue().get(key);
			failures = raw == null ? 0 : Integer.parseInt(raw);
		}
		catch (DataAccessException e) {
			log.warn("Lockout check unavailable, allowing login attempt through", e);
			return;
		}

		if (failures >= properties.maxFailures()) {
			// The TTL already sitting on the key *is* the real remaining lockout
			// time - falling back to the full configured duration only covers
			// the sliver where the key expires between this GET and the TTL
			// read below.
			long retryAfterSeconds;
			try {
				Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
				retryAfterSeconds = ttl != null && ttl > 0 ? ttl : properties.lockoutDuration().toSeconds();
			}
			catch (DataAccessException e) {
				retryAfterSeconds = properties.lockoutDuration().toSeconds();
			}

			throw new TooManyRequestsException(AuthErrorCode.ACCOUNT_LOCKED, AuthErrorCode.ACCOUNT_LOCKED.defaultMessage(),
					retryAfterSeconds);
		}
	}

	// The TTL is set only on the first failure of a streak, not refreshed on
	// every subsequent one - otherwise an attacker retrying just once every
	// lockoutDuration would keep the account locked out indefinitely.
	public void recordFailure(String email) {
		try {
			String key = failureKey(email);
			Long failures = redisTemplate.opsForValue().increment(key);

			if (failures != null && failures == 1L) {
				redisTemplate.expire(key, properties.lockoutDuration());
			}
		}
		catch (DataAccessException e) {
			log.warn("Lockout tracking unavailable, failed attempt not recorded", e);
		}
	}

	public void recordSuccess(String email) {
		try {
			redisTemplate.delete(failureKey(email));
		}
		catch (DataAccessException e) {
			log.warn("Lockout tracking unavailable, could not clear failure count", e);
		}
	}

	private String failureKey(String email) {
		return "login-failures:" + EmailNormalizer.normalize(email);
	}

}
