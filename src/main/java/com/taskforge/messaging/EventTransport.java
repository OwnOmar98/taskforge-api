package com.taskforge.messaging;

import java.util.concurrent.CompletableFuture;

// Completes once the event is safely handed over (broker confirm, or every
// in-process handler done), and exceptionally if it wasn't.
public interface EventTransport {

	CompletableFuture<Void> publish(DomainEvent event);

}
