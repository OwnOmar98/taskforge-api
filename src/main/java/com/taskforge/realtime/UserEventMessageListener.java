package com.taskforge.realtime;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

// One channel for every event type, with the type inside the message: a new
// UserEventType needs no new Redis subscription.
//
// Every instance receives every message and delivers only to the streams it
// holds locally - most messages are for users connected elsewhere and are
// dropped by the registry. Fine at this scale; the scale-out path is per-user
// channels (or Redis 7 sharded pub/sub) so an instance only receives messages
// for users it actually holds.
@Component
public class UserEventMessageListener implements MessageListener {

	private final UserEventStreamRegistry registry;
	private final ObjectMapper objectMapper;

	public UserEventMessageListener(UserEventStreamRegistry registry, ObjectMapper objectMapper) {
		this.registry = registry;
		this.objectMapper = objectMapper;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		UserEventMessage userEventMessage = objectMapper.readValue(message.getBody(), UserEventMessage.class);
		registry.deliver(userEventMessage.userId(), userEventMessage.event());
	}

}
