package com.taskforge.integration;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.task.TaskStatus;

import tools.jackson.databind.ObjectMapper;

// BEFORE_COMMIT, synchronous: the outbox row is written inside the very
// transaction that changed the task, so the two commit or roll back together.
// The previous design (AFTER_COMMIT + @Async, sending straight away) could
// lose a delivery outright - a crash, deploy, or full executor queue between
// the commit and the send left a completed task with no webhook ever sent,
// and nothing recorded to retry it from. Sending now happens later, in
// WebhookOutboxRelay, from durable rows.
@Component
public class WebhookEventListener {

	private final WebhookRepository webhookRepository;
	private final WebhookOutbox outbox;
	private final ObjectMapper objectMapper;

	public WebhookEventListener(WebhookRepository webhookRepository, WebhookOutbox outbox, ObjectMapper objectMapper) {
		this.webhookRepository = webhookRepository;
		this.outbox = outbox;
		this.objectMapper = objectMapper;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void onTaskStatusChanged(TaskStatusChangedEvent event) {
		if (event.newStatus() != TaskStatus.DONE) {
			return;
		}

		List<Webhook> webhooks = webhookRepository.findByOrganizationId(event.organizationId());
		if (webhooks.isEmpty()) {
			return;
		}

		String payload = objectMapper.writeValueAsString(
				Map.of("event", "task.completed", "taskId", event.taskId(), "organizationId", event.organizationId()));
		for (Webhook webhook : webhooks) {
			outbox.enqueue(webhook, "task.completed", payload);
		}
	}

}
