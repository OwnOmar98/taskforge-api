package com.taskforge.project;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.project.dto.ProjectMemberResponse;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class ProjectMemberService {

	private final ProjectService projectService;
	private final ProjectMemberRepository projectMemberRepository;
	private final MembershipRepository membershipRepository;
	private final UserRepository userRepository;

	public ProjectMemberService(ProjectService projectService, ProjectMemberRepository projectMemberRepository,
			MembershipRepository membershipRepository, UserRepository userRepository) {
		this.projectService = projectService;
		this.projectMemberRepository = projectMemberRepository;
		this.membershipRepository = membershipRepository;
		this.userRepository = userRepository;
	}

	// No @PreAuthorize: TenantInterceptor already requires org membership for
	// any {orgId} route, and seeing who's on a project isn't a privileged
	// action on top of that - same reasoning as org member listing.
	@Transactional(readOnly = true)
	public List<ProjectMemberResponse> listMembers(UUID organizationId, UUID projectId) {
		projectService.findProjectInOrgOrThrow(organizationId, projectId);
		return projectMemberRepository.findByProjectIdWithUser(projectId).stream().map(this::toResponse).toList();
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MANAGE')")
	@Transactional
	public ProjectMemberResponse addMember(UUID organizationId, UUID projectId, UUID targetUserId,
			ProjectMemberRole role) {
		Project project = projectService.findProjectInOrgOrThrow(organizationId, projectId);

		boolean isOrgMember = membershipRepository.findByOrganization_IdAndUser_Id(organizationId, targetUserId)
				.isPresent();
		if (!isOrgMember) {
			throw new ConflictException(ProjectErrorCode.NOT_AN_ORGANIZATION_MEMBER,
					ProjectErrorCode.NOT_AN_ORGANIZATION_MEMBER.defaultMessage());
		}

		if (projectMemberRepository.findByProject_IdAndUser_Id(projectId, targetUserId).isPresent()) {
			throw new ConflictException(ProjectErrorCode.ALREADY_PROJECT_MEMBER,
					ProjectErrorCode.ALREADY_PROJECT_MEMBER.defaultMessage());
		}

		User targetUser = userRepository.findById(targetUserId).orElseThrow();
		ProjectMember member = projectMemberRepository.save(new ProjectMember(project, targetUser, role));
		return toResponse(member);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MANAGE')")
	@Transactional
	public ProjectMemberResponse changeRole(UUID organizationId, UUID projectId, UUID targetUserId,
			ProjectMemberRole newRole) {
		projectService.findProjectInOrgOrThrow(organizationId, projectId);
		ProjectMember member = findMemberOrThrow(projectId, targetUserId);
		member.changeRole(newRole);
		return toResponse(member);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MANAGE')")
	@Transactional
	public void removeMember(UUID organizationId, UUID projectId, UUID targetUserId) {
		projectService.findProjectInOrgOrThrow(organizationId, projectId);
		projectMemberRepository.delete(findMemberOrThrow(projectId, targetUserId));
	}

	private ProjectMember findMemberOrThrow(UUID projectId, UUID userId) {
		return projectMemberRepository.findByProject_IdAndUser_Id(projectId, userId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Project membership not found"));
	}

	private ProjectMemberResponse toResponse(ProjectMember member) {
		return new ProjectMemberResponse(member.getUser().getId(), member.getUser().getEmail(),
				member.getUser().getFullName(), member.getRole());
	}

}
