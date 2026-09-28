package com.taskforge.realtime;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(RealtimeProperties.class)
public class RealtimeConfig {

	@Bean
	public RedisMessageListenerContainer userEventListenerContainer(RedisConnectionFactory connectionFactory,
			UserEventMessageListener listener,
			@Qualifier("userEventDeliveryExecutor") ThreadPoolTaskExecutor userEventDeliveryExecutor) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.setTaskExecutor(userEventDeliveryExecutor);
		container.addMessageListener(listener, new ChannelTopic(UserEventPublisher.CHANNEL));
		// Started by UserEventSubscription instead, which tolerates Redis being
		// unreachable at boot.
		container.setAutoStartup(false);
		return container;
	}

	@Bean
	public UserEventSubscription userEventSubscription(
			@Qualifier("userEventListenerContainer") RedisMessageListenerContainer userEventListenerContainer,
			TaskScheduler taskScheduler) {
		// Same cadence as the container's own reconnect interval once subscribed.
		return new UserEventSubscription(userEventListenerContainer, taskScheduler, Duration.ofSeconds(5));
	}

	// The container's default dispatch executor is a SimpleAsyncTaskExecutor
	// - a new thread per message, unbounded - the same reason AsyncConfig
	// defines its own pools. Also kept separate from those pools because an
	// SSE write to a slow client blocks until its socket drains, and that
	// shouldn't hold up notification creation or webhook delivery. A message
	// rejected by a full queue is dropped, which pub/sub delivery already
	// tolerates (see UserEventPublisher).
	@Bean
	public ThreadPoolTaskExecutor userEventDeliveryExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(1000);
		executor.setThreadNamePrefix("user-event-delivery-");
		return executor;
	}

	// A single thread, not a pool: events are published in the order they were
	// committed, so two changes to the same thing can't reach a client in the
	// opposite order they happened in. Publishing is one fast Redis command,
	// so one thread keeps up; the queue absorbs a Redis slowdown, and past it
	// events are dropped rather than blocking callers.
	@Bean
	public ThreadPoolTaskExecutor userEventPublishExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1000);
		executor.setThreadNamePrefix("user-event-publish-");
		return executor;
	}

}
