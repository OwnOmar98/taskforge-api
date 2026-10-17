package com.taskforge.messaging;

import java.util.Set;

// A consumer of domain events. Implementations know nothing about the
// transport: the RabbitMQ listeners and InProcessEventTransport both call
// handle(). Each consumer gets its own queue, so one failing doesn't hold up
// the others.
public interface DomainEventHandler {

	String consumerName();

	Set<String> eventTypes();

	void handle(DomainEvent event);

}
