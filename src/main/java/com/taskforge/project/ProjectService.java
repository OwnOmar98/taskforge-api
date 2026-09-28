package com.taskforge.project;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	public ProjectService(ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
			OrganizationRepository organizationRepository, UserRepository userRepository) {
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
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
		return toResponse(project);
	}

	@Transactional(readOnly = true)
	public PageResponse<ProjectResponse> listProjects(UUID organizationId, Pageable pageable) {
		Page<Project> page = projectRepository.findByOrganization_Id(organizationId, pageable);
		return PageResponse.from(page.map(this::toResponse));
	}

	@Transactional(readOnly = true)
	public ProjectResponse getProject(UUID organizationId, UUID projectId) {
		return toResponse(findProjectInOrgOrThrow(organizationId, projectId));
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
		return toResponse(project);
	}

	Project findProjectInOrgOrThrow(UUID organizationId, UUID projectId) {
		return projectRepository.findByIdAndOrganization_Id(projectId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Project not found"));
	}

	private String normalizeKey(String key) {
		return key == null ? null : key.strip().toUpperCase(Locale.ROOT);
	}

	private ProjectResponse toResponse(Project project) {
		return new ProjectResponse(project.getId(), project.getOrganization().getId(), project.getKey(),
				project.getName(), project.getVersion(), project.getCreatedAt());
	}

}
