package com.taskforge.task;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.common.PageResponse;
import com.taskforge.task.dto.CreateLabelRequest;
import com.taskforge.task.dto.LabelResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Labels")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/labels")
public class LabelController {

	private final LabelService labelService;

	public LabelController(LabelService labelService) {
		this.labelService = labelService;
	}

	@Operation(summary = "Create a label")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public LabelResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateLabelRequest request) {
		return labelService.createLabel(orgId, request.name());
	}

	@Operation(summary = "List labels in an organization")
	@GetMapping
	public PageResponse<LabelResponse> list(@PathVariable UUID orgId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return labelService.listLabels(orgId, pageable);
	}

}
