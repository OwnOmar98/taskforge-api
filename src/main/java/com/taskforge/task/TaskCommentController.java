package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.security.CurrentUserId;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.dto.TaskCommentResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/comments")
public class TaskCommentController {

	private final TaskCommentService taskCommentService;

	public TaskCommentController(TaskCommentService taskCommentService) {
		this.taskCommentService = taskCommentService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskCommentResponse create(@PathVariable UUID taskId, @Valid @RequestBody CreateCommentRequest request,
			@CurrentUserId UUID currentUserId) {
		return taskCommentService.addComment(taskId, request.body(), currentUserId);
	}

	@GetMapping
	public List<TaskCommentResponse> list(@PathVariable UUID taskId) {
		return taskCommentService.listComments(taskId);
	}

	@DeleteMapping("/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID taskId, @PathVariable UUID commentId) {
		taskCommentService.deleteComment(taskId, commentId);
	}

}
