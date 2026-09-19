package com.taskforge.project;

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

import com.taskforge.project.dto.AddProjectMemberRequest;
import com.taskforge.project.dto.ChangeProjectMemberRoleRequest;
import com.taskforge.project.dto.ProjectMemberResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/projects/{projectId}/members")
public class ProjectMemberController {

	private final ProjectMemberService projectMemberService;

	public ProjectMemberController(ProjectMemberService projectMemberService) {
		this.projectMemberService = projectMemberService;
	}

	@GetMapping
	public List<ProjectMemberResponse> list(@PathVariable UUID orgId, @PathVariable UUID projectId) {
		return projectMemberService.listMembers(orgId, projectId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ProjectMemberResponse add(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@Valid @RequestBody AddProjectMemberRequest request) {
		return projectMemberService.addMember(orgId, projectId, request.userId(), request.role());
	}

	@PatchMapping("/{userId}")
	public ProjectMemberResponse changeRole(@PathVariable UUID orgId, @PathVariable UUID projectId,
			@PathVariable UUID userId, @Valid @RequestBody ChangeProjectMemberRoleRequest request) {
		return projectMemberService.changeRole(orgId, projectId, userId, request.role());
	}

	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID orgId, @PathVariable UUID projectId, @PathVariable UUID userId) {
		projectMemberService.removeMember(orgId, projectId, userId);
	}

}
