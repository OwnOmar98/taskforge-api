package com.taskforge.messaging;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

// An event only counts as published once the broker confirms it (publisher
// confirms) and it was routed to at least one queue (mandatory + returns).
// Without both, a send to a broker that drops it - or to an exchange with no
// matching binding - would "succeed" and the outbox row would be marked
// PUBLISHED for an event nobody will ever receive.
@Component
@ConditionalOnProperty(name = "app.events.transport", havingValue = "rabbitmq")
public class RabbitEventTransport implements EventTransport {

	private static final long CONFIRM_TIMEOUT_SECONDS = 10;

	private final RabbitTemplate rabbitTemplate;
	private final ObjectMapper objectMapper;

	public RabbitEventTransport(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
		this.rabbitTemplate = rabbitTemplate;
		this.objectMapper = objectMapper;
		// Returns are read from each send's CorrelationData instead; this only
		// stops the template warning that nothing else is listening for them.
		rabbitTemplate.setReturnsCallback(returned -> {
		});
	}

	@Override
	public CompletableFuture<Void> publish(DomainEvent event) {
		Message message = MessageBuilder.withBody(objectMapper.writeValueAsBytes(event))
				.setContentType(MessageProperties.CONTENT_TYPE_JSON)
				.setMessageId(event.id().toString())
				.setType(event.type())
				.setDeliveryMode(MessageDeliveryMode.PERSISTENT)
				.build();
		CorrelationData correlation = new CorrelationData(event.id().toString());
		rabbitTemplate.send(RabbitTopology.EXCHANGE, event.type(), message, correlation);

		// The broker reports a return before the confirm, so by the time the
		// confirm completes getReturned() is already set for an unroutable one.
		return correlation.getFuture().orTimeout(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS).thenAccept(confirm -> {
			if (!confirm.ack()) {
				throw new IllegalStateException("Broker rejected event " + event.id() + ": " + confirm.reason());
			}
			if (correlation.getReturned() != null) {
				throw new IllegalStateException("No queue is bound for event type " + event.type() + " ("
						+ correlation.getReturned().getReplyText() + ")");
			}
		});
	}

}
