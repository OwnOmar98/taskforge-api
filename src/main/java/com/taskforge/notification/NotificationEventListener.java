package com.taskforge.notification;

import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.task.Task;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.events.TaskAssignedEvent;
import com.taskforge.task.events.TaskCommentAddedEvent;
import com.taskforge.user.User;

import tools.jackson.databind.ObjectMapper;

// Same AFTER_COMMIT phase as AuditEventListener, for the same reason: firing
// before the enclosing transaction commits would race it - this listener
// re-queries the Task in its own transaction, and a plain @EventListener
// dispatched immediately at publish time could run before the original
// INSERT/UPDATE is even visible, silently finding nothing. The real
// difference from AuditEventListener is @Async: a notification is
// best-effort, not a compliance record, so it's fine for it to run on a
// background thread and occasionally be dropped if the executor's bounded
// queue is full. That's backwards for audit logging, which must stay
// synchronous so a failure to write it is never silently swallowed -
// exactly why TaskStatusChangedEvent has two listeners taking two different
// approaches to the same event.
@Component
public class NotificationEventListener {

	private final NotificationRepository notificationRepository;
	private final TaskRepository taskRepository;
	private final ObjectMapper objectMapper;

	public NotificationEventListener(NotificationRepository notificationRepository, TaskRepository taskRepository,
			ObjectMapper objectMapper) {
		this.notificationRepository = notificationRepository;
		this.taskRepository = taskRepository;
		this.objectMapper = objectMapper;
	}

	@Async("notificationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onTaskStatusChanged(TaskStatusChangedEvent event) {
		Task task = taskRepository.findById(event.taskId()).orElse(null);
		if (task == null) {
			return;
		}

		User assignee = task.getAssignee();
		if (assignee == null || assignee.getId().equals(event.actorId())) {
			return;
		}

		String payload = toJson(Map.of("oldStatus", event.oldStatus(), "newStatus", event.newStatus()));
		save(assignee.getId(), task.getProject().getOrganization().getId(), NotificationType.TASK_STATUS_CHANGED,
				payload);
	}

	@Async("notificationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onTaskAssigned(TaskAssignedEvent event) {
		if (event.assigneeId().equals(event.actorId())) {
			return;
		}

		Task task = taskRepository.findById(event.taskId()).orElse(null);
		if (task == null) {
			return;
		}

		String payload = toJson(Map.of("taskId", event.taskId()));
		save(event.assigneeId(), task.getProject().getOrganization().getId(), NotificationType.TASK_ASSIGNED,
				payload);
	}

	@Async("notificationExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onTaskCommentAdded(TaskCommentAddedEvent event) {
		Task task = taskRepository.findById(event.taskId()).orElse(null);
		if (task == null) {
			return;
		}

		User assignee = task.getAssignee();
		if (assignee == null || assignee.getId().equals(event.authorId())) {
			return;
		}

		String payload = toJson(Map.of("taskId", event.taskId(), "commentId", event.commentId()));
		save(assignee.getId(), task.getProject().getOrganization().getId(), NotificationType.TASK_COMMENT_ADDED,
				payload);
	}

	private void save(UUID userId, UUID organizationId, NotificationType type, String payload) {
		notificationRepository.save(new Notification(userId, organizationId, type, payload));
	}

	private String toJson(Map<String, ?> payload) {
		return objectMapper.writeValueAsString(payload);
	}

}
