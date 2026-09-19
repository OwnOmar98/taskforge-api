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
import com.taskforge.task.Task;
import com.taskforge.task.TaskComment;
import com.taskforge.task.TaskCommentRepository;
import com.taskforge.task.TaskRepository;

@Component
public class TaskForgePermissionEvaluator implements PermissionEvaluator {

	private final MembershipRepository membershipRepository;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;
	private final TaskRepository taskRepository;
	private final TaskCommentRepository taskCommentRepository;

	public TaskForgePermissionEvaluator(MembershipRepository membershipRepository,
			ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
			TaskRepository taskRepository, TaskCommentRepository taskCommentRepository) {
		this.membershipRepository = membershipRepository;
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
		this.taskRepository = taskRepository;
		this.taskCommentRepository = taskCommentRepository;
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

		// Same "resolve up to the owning scope, then check membership there"
		// shape as canManageProject - comments/labels are addressed by taskId
		// alone, so this walks task -> project before it can check anything.
		if ("Task".equals(targetType) && "MEMBER".equals(permission)) {
			UUID taskId = UUID.fromString(targetId.toString());
			return canAccessTask(userId, taskId);
		}

		// Deleting a comment is allowed for its own author, or as a moderation
		// action by anyone who could already MANAGE the project - no separate
		// "comment moderator" role, just the same composite rule reused.
		if ("TaskComment".equals(targetType) && "DELETE".equals(permission)) {
			UUID commentId = UUID.fromString(targetId.toString());
			return canDeleteComment(userId, commentId);
		}

		return false;
	}

	private boolean canDeleteComment(UUID userId, UUID commentId) {
		TaskComment comment = taskCommentRepository.findById(commentId).orElse(null);
		if (comment == null) {
			return false;
		}

		if (comment.getAuthor().getId().equals(userId)) {
			return true;
		}

		// comment.getTask() is a lazy proxy - .getId() on it is safe (Hibernate
		// resolves that from the FK column alone), but .getProject() on that same
		// proxy would need a real field and therefore a session, which doesn't
		// exist yet here (@PreAuthorize runs before @Transactional opens one).
		// Re-fetching gives a real entity whose own lazy .getProject().getId()
		// is the same safe id-only access canManageProject already relies on.
		Task task = taskRepository.findById(comment.getTask().getId()).orElse(null);
		if (task == null) {
			return false;
		}

		return canManageProject(userId, task.getProject().getId());
	}

	private boolean canAccessTask(UUID userId, UUID taskId) {
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null) {
			return false;
		}

		return projectMemberRepository.findByProject_IdAndUser_Id(task.getProject().getId(), userId).isPresent();
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
