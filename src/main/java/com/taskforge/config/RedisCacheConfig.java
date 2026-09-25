package com.taskforge.config;

import java.time.Duration;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
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
	public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
		// The no-arg constructor, not one built from a plain ObjectMapper: it
		// configures default typing internally, embedding a "@class" hint in
		// the stored JSON so a cached value deserializes back into its exact
		// concrete type (an enum here) instead of a generic String/Map.
		RedisSerializationContext.SerializationPair<Object> jsonValues = RedisSerializationContext.SerializationPair
				.fromSerializer(new GenericJackson2JsonRedisSerializer());

		RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
				.entryTtl(DEFAULT_TTL)
				.serializeValuesWith(jsonValues);

		// Different cache names get their own TTL rather than sharing one
		// blanket default, to show the manager supports it - both are still
		// backstops behind the same mandatory eviction-on-write.
		return builder -> builder.cacheDefaults(defaultConfig)
				.withCacheConfiguration("orgMembershipRole", defaultConfig.entryTtl(Duration.ofMinutes(10)))
				.withCacheConfiguration("projectMemberRole", defaultConfig.entryTtl(Duration.ofMinutes(5)));
	}

}
