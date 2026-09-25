package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.task.dto.TaskCommentResponse;
import com.taskforge.task.events.TaskCommentAddedEvent;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class TaskCommentService {

	private final TaskRepository taskRepository;
	private final TaskCommentRepository taskCommentRepository;
	private final UserRepository userRepository;
	private final ApplicationEventPublisher eventPublisher;

	public TaskCommentService(TaskRepository taskRepository, TaskCommentRepository taskCommentRepository,
			UserRepository userRepository, ApplicationEventPublisher eventPublisher) {
		this.taskRepository = taskRepository;
		this.taskCommentRepository = taskCommentRepository;
		this.userRepository = userRepository;
		this.eventPublisher = eventPublisher;
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'MEMBER')")
	@Transactional
	public TaskCommentResponse addComment(UUID taskId, String body, UUID authorId) {
		Task task = findTaskOrThrow(taskId);
		User author = userRepository.findById(authorId).orElseThrow();

		TaskComment comment = taskCommentRepository.save(new TaskComment(task, author, body));
		eventPublisher.publishEvent(new TaskCommentAddedEvent(taskId, comment.getId(), authorId));

		return toResponse(comment);
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'MEMBER')")
	@Transactional(readOnly = true)
	public List<TaskCommentResponse> listComments(UUID taskId) {
		return taskCommentRepository.findByTaskIdWithAuthor(taskId).stream().map(this::toResponse).toList();
	}

	// Removes the comment through the parent Task's own collection rather than
	// a direct repository delete, so this exercises Task's orphanRemoval
	// mapping instead of bypassing it.
	@PreAuthorize("hasPermission(#commentId, 'TaskComment', 'DELETE')")
	@Transactional
	public void deleteComment(UUID taskId, UUID commentId) {
		Task task = findTaskOrThrow(taskId);
		TaskComment comment = taskCommentRepository.findByIdAndTask_Id(commentId, taskId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Comment not found"));

		task.getComments().remove(comment);
	}

	private Task findTaskOrThrow(UUID taskId) {
		return taskRepository.findById(taskId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Task not found"));
	}

	private TaskCommentResponse toResponse(TaskComment comment) {
		return new TaskCommentResponse(comment.getId(), comment.getAuthor().getId(), comment.getAuthor().getEmail(),
				comment.getBody(), comment.getCreatedAt());
	}

}
