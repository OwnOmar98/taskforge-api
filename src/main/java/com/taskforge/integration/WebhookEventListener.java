package com.taskforge.integration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.task.TaskStatus;

import tools.jackson.databind.ObjectMapper;

// TaskStatusChangedEvent's third listener, alongside AuditEventListener
// (sync) and NotificationEventListener (async): same AFTER_COMMIT reasoning
// as both of those (don't fire for a status change that gets rolled back),
// and async for the same reason as notifications - a third party's endpoint
// being slow must never slow down the request that changed the task.
@Component
public class WebhookEventListener {

	private final WebhookRepository webhookRepository;
	private final WebhookSender webhookSender;
	private final ObjectMapper objectMapper;

	public WebhookEventListener(WebhookRepository webhookRepository, WebhookSender webhookSender,
			ObjectMapper objectMapper) {
		this.webhookRepository = webhookRepository;
		this.webhookSender = webhookSender;
		this.objectMapper = objectMapper;
	}

	@Async("webhookExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
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
			// One key per delivery, reused across that delivery's own retries -
			// not a new key per HTTP attempt - so the receiving end can
			// correctly treat retries of the same delivery as duplicates of
			// each other, contrasted with PR23's receiver-side idempotency.
			String idempotencyKey = UUID.randomUUID().toString();
			webhookSender.send(webhook, payload, idempotencyKey);
		}
	}

}
