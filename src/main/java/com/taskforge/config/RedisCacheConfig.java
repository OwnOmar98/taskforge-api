package com.taskforge.config;

import java.time.Duration;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

// TTL here is a backstop, not the correctness mechanism - every write path
// that changes a membership/role explicitly evicts the affected key.
// Without that, a stale cached role surviving until TTL expiry would be a
// real security bug (e.g. a demoted admin keeping elevated access), not
// just a performance nit.
@Configuration
@EnableCaching
public class RedisCacheConfig {

	private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

	@Bean
	public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
			RedisConnectionFactory connectionFactory) {
		// GenericJackson2JsonRedisSerializer (Jackson 2) is deprecated for
		// removal in this version - GenericJacksonJsonRedisSerializer (no "2")
		// is the Jackson 3 replacement, matching the Jackson version used
		// everywhere else in this project. enableUnsafeDefaultTyping() embeds a
		// type hint in the stored JSON so a cached value deserializes back into
		// its exact concrete type instead of a generic Map/String - "unsafe"
		// here just means unrestricted-by-package, which is fine since this
		// deserializes our own trusted cache, never attacker-controlled input.
		RedisSerializationContext.SerializationPair<Object> jsonValues = RedisSerializationContext.SerializationPair
				.fromSerializer(GenericJacksonJsonRedisSerializer.builder().enableUnsafeDefaultTyping().build());

		RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(DEFAULT_TTL)
				.serializeValuesWith(jsonValues);

		// Different cache names get their own TTL rather than sharing one
		// blanket default, to show the manager supports it - both are still
		// backstops behind the same mandatory eviction-on-write.
		// immediateWrites: Spring Data Redis 4 writes to the cache
		// asynchronously by default whenever the driver supports it (Lettuce
		// does), so @CacheEvict fired its DEL without waiting for it - a lookup
		// right after an eviction could still read the old role. For a cache
		// whose eviction is a security guarantee (a demoted user, a deleted
		// project) that window is the bug, so writes wait for Redis.
		RedisCacheWriter cacheWriter = RedisCacheWriter.create(connectionFactory,
				configurer -> configurer.immediateWrites());

		return builder -> builder.cacheWriter(cacheWriter)
				.cacheDefaults(defaultConfig)
				.withCacheConfiguration("orgMembershipRole", defaultConfig.entryTtl(Duration.ofMinutes(10)))
				.withCacheConfiguration("projectMemberRole", defaultConfig.entryTtl(Duration.ofMinutes(5)));
	}

}
