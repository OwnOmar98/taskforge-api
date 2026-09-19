package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.task.dto.TaskResponse;
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
	public TaskResponse create(@PathVariable UUID projectId, @Valid @RequestBody CreateTaskRequest request) {
		return taskService.createTask(projectId, request.title(), request.description(), request.priority(),
				request.dueDate(), request.assigneeId());
	}

	@GetMapping
	public List<TaskResponse> list(@PathVariable UUID projectId) {
		return taskService.listTasks(projectId);
	}

	@GetMapping("/{taskId}")
	public TaskResponse get(@PathVariable UUID projectId, @PathVariable UUID taskId) {
		return taskService.getTask(projectId, taskId);
	}

	@PatchMapping("/{taskId}")
	public TaskResponse update(@PathVariable UUID projectId, @PathVariable UUID taskId,
			@Valid @RequestBody UpdateTaskRequest request) {
		return taskService.updateTask(projectId, taskId, request);
	}

	@DeleteMapping("/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID projectId, @PathVariable UUID taskId) {
		taskService.deleteTask(projectId, taskId);
	}

}
