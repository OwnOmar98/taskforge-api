package com.taskforge.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.TaskScheduler;

// Starts the Redis subscription without making Redis a precondition for the
// app booting at all. The container on its own fails its initial subscribe
// outright - and with it, context startup - and only recovers automatically
// from connection loss *after* a first successful subscribe. Real-time push
// is best-effort on top of persisted state (the same fail-open stance as the
// login rate limiter), so an app without Redis should still boot and serve
// REST; this keeps retrying in the background until Redis is reachable.
public class UserEventSubscription implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(UserEventSubscription.class);

	private final RedisMessageListenerContainer container;
	private final TaskScheduler taskScheduler;
	private final Duration retryInterval;
	private volatile boolean running;
	private volatile ScheduledFuture<?> pendingRetry;
	private boolean warned;

	public UserEventSubscription(RedisMessageListenerContainer container, TaskScheduler taskScheduler,
			Duration retryInterval) {
		this.container = container;
		this.taskScheduler = taskScheduler;
		this.retryInterval = retryInterval;
	}

	@Override
	public void start() {
		running = true;
		trySubscribe();
	}

	private synchronized void trySubscribe() {
		if (!running) {
			return;
		}
		try {
			container.start();
			log.info("Subscribed to real-time event channel");
		}
		catch (RuntimeException e) {
			// Resets the container so the next start() is a real retry rather
			// than a no-op on an already-"started" one.
			container.stop();
			// Once at WARN, then quietly: an outage lasting hours shouldn't
			// write a warning every few seconds for its whole duration.
			if (!warned) {
				log.warn("Real-time event channel unavailable, retrying every {}s until Redis is reachable",
						retryInterval.toSeconds(), e);
				warned = true;
			}
			else {
				log.debug("Real-time event channel still unavailable", e);
			}
			pendingRetry = taskScheduler.schedule(this::trySubscribe, Instant.now().plus(retryInterval));
		}
	}

	@Override
	public synchronized void stop() {
		running = false;
		if (pendingRetry != null) {
			pendingRetry.cancel(false);
		}
		container.stop();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

}
