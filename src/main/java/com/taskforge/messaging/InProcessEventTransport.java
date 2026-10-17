package com.taskforge.messaging;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Every handler runs (each in its own transaction) even if an earlier one
// failed; a failure sends the whole event back for a retry, and the handlers
// that already succeeded skip it through ProcessedEvents.
@Component
@ConditionalOnProperty(name = "app.events.transport", havingValue = "in-process")
public class InProcessEventTransport implements EventTransport {

	private final List<DomainEventHandler> handlers;

	public InProcessEventTransport(List<DomainEventHandler> handlers) {
		this.handlers = handlers;
	}

	@Override
	public CompletableFuture<Void> publish(DomainEvent event) {
		RuntimeException firstFailure = null;
		for (DomainEventHandler handler : handlers) {
			if (!handler.eventTypes().contains(event.type())) {
				continue;
			}
			try {
				handler.handle(event);
			}
			catch (RuntimeException e) {
				if (firstFailure == null) {
					firstFailure = e;
				}
				else {
					firstFailure.addSuppressed(e);
				}
			}
		}
		return firstFailure == null ? CompletableFuture.completedFuture(null)
				: CompletableFuture.failedFuture(firstFailure);
	}

}
