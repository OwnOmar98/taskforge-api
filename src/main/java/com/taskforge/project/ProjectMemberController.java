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
import com.taskforge.project.dto.AddProjectMemberRequest;
import com.taskforge.project.dto.ChangeProjectMemberRoleRequest;
import com.taskforge.project.dto.ProjectMemberResponse;
import com.taskforge.security.CurrentUserId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Project Members")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/projects/{projectId}/members")
public class ProjectMemberController {

	private final ProjectMemberService projectMemberService;

	public ProjectMemberController(ProjectMemberService projectMemberService) {
		this.projectMemberService = projectMemberService;
	}

	@Operation(summary = "List members of a project")
	@GetMapping
	public PageResponse<ProjectMemberResponse> list(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return projectMemberService.listMembers(orgId, projectId, pageable);
	}

	@Operation(summary = "Add a member to a project")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectMemberResponse add(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@Valid @RequestBody AddProjectMemberRequest request, @CurrentUserId UUID currentUserId) {
		return projectMemberService.addMember(orgId, projectId, request.userId(), request.role(), currentUserId);
	}

	@Operation(summary = "Change a project member's role")
	@PatchMapping("/{userId}")
	public ProjectMemberResponse changeRole(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@PathVariable UUID userId, @Valid @RequestBody ChangeProjectMemberRoleRequest request,
			@CurrentUserId UUID currentUserId) {
		return projectMemberService.changeRole(orgId, projectId, userId, request.role(), currentUserId);
	}

	@Operation(summary = "Remove a member from a project")
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID orgId, @PathVariable UUID projectId, @PathVariable UUID userId,
			@CurrentUserId UUID currentUserId) {
		projectMemberService.removeMember(orgId, projectId, userId, currentUserId);
	}

}
