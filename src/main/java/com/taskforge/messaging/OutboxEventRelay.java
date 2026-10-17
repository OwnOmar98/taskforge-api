package com.taskforge.messaging;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.messaging.EventOutbox.ClaimedEvent;
import com.taskforge.messaging.EventOutbox.OutboxEventAppended;

import io.micrometer.core.instrument.MeterRegistry;

// Publishes outbox events in two ways: a nudge right after the transaction
// that wrote them commits (low latency), and a slow poll as the safety net for
// anything a nudge missed (a crash after commit, a failed publish's retry).
@Component
public class OutboxEventRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxEventRelay.class);

	private final EventOutbox outbox;
	private final EventTransport transport;
	private final EventsProperties.Outbox properties;
	private final Executor executor;
	private final MeterRegistry meterRegistry;
	// Coalesces nudges: a burst of commits queues one relay pass, not one each.
	private final AtomicBoolean nudgePending = new AtomicBoolean();

	public OutboxEventRelay(EventOutbox outbox, EventTransport transport, EventsProperties properties,
			@Qualifier("eventRelayExecutor") Executor executor, MeterRegistry meterRegistry) {
		this.outbox = outbox;
		this.transport = transport;
		this.properties = properties.outbox();
		this.executor = executor;
		this.meterRegistry = meterRegistry;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onAppended(OutboxEventAppended appended) {
		nudge();
	}

	@Scheduled(fixedDelayString = "${app.events.outbox.poll-interval}")
	void poll() {
		if (properties.pollEnabled()) {
			relayAll();
		}
	}

	void nudge() {
		if (!nudgePending.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(() -> {
				nudgePending.set(false);
				relayAll();
			});
		}
		catch (TaskRejectedException e) {
			nudgePending.set(false);
			log.debug("Outbox nudge rejected; the next poll will publish instead", e);
		}
	}

	// Public so tests can drive one cycle deterministically. Outcomes are
	// recorded here, on the relay thread, after waiting - not in a callback,
	// which for RabbitMQ would run JDBC on the AMQP connection's own thread.
	public int relayOnce() {
		List<ClaimedEvent> claimed = outbox.claimDue(properties.batchSize());
		List<CompletableFuture<Void>> publishes = claimed.stream().map(this::publish).toList();
		for (int i = 0; i < claimed.size(); i++) {
			record(claimed.get(i), failureOf(publishes.get(i)));
		}
		return claimed.size();
	}

	private void relayAll() {
		try {
			while (relayOnce() == properties.batchSize()) {
				// a full batch means there may be more waiting
			}
		}
		catch (RuntimeException e) {
			log.error("Outbox relay pass failed", e);
		}
	}

	private CompletableFuture<Void> publish(ClaimedEvent claimed) {
		try {
			return transport.publish(claimed.event());
		}
		catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	private static Throwable failureOf(CompletableFuture<Void> publish) {
		try {
			publish.join();
			return null;
		}
		catch (CompletionException | CancellationException e) {
			return e.getCause() != null ? e.getCause() : e;
		}
	}

	private void record(ClaimedEvent claimed, Throwable failure) {
		try {
			if (failure == null) {
				outbox.markPublished(claimed);
			}
			else {
				meterRegistry.counter("events.outbox.publish.failures").increment();
				log.warn("Publishing event {} ({}) failed on attempt {}: {}", claimed.event().id(),
						claimed.event().type(), claimed.attempt(), failure.toString());
				outbox.scheduleRetry(claimed, failure);
			}
		}
		catch (RuntimeException e) {
			// Left IN_FLIGHT, so it's reclaimed after the lease: a republish,
			// which consumers dedupe, never a lost event.
			log.error("Could not record the publish outcome of event {}", claimed.event().id(), e);
		}
	}

}
