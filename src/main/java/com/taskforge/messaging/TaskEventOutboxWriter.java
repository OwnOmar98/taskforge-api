package com.taskforge.messaging;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.TaskStatusChangedEvent;
import com.taskforge.task.Task;
import com.taskforge.task.TaskRepository;
import com.taskforge.task.events.TaskAssignedEvent;
import com.taskforge.task.events.TaskCommentAddedEvent;
import com.taskforge.user.User;

// BEFORE_COMMIT and synchronous, so the outbox row commits or rolls back with
// the task change. The task is still in the persistence context here, so
// reading its assignee and organization costs no extra query.
@Component
public class TaskEventOutboxWriter {

	private final EventOutbox outbox;
	private final TaskRepository taskRepository;

	public TaskEventOutboxWriter(EventOutbox outbox, TaskRepository taskRepository) {
		this.outbox = outbox;
		this.taskRepository = taskRepository;
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void onTaskStatusChanged(TaskStatusChangedEvent event) {
		UUID assigneeId = taskRepository.findById(event.taskId()).map(Task::getAssignee).map(User::getId).orElse(null);
		outbox.append(DomainEventTypes.TASK_STATUS_CHANGED, new TaskEventPayload(event.taskId(),
				event.organizationId(), event.actorId(), assigneeId, event.oldStatus(), event.newStatus(), null));
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void onTaskAssigned(TaskAssignedEvent event) {
		taskRepository.findById(event.taskId())
				.ifPresent(task -> outbox.append(DomainEventTypes.TASK_ASSIGNED,
						new TaskEventPayload(event.taskId(), organizationId(task), event.actorId(),
								event.assigneeId(), null, null, null)));
	}

	@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
	public void onTaskCommentAdded(TaskCommentAddedEvent event) {
		taskRepository.findById(event.taskId())
				.ifPresent(task -> outbox.append(DomainEventTypes.TASK_COMMENT_ADDED,
						new TaskEventPayload(event.taskId(), organizationId(task), event.authorId(),
								task.getAssignee() == null ? null : task.getAssignee().getId(), null, null,
								event.commentId())));
	}

	private static UUID organizationId(Task task) {
		return task.getProject().getOrganization().getId();
	}

}
