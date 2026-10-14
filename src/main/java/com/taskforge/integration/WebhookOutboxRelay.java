package com.taskforge.integration;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.taskforge.integration.WebhookOutbox.ClaimedDelivery;

// Polls the outbox and sends what's due. Safe to run on every instance at
// once - claiming uses SKIP LOCKED, so instances split the work instead of
// duplicating it, with no distributed lock needed.
@Component
public class WebhookOutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(WebhookOutboxRelay.class);

	private final WebhookOutbox outbox;
	private final WebhookSender webhookSender;
	private final WebhookOutboxProperties properties;

	public WebhookOutboxRelay(WebhookOutbox outbox, WebhookSender webhookSender, WebhookOutboxProperties properties) {
		this.outbox = outbox;
		this.webhookSender = webhookSender;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.webhook.outbox.poll-interval}")
	void poll() {
		if (properties.relayEnabled()) {
			relayOnce();
		}
	}

	// Waits for the whole batch before returning: fixedDelay then measures
	// from the end of a batch, so a slow batch is never overlapped by the
	// next poll on the same instance. Public so tests can drive one cycle
	// deterministically instead of waiting on the schedule.
	public int relayOnce() {
		List<ClaimedDelivery> claimed = outbox.claimDue(properties.batchSize());
		CompletableFuture<?>[] sends = claimed.stream().map(this::send).toArray(CompletableFuture[]::new);
		CompletableFuture.allOf(sends).join();
		return claimed.size();
	}

	private CompletableFuture<Void> send(ClaimedDelivery delivery) {
		return webhookSender.send(delivery.webhook(), delivery.payload(), delivery.idempotencyKey().toString())
				.handle((ignored, failure) -> {
					try {
						if (failure == null) {
							outbox.recordSuccess(delivery);
						}
						else {
							outbox.recordFailure(delivery, failure);
						}
					}
					catch (RuntimeException e) {
						// The row stays IN_FLIGHT and is reclaimed once its lease
						// expires - a lost status update means a resend, never a
						// lost delivery.
						log.error("Could not record the outcome of webhook delivery {}", delivery.deliveryId(), e);
					}
					return null;
				});
	}

}
