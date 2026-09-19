package com.taskforge.project;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.project.dto.UpdateProjectRequest;
import com.taskforge.security.CurrentUserId;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/projects")
public class ProjectController {

	private final ProjectService projectService;

	public ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateProjectRequest request,
			@CurrentUserId UUID currentUserId) {
		return projectService.createProject(orgId, request.key(), request.name(), currentUserId);
	}

	@GetMapping
	public List<ProjectResponse> list(@PathVariable UUID orgId) {
		return projectService.listProjects(orgId);
	}

	@GetMapping("/{projectId}")
	public ProjectResponse get(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return projectService.getProject(orgId, projectId);
	}

	@PatchMapping("/{projectId}")
	public ProjectResponse update(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@Valid @RequestBody UpdateProjectRequest request) {
		return projectService.updateProject(orgId, projectId, request.name(), request.version());
	}

}
