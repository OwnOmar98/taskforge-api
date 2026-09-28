package com.taskforge.realtime;

import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.taskforge.common.AfterCommit;

import tools.jackson.databind.ObjectMapper;

// The one entry point for pushing anything to a user's open streams, from any
// feature. Goes through Redis even when the recipient's stream happens to be
// open on this same instance: one delivery path for every case, rather than a
// local shortcut plus a remote path that could drift apart.
//
// Redis pub/sub is fire-and-forget - a message published while no instance
// holds the user's stream, or during a Redis blip, is simply gone. Replayable
// events are recovered from their own table on reconnect (see
// EventReplaySource); signals are recovered by the client refetching.
@Component
public class UserEventPublisher {

	static final String CHANNEL = "taskforge:user-events";

	private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final Executor publishExecutor;

	public UserEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			@Qualifier("userEventPublishExecutor") Executor publishExecutor) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.publishExecutor = publishExecutor;
	}

	// After commit, not inline: pushing something whose transaction then rolls
	// back would show the user a change that never happened.
	//
	// Handed off to its own executor rather than published directly in the
	// after-commit callback: callers include request threads (TaskService), and
	// a Redis round trip - or a full connect timeout during a Redis outage -
	// has no business delaying the HTTP response of a change that already
	// committed.
	public void publishAfterCommit(UUID userId, UserEvent event) {
		// Serialized now, on the caller's thread, so the executor never touches
		// caller-owned objects after the handoff.
		String message = objectMapper.writeValueAsString(new UserEventMessage(userId, event));
		AfterCommit.run(() -> {
			try {
				publishExecutor.execute(() -> publish(message, event));
			}
			catch (TaskRejectedException e) {
				log.warn("Dropped real-time {} event: publish queue is full", event.type().wireName());
			}
		});
	}

	// Fails open, same stance as the login rate limiter: the push is a
	// best-effort convenience on top of a change that's already committed.
	private void publish(String message, UserEvent event) {
		try {
			redisTemplate.convertAndSend(CHANNEL, message);
		}
		catch (DataAccessException e) {
			log.warn("Could not publish real-time {} event", event.type().wireName(), e);
		}
	}

}
