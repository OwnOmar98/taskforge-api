package com.taskforge.task;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tasks/{taskId}/labels")
public class TaskLabelController {

	private final LabelService labelService;

	public TaskLabelController(LabelService labelService) {
		this.labelService = labelService;
	}

	@PostMapping("/{labelId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void attach(@PathVariable UUID taskId, @PathVariable UUID labelId) {
		labelService.attachLabel(taskId, labelId);
	}

	@DeleteMapping("/{labelId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void detach(@PathVariable UUID taskId, @PathVariable UUID labelId) {
		labelService.detachLabel(taskId, labelId);
	}

}
