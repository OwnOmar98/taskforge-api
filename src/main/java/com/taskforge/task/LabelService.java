package com.taskforge.task;

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
import com.taskforge.task.dto.LabelResponse;

@Service
public class LabelService {

	private final LabelRepository labelRepository;
	private final OrganizationRepository organizationRepository;
	private final TaskRepository taskRepository;

	public LabelService(LabelRepository labelRepository, OrganizationRepository organizationRepository,
			TaskRepository taskRepository) {
		this.labelRepository = labelRepository;
		this.organizationRepository = organizationRepository;
		this.taskRepository = taskRepository;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public LabelResponse createLabel(UUID organizationId, String name) {
		if (labelRepository.existsByOrganization_IdAndName(organizationId, name)) {
			throw new ConflictException(LabelErrorCode.LABEL_NAME_IN_USE,
					LabelErrorCode.LABEL_NAME_IN_USE.defaultMessage());
		}

		Organization organization = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Organization not found"));

		try {
			// The check above is a point-in-time read, not a lock - two concurrent
			// creates for the same org+name can both pass it before either commits.
			// saveAndFlush forces the insert (and the unique constraint it can
			// violate) to happen synchronously here, not deferred to commit,
			// where this catch couldn't see it.
			return toResponse(labelRepository.saveAndFlush(new Label(organization, name)));
		}
		catch (DataIntegrityViolationException e) {
			throw new ConflictException(LabelErrorCode.LABEL_NAME_IN_USE,
					LabelErrorCode.LABEL_NAME_IN_USE.defaultMessage());
		}
	}

	// No @PreAuthorize: TenantInterceptor already requires org membership for
	// any {orgId} route, and seeing the shared label set isn't privileged.
	@Transactional(readOnly = true)
	public PageResponse<LabelResponse> listLabels(UUID organizationId, Pageable pageable) {
		Page<Label> page = labelRepository.findByOrganization_Id(organizationId, pageable);
		return PageResponse.from(page.map(this::toResponse));
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'CONTRIBUTE')")
	@Transactional
	public void attachLabel(UUID taskId, UUID labelId) {
		Task task = findTaskOrThrow(taskId);
		UUID organizationId = task.getProject().getOrganization().getId();

		Label label = labelRepository.findByIdAndOrganization_Id(labelId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Label not found"));

		task.getLabels().add(label);
	}

	@PreAuthorize("hasPermission(#taskId, 'Task', 'CONTRIBUTE')")
	@Transactional
	public void detachLabel(UUID taskId, UUID labelId) {
		Task task = findTaskOrThrow(taskId);
		task.getLabels().removeIf(label -> label.getId().equals(labelId));
	}

	private Task findTaskOrThrow(UUID taskId) {
		return taskRepository.findById(taskId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Task not found"));
	}

	private LabelResponse toResponse(Label label) {
		return new LabelResponse(label.getId(), label.getOrganization().getId(), label.getName(),
				label.getCreatedAt());
	}

}
