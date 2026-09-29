package com.taskforge.project;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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

import com.taskforge.common.PageResponse;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.project.dto.UpdateProjectRequest;
import com.taskforge.security.CurrentUserId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Projects")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/projects")
public class ProjectController {

	private final ProjectService projectService;

	public ProjectController(ProjectService projectService) {
		this.projectService = projectService;
	}

	@Operation(summary = "Create a project")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateProjectRequest request,
			@CurrentUserId UUID currentUserId) {
		return projectService.createProject(orgId, request.key(), request.name(), currentUserId);
	}

	@Operation(summary = "List projects in an organization")
	@GetMapping
	public PageResponse<ProjectResponse> list(@PathVariable UUID orgId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return projectService.listProjects(orgId, pageable);
	}

	@Operation(summary = "Get a project by id")
	@GetMapping("/{projectId}")
	public ProjectResponse get(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return projectService.getProject(orgId, projectId);
	}

	@Operation(summary = "Rename a project")
	@PatchMapping("/{projectId}")
	public ProjectResponse update(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@Valid @RequestBody UpdateProjectRequest request) {
		return projectService.updateProject(orgId, projectId, request.name(), request.version());
	}

	@Operation(summary = "Delete a project (restorable)")
	@DeleteMapping("/{projectId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID orgId, @PathVariable UUID projectId, @CurrentUserId UUID currentUserId) {
		projectService.deleteProject(orgId, projectId, currentUserId);
	}

	@Operation(summary = "Restore a deleted project")
	@PostMapping("/{projectId}/restore")
	public ProjectResponse restore(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@CurrentUserId UUID currentUserId) {
		return projectService.restoreProject(orgId, projectId, currentUserId);
	}

}
