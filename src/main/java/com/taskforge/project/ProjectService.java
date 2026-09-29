package com.taskforge.project;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.events.EntityDeletedEvent;
import com.taskforge.audit.events.EntityRestoredEvent;
import com.taskforge.common.AfterCommit;
import com.taskforge.common.PageResponse;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class ProjectService {

	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;
	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final ProjectMapper projectMapper;
	private final ProjectMemberRoleCacheService projectMemberRoleCacheService;
	private final ApplicationEventPublisher eventPublisher;

	public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
			OrganizationRepository organizationRepository, UserRepository userRepository, ProjectMapper projectMapper,
			ProjectMemberRoleCacheService projectMemberRoleCacheService, ApplicationEventPublisher eventPublisher) {
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
		this.projectMapper = projectMapper;
		this.projectMemberRoleCacheService = projectMemberRoleCacheService;
		this.eventPublisher = eventPublisher;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public ProjectResponse createProject(UUID organizationId, String key, String name, UUID creatorUserId) {
		String normalizedKey = normalizeKey(key);

		if (projectRepository.existsByOrganization_IdAndKey(organizationId, normalizedKey)) {
			throw new ConflictException(ProjectErrorCode.PROJECT_KEY_IN_USE,
					ProjectErrorCode.PROJECT_KEY_IN_USE.defaultMessage());
		}

		Organization organization = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Organization not found"));

		Project project;
		try {
			// The check above is a point-in-time read, not a lock - two concurrent
			// creates for the same org+key can both pass it before either commits.
			// saveAndFlush forces the insert (and the unique constraint it can
			// violate) to happen synchronously here, not deferred to commit,
			// where this catch couldn't see it.
			project = projectRepository.saveAndFlush(new Project(organization, normalizedKey, name));
		}
		catch (DataIntegrityViolationException e) {
			throw new ConflictException(ProjectErrorCode.PROJECT_KEY_IN_USE,
					ProjectErrorCode.PROJECT_KEY_IN_USE.defaultMessage());
		}

		User creator = userRepository.findById(creatorUserId).orElseThrow();
		projectMemberRepository.save(new ProjectMember(project, creator, ProjectMemberRole.LEAD));

		// No cache eviction needed, same reasoning as OrganizationService.
		// createOrganization: project.getId() has never existed before this
		// line, so no prior cache entry for this pair could exist to invalidate.
		return projectMapper.toResponse(project);
	}

	@Transactional(readOnly = true)
	public PageResponse<ProjectResponse> listProjects(UUID organizationId, Pageable pageable) {
		Page<Project> page = projectRepository.findByOrganization_Id(organizationId, pageable);
		return PageResponse.from(page.map(projectMapper::toResponse));
	}

	@Transactional(readOnly = true)
	public ProjectResponse getProject(UUID organizationId, UUID projectId) {
		return projectMapper.toResponse(findProjectInOrgOrThrow(organizationId, projectId));
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MANAGE')")
	@Transactional
	public ProjectResponse updateProject(UUID organizationId, UUID projectId, String newName, long expectedVersion) {
		// hasPermission above only knows the project's real organization, not
		// which orgId the caller used in the URL - this lookup is what actually
		// stops a cross-org projectId from being mutated, regardless of what the
		// permission check decided.
		Project project = findProjectInOrgOrThrow(organizationId, projectId);

		if (project.getVersion() != expectedVersion) {
			throw new ConflictException(ProjectErrorCode.STALE_PROJECT_VERSION,
					ProjectErrorCode.STALE_PROJECT_VERSION.defaultMessage());
		}

		project.rename(newName);
		// Hibernate only bumps @Version on flush, which otherwise wouldn't
		// happen until this transaction commits - flushing now so the response
		// reflects the incremented version instead of the stale in-memory one.
		projectRepository.flush();
		return projectMapper.toResponse(project);
	}

	@PreAuthorize("hasPermission(#projectId, 'Project', 'MANAGE')")
	@Transactional
	public void deleteProject(UUID organizationId, UUID projectId, UUID actorId) {
		Project project = findProjectInOrgOrThrow(organizationId, projectId);
		project.markDeleted(actorId);

		evictMemberRolesAfterCommit(projectId);
		eventPublisher.publishEvent(new EntityDeletedEvent(organizationId, actorId, "Project", projectId,
				Map.of("key", project.getKey(), "name", project.getName())));
	}

	// Org ADMIN, not the MANAGE rule deleting uses: MANAGE also admits the
	// project's LEAD, but a deleted project has no members for authorization
	// purposes (see ProjectMemberRepository.findActiveMembership), so a LEAD
	// could never pass it here anyway.
	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public ProjectResponse restoreProject(UUID organizationId, UUID projectId, UUID actorId) {
		Project project = projectRepository.findDeletedByIdAndOrganizationId(projectId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Deleted project not found"));

		// Only live projects hold a key (see V16's partial unique index), so a
		// new project may have taken this one while it was deleted.
		if (projectRepository.existsByOrganization_IdAndKey(organizationId, project.getKey())) {
			throw new ConflictException(ProjectErrorCode.PROJECT_KEY_IN_USE,
					ProjectErrorCode.PROJECT_KEY_IN_USE.defaultMessage());
		}

		project.restore();
		try {
			// Same check-then-act gap as createProject: the flush is what lets a
			// concurrent create that slipped past the check above surface here.
			projectRepository.flush();
		}
		catch (DataIntegrityViolationException e) {
			throw new ConflictException(ProjectErrorCode.PROJECT_KEY_IN_USE,
					ProjectErrorCode.PROJECT_KEY_IN_USE.defaultMessage());
		}

		evictMemberRolesAfterCommit(projectId);
		eventPublisher.publishEvent(new EntityRestoredEvent(organizationId, actorId, "Project", projectId,
				Map.of("key", project.getKey(), "name", project.getName())));
		return projectMapper.toResponse(project);
	}

	// Every member's cached role has to go, in both directions: after a delete
	// a cached role would keep the project reachable until TTL expiry (the
	// security bug the role cache's own eviction rule exists to prevent); after
	// a restore, a cached "no role" would keep locking members out.
	private void evictMemberRolesAfterCommit(UUID projectId) {
		List<UUID> memberIds = projectMemberRepository.findUserIdsByProjectId(projectId);
		AfterCommit.run(() -> memberIds.forEach(userId -> projectMemberRoleCacheService.evict(projectId, userId)));
	}

	Project findProjectInOrgOrThrow(UUID organizationId, UUID projectId) {
		return projectRepository.findByIdAndOrganization_Id(projectId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Project not found"));
	}

	private String normalizeKey(String key) {
		return key == null ? null : key.strip().toUpperCase(Locale.ROOT);
	}

}
