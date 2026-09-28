package com.taskforge.security;

import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.MembershipRoleCacheService;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectMemberRoleCacheService;
import com.taskforge.project.ProjectRepository;
import com.taskforge.task.Task;
import com.taskforge.task.TaskComment;
import com.taskforge.task.TaskCommentRepository;
import com.taskforge.task.TaskRepository;

// The org/project role lookups below are the hottest path in the app - every
// @PreAuthorize check runs one - so they go through the cached lookup
// services rather than querying the repositories directly.
@Component
public class TaskForgePermissionEvaluator implements PermissionEvaluator {

	private final MembershipRoleCacheService membershipRoleCacheService;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRoleCacheService projectMemberRoleCacheService;
	private final TaskRepository taskRepository;
	private final TaskCommentRepository taskCommentRepository;

	public TaskForgePermissionEvaluator(MembershipRoleCacheService membershipRoleCacheService,
			ProjectRepository projectRepository, ProjectMemberRoleCacheService projectMemberRoleCacheService,
			TaskRepository taskRepository, TaskCommentRepository taskCommentRepository) {
		this.membershipRoleCacheService = membershipRoleCacheService;
		this.projectRepository = projectRepository;
		this.projectMemberRoleCacheService = projectMemberRoleCacheService;
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
			MembershipRole actualRole = membershipRoleCacheService.findRole(organizationId, userId).role();

			return actualRole != null && actualRole.isAtLeast(requiredRole);
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
		// "MEMBER" is read-only: any tier, including VIEWER, passes.
		if ("Project".equals(targetType) && "MEMBER".equals(permission)) {
			UUID projectId = UUID.fromString(targetId.toString());
			return projectMemberRoleCacheService.findRole(projectId, userId).role() != null;
		}

		// "CONTRIBUTE" is the write-access tier - VIEWER (read-only by design)
		// must not pass this, unlike "MEMBER" above.
		if ("Project".equals(targetType) && "CONTRIBUTE".equals(permission)) {
			UUID projectId = UUID.fromString(targetId.toString());
			ProjectMemberRole role = projectMemberRoleCacheService.findRole(projectId, userId).role();
			return role != null && role.isAtLeast(ProjectMemberRole.CONTRIBUTOR);
		}

		// Same "resolve up to the owning scope, then check membership there"
		// shape as canManageProject - comments/labels are addressed by taskId
		// alone, so this walks task -> project before it can check anything.
		if ("Task".equals(targetType) && "MEMBER".equals(permission)) {
			UUID taskId = UUID.fromString(targetId.toString());
			return canAccessTask(userId, taskId);
		}

		if ("Task".equals(targetType) && "CONTRIBUTE".equals(permission)) {
			UUID taskId = UUID.fromString(targetId.toString());
			return canContributeToTask(userId, taskId);
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

		return projectMemberRoleCacheService.findRole(task.getProject().getId(), userId).role() != null;
	}

	private boolean canContributeToTask(UUID userId, UUID taskId) {
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null) {
			return false;
		}

		ProjectMemberRole role = projectMemberRoleCacheService.findRole(task.getProject().getId(), userId).role();
		return role != null && role.isAtLeast(ProjectMemberRole.CONTRIBUTOR);
	}

	private boolean canManageProject(UUID userId, UUID projectId) {
		Project project = projectRepository.findById(projectId).orElse(null);
		if (project == null) {
			return false;
		}

		MembershipRole orgRole = membershipRoleCacheService.findRole(project.getOrganization().getId(), userId)
				.role();
		if (orgRole != null && orgRole.isAtLeast(MembershipRole.ADMIN)) {
			return true;
		}

		ProjectMemberRole projectRole = projectMemberRoleCacheService.findRole(projectId, userId).role();
		return projectRole != null && projectRole.isAtLeast(ProjectMemberRole.LEAD);
	}

}
