package com.taskforge.security;

import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectRepository;

@Component
public class TaskForgePermissionEvaluator implements PermissionEvaluator {

	private final MembershipRepository membershipRepository;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;

	public TaskForgePermissionEvaluator(MembershipRepository membershipRepository,
			ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository) {
		this.membershipRepository = membershipRepository;
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
	}

	@Override
	public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
		return false;
	}

	@Override
	public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
			Object permission) {
		UUID userId = (UUID) authentication.getPrincipal();

		if ("Organization".equals(targetType)) {
			UUID organizationId = UUID.fromString(targetId.toString());
			MembershipRole requiredRole = MembershipRole.valueOf(permission.toString());

			return membershipRepository.findByOrganization_IdAndUser_Id(organizationId, userId)
					.map(membership -> membership.getRole().isAtLeast(requiredRole))
					.orElse(false);
		}

		// "MANAGE" rather than a role name: this isn't a single role-hierarchy
		// lookup like Organization above, it's the composite rule "org ADMIN+ OR
		// project LEAD", so the permission string names the business rule, not a
		// role to match.
		if ("Project".equals(targetType) && "MANAGE".equals(permission)) {
			UUID projectId = UUID.fromString(targetId.toString());
			return canManageProject(userId, projectId);
		}

		// Tasks have no {orgId} in their path, so TenantInterceptor never runs
		// for them - this is the only gate standing between a non-member and
		// task data, unlike Organization/Project routes where it's a backstop.
		if ("Project".equals(targetType) && "MEMBER".equals(permission)) {
			UUID projectId = UUID.fromString(targetId.toString());
			return projectMemberRepository.findByProject_IdAndUser_Id(projectId, userId).isPresent();
		}

		return false;
	}

	private boolean canManageProject(UUID userId, UUID projectId) {
		Project project = projectRepository.findById(projectId).orElse(null);
		if (project == null) {
			return false;
		}

		boolean isOrgAdmin = membershipRepository
				.findByOrganization_IdAndUser_Id(project.getOrganization().getId(), userId)
				.map(membership -> membership.getRole().isAtLeast(MembershipRole.ADMIN))
				.orElse(false);

		if (isOrgAdmin) {
			return true;
		}

		return projectMemberRepository.findByProject_IdAndUser_Id(projectId, userId)
				.map(projectMember -> projectMember.getRole().isAtLeast(ProjectMemberRole.LEAD))
				.orElse(false);
	}

}
