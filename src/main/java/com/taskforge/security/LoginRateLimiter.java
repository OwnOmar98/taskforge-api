package com.taskforge.security;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

// A sliding-window-log algorithm (Redis sorted set + Lua script), not a
// fixed-window counter or a token bucket: login abuse needs a hard,
// accurate cutoff per client, not the smooth burst-then-refill shaping a
// token bucket is designed for, and a fixed window would let a client burst
// up to 2x the limit by timing requests around a window boundary. The
// check-trim-count-add sequence runs as one Lua script so it's atomic under
// concurrent requests from the same client - doing it as separate Redis
// calls from Java would have the same race a check-then-insert has in SQL.
@Component
public class LoginRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> SCRIPT = loadScript();

	private final StringRedisTemplate redisTemplate;
	private final RateLimitProperties properties;

	public LoginRateLimiter(StringRedisTemplate redisTemplate, RateLimitProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	// Fails open on a Redis outage: a rate limiter that's temporarily
	// unreachable should degrade to "unprotected," not take login itself
	// down as collateral damage. This is a deliberate availability-over-
	// security tradeoff for exactly this kind of best-effort abuse
	// protection - not one that would be acceptable for, say, an
	// authorization check.
	@SuppressWarnings("unchecked")
	public RateLimitResult tryAcquire(String clientId) {
		try {
			long now = System.currentTimeMillis();
			long windowMillis = properties.window().toMillis();
			long ttlSeconds = Math.max(1, properties.window().toSeconds() + 1);
			String member = now + "-" + UUID.randomUUID();

			List<Long> result = redisTemplate.execute(SCRIPT, List.of("rate-limit:login:" + clientId),
					String.valueOf(now), String.valueOf(windowMillis), String.valueOf(properties.maxAttempts()),
					member, String.valueOf(ttlSeconds));

			if (result == null || result.get(0) == 1L) {
				return RateLimitResult.allow();
			}

			long retryAfterMillis = result.get(1);
			long retryAfterSeconds = Math.max(1, (retryAfterMillis + 999) / 1000);
			return new RateLimitResult(false, retryAfterSeconds);
		}
		catch (DataAccessException e) {
			log.warn("Rate limiter unavailable, allowing request through unthrottled", e);
			return RateLimitResult.allow();
		}
	}

	@SuppressWarnings("rawtypes")
	private static DefaultRedisScript<List> loadScript() {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/sliding-window-rate-limiter.lua"));
		script.setResultType(List.class);
		return script;
	}

}
