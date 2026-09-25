package com.taskforge.task;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.common.PageResponse;
import com.taskforge.security.CurrentUserId;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.TaskFilter;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.TaskSummaryProjection;
import com.taskforge.task.dto.UpdateTaskRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/tasks")
public class TaskController {

	private final TaskService taskService;

	public TaskController(TaskService taskService) {
		this.taskService = taskService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TaskResponse create(@PathVariable UUID projectId, @Valid @RequestBody CreateTaskRequest request,
			@CurrentUserId UUID currentUserId) {
		return taskService.createTask(projectId, request.title(), request.description(), request.priority(),
				request.dueDate(), request.assigneeId(), currentUserId);
	}

	@GetMapping
	public PageResponse<TaskSummaryProjection> list(@PathVariable UUID projectId, @ModelAttribute TaskFilter filter,
			@PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return taskService.listTasks(projectId, filter.status(), filter.priority(), filter.assigneeId(),
				filter.labelId(), pageable);
	}

	@GetMapping("/{taskId}")
	public TaskResponse get(@PathVariable UUID projectId, @PathVariable UUID taskId) {
		return taskService.getTask(projectId, taskId);
	}

	@PatchMapping("/{taskId}")
	public TaskResponse update(@PathVariable UUID projectId, @PathVariable UUID taskId,
			@Valid @RequestBody UpdateTaskRequest request, @CurrentUserId UUID currentUserId) {
		return taskService.updateTask(projectId, taskId, request, currentUserId);
	}

	@DeleteMapping("/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID projectId, @PathVariable UUID taskId) {
		taskService.deleteTask(projectId, taskId);
	}

}
