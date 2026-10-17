package com.taskforge.notification;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.messaging.DomainEvent;
import com.taskforge.messaging.DomainEventHandler;
import com.taskforge.messaging.DomainEventTypes;
import com.taskforge.messaging.ProcessedEvents;
import com.taskforge.messaging.TaskEventPayload;
import com.taskforge.notification.dto.NotificationResponse;
import com.taskforge.realtime.UserEventPublisher;

import tools.jackson.databind.ObjectMapper;

// Notifies a task's assignee about changes someone else made to it.
@Component
public class NotificationEventHandler implements DomainEventHandler {

	private static final String CONSUMER = "notifications";

	private final NotificationRepository notificationRepository;
	private final ProcessedEvents processedEvents;
	private final ObjectMapper objectMapper;
	private final UserEventPublisher userEventPublisher;

	public NotificationEventHandler(NotificationRepository notificationRepository, ProcessedEvents processedEvents,
			ObjectMapper objectMapper, UserEventPublisher userEventPublisher) {
		this.notificationRepository = notificationRepository;
		this.processedEvents = processedEvents;
		this.objectMapper = objectMapper;
		this.userEventPublisher = userEventPublisher;
	}

	@Override
	public String consumerName() {
		return CONSUMER;
	}

	@Override
	public Set<String> eventTypes() {
		return Set.of(DomainEventTypes.TASK_STATUS_CHANGED, DomainEventTypes.TASK_ASSIGNED,
				DomainEventTypes.TASK_COMMENT_ADDED);
	}

	@Override
	@Transactional
	public void handle(DomainEvent event) {
		TaskEventPayload task = objectMapper.treeToValue(event.data(), TaskEventPayload.class);
		if (task.assigneeId() == null || task.assigneeId().equals(task.actorId())) {
			return;
		}
		if (!processedEvents.markProcessed(CONSUMER, event.id())) {
			return;
		}

		switch (event.type()) {
			case DomainEventTypes.TASK_STATUS_CHANGED -> save(task, NotificationType.TASK_STATUS_CHANGED,
					Map.of("oldStatus", task.oldStatus(), "newStatus", task.newStatus()));
			case DomainEventTypes.TASK_ASSIGNED -> save(task, NotificationType.TASK_ASSIGNED,
					Map.of("taskId", task.taskId()));
			case DomainEventTypes.TASK_COMMENT_ADDED -> save(task, NotificationType.TASK_COMMENT_ADDED,
					Map.of("taskId", task.taskId(), "commentId", task.commentId()));
			default -> throw new IllegalArgumentException("Unexpected event type " + event.type());
		}
	}

	// The pushed response is built from the in-memory payload map rather
	// than read back from the saved row's JSON column - same content, without
	// a pointless serialize/parse round trip.
	private void save(TaskEventPayload task, NotificationType type, Map<String, ?> payload) {
		UUID userId = task.assigneeId();
		Notification notification = notificationRepository.save(new Notification(userId, task.organizationId(), type,
				objectMapper.writeValueAsString(payload)));
		userEventPublisher.publishAfterCommit(userId, NotificationReplaySource.toEvent(
				new NotificationResponse(notification.getId(), type, payload, null, notification.getCreatedAt())));
	}

}
