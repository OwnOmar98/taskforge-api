package com.taskforge.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.taskforge.integration.WebhookEventHandler;
import com.taskforge.notification.NotificationEventHandler;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Acknowledgement is automatic: the message is acked when a listener method
// returns, and on an exception it's retried a few times in place (see
// spring.rabbitmq.listener.simple.retry) and then rejected without requeue,
// which dead-letters it. A message that can't even be read skips straight to
// rejection - retrying won't make it readable.
@Component
@ConditionalOnProperty(name = "app.events.transport", havingValue = "rabbitmq")
public class RabbitEventConsumers {

	private final NotificationEventHandler notificationHandler;
	private final WebhookEventHandler webhookHandler;
	private final ObjectMapper objectMapper;

	public RabbitEventConsumers(NotificationEventHandler notificationHandler, WebhookEventHandler webhookHandler,
			ObjectMapper objectMapper) {
		this.notificationHandler = notificationHandler;
		this.webhookHandler = webhookHandler;
		this.objectMapper = objectMapper;
	}

	@RabbitListener(queues = RabbitTopology.NOTIFICATIONS_QUEUE)
	void onNotificationEvent(Message message) {
		notificationHandler.handle(read(message));
	}

	@RabbitListener(queues = RabbitTopology.WEBHOOKS_QUEUE)
	void onWebhookEvent(Message message) {
		webhookHandler.handle(read(message));
	}

	private DomainEvent read(Message message) {
		DomainEvent event;
		try {
			event = objectMapper.readValue(message.getBody(), DomainEvent.class);
		}
		catch (JacksonException e) {
			throw new AmqpRejectAndDontRequeueException("Unreadable event message", e);
		}
		if (event.id() == null || event.type() == null || event.data() == null) {
			throw new AmqpRejectAndDontRequeueException("Event message is missing its id, type or data");
		}
		return event;
	}

}
