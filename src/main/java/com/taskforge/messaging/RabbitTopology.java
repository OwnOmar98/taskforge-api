package com.taskforge.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.taskforge.integration.WebhookEventHandler;
import com.taskforge.notification.NotificationEventHandler;

// One topic exchange for every domain event (routing key = event type), and
// one queue per consumer bound to the types it handles, so each consumer gets
// its own copy and its own backlog. A message a consumer rejects goes to that
// consumer's dead-letter queue (<queue>.dlq) instead of being redelivered
// forever.
@Configuration
@ConditionalOnProperty(name = "app.events.transport", havingValue = "rabbitmq")
public class RabbitTopology {

	public static final String EXCHANGE = "taskforge.events";
	public static final String DEAD_LETTER_EXCHANGE = "taskforge.events.dlx";
	public static final String NOTIFICATIONS_QUEUE = "taskforge.notifications";
	public static final String WEBHOOKS_QUEUE = "taskforge.webhooks";

	@Bean
	public Declarables eventTopology(NotificationEventHandler notifications, WebhookEventHandler webhooks) {
		TopicExchange exchange = new TopicExchange(EXCHANGE);
		DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE);
		List<Declarable> declarables = new ArrayList<>(List.of(exchange, deadLetters));
		addConsumerQueue(declarables, exchange, deadLetters, NOTIFICATIONS_QUEUE, notifications.eventTypes());
		addConsumerQueue(declarables, exchange, deadLetters, WEBHOOKS_QUEUE, webhooks.eventTypes());
		return new Declarables(declarables);
	}

	private static void addConsumerQueue(List<Declarable> declarables, TopicExchange exchange,
			DirectExchange deadLetters, String name, Set<String> eventTypes) {
		Queue queue = QueueBuilder.durable(name).deadLetterExchange(DEAD_LETTER_EXCHANGE).deadLetterRoutingKey(name)
				.build();
		Queue deadLetterQueue = QueueBuilder.durable(name + ".dlq").build();
		declarables.add(queue);
		declarables.add(deadLetterQueue);
		declarables.add(BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(name));
		eventTypes.forEach(type -> declarables.add(BindingBuilder.bind(queue).to(exchange).with(type)));
	}

}
