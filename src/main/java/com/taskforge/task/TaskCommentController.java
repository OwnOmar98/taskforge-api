package com.taskforge.task;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.common.PageResponse;
import com.taskforge.security.CurrentUserId;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.dto.TaskCommentResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Task Comments")
@RestController
@RequestMapping("/api/v1/tasks/{taskId}/comments")
public class TaskCommentController {

	private final TaskCommentService taskCommentService;

	public TaskCommentController(TaskCommentService taskCommentService) {
		this.taskCommentService = taskCommentService;
	}

	@Operation(summary = "Add a comment to a task")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskCommentResponse create(@PathVariable UUID taskId, @Valid @RequestBody CreateCommentRequest request,
			@CurrentUserId UUID currentUserId) {
		return taskCommentService.addComment(taskId, request.body(), currentUserId);
	}

	@Operation(summary = "List comments on a task")
	@GetMapping
	public PageResponse<TaskCommentResponse> list(@PathVariable UUID taskId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return taskCommentService.listComments(taskId, pageable);
	}

	@Operation(summary = "Delete a comment")
	@DeleteMapping("/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID taskId, @PathVariable UUID commentId) {
		taskCommentService.deleteComment(taskId, commentId);
	}

}
