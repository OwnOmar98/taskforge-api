package com.taskforge.integration;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.messaging.DomainEvent;
import com.taskforge.messaging.DomainEventHandler;
import com.taskforge.messaging.DomainEventTypes;
import com.taskforge.messaging.ProcessedEvents;
import com.taskforge.messaging.TaskEventPayload;
import com.taskforge.task.TaskStatus;

import tools.jackson.databind.ObjectMapper;

// Turns a completed task into one webhook_deliveries row per organization
// webhook. The HTTP send, its retries and the delivery log stay with
// WebhookOutboxRelay; this consumer only decides what needs delivering.
@Component
public class WebhookEventHandler implements DomainEventHandler {

	private static final String CONSUMER = "webhooks";

	private final WebhookRepository webhookRepository;
	private final WebhookOutbox outbox;
	private final ProcessedEvents processedEvents;
	private final ObjectMapper objectMapper;

	public WebhookEventHandler(WebhookRepository webhookRepository, WebhookOutbox outbox,
			ProcessedEvents processedEvents, ObjectMapper objectMapper) {
		this.webhookRepository = webhookRepository;
		this.outbox = outbox;
		this.processedEvents = processedEvents;
		this.objectMapper = objectMapper;
	}

	@Override
	public String consumerName() {
		return CONSUMER;
	}

	@Override
	public Set<String> eventTypes() {
		return Set.of(DomainEventTypes.TASK_STATUS_CHANGED);
	}

	@Override
	@Transactional
	public void handle(DomainEvent event) {
		TaskEventPayload task = objectMapper.treeToValue(event.data(), TaskEventPayload.class);
		if (task.newStatus() != TaskStatus.DONE || !processedEvents.markProcessed(CONSUMER, event.id())) {
			return;
		}

		List<Webhook> webhooks = webhookRepository.findByOrganizationId(task.organizationId());
		if (webhooks.isEmpty()) {
			return;
		}

		// The receiver-facing payload is a public contract: unchanged from
		// before the broker existed.
		String payload = objectMapper.writeValueAsString(
				Map.of("event", "task.completed", "taskId", task.taskId(), "organizationId", task.organizationId()));
		for (Webhook webhook : webhooks) {
			outbox.enqueue(webhook, "task.completed", payload);
		}
	}

}
