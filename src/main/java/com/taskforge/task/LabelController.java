package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.task.dto.CreateLabelRequest;
import com.taskforge.task.dto.LabelResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/labels")
public class LabelController {

	private final LabelService labelService;

	public LabelController(LabelService labelService) {
		this.labelService = labelService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public LabelResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateLabelRequest request) {
		return labelService.createLabel(orgId, request.name());
	}

	@GetMapping
	public List<LabelResponse> list(@PathVariable UUID orgId) {
		return labelService.listLabels(orgId);
	}

}
