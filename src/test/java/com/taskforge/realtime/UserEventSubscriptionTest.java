package com.taskforge.realtime;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class UserEventSubscriptionTest {

	private final RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
	private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

	UserEventSubscriptionTest() {
		scheduler.initialize();
	}

	@AfterEach
	void shutdownScheduler() {
		scheduler.shutdown();
	}

	@Test
	void redisBeingUnreachableAtStartupDoesNotFailStartupAndIsRetriedUntilItSucceeds() {
		doThrow(new RedisConnectionFailureException("down"))
				.doThrow(new RedisConnectionFailureException("still down"))
				.doNothing()
				.when(container).start();
		UserEventSubscription subscription = new UserEventSubscription(container, scheduler,
				Duration.ofMillis(50));

		assertDoesNotThrow(subscription::start);

		await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(container, times(3)).start());
	}

	@Test
	void stoppingCancelsAPendingRetry() throws Exception {
		doThrow(new RedisConnectionFailureException("down")).when(container).start();
		UserEventSubscription subscription = new UserEventSubscription(container, scheduler,
				Duration.ofMillis(200));

		subscription.start();
		subscription.stop();
		Thread.sleep(500);

		verify(container, times(1)).start();
	}

	@Test
	void aSuccessfulFirstAttemptSchedulesNoRetry() throws Exception {
		doNothing().when(container).start();
		UserEventSubscription subscription = new UserEventSubscription(container, scheduler,
				Duration.ofMillis(50));

		subscription.start();
		Thread.sleep(300);

		verify(container, times(1)).start();
	}

}
