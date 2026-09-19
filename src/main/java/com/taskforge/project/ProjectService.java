package com.taskforge.project;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

		Project project = projectRepository.save(new Project(organization, normalizedKey, name));

		User creator = userRepository.findById(creatorUserId).orElseThrow();
		projectMemberRepository.save(new ProjectMember(project, creator, ProjectMemberRole.LEAD));

		return toResponse(project);
	}

	@Transactional(readOnly = true)
	public List<ProjectResponse> listProjects(UUID organizationId) {
		return projectRepository.findByOrganization_Id(organizationId).stream().map(this::toResponse).toList();
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
