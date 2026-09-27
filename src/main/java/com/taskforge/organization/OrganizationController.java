package com.taskforge.organization;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.organization.dto.OrganizationResponse;
import com.taskforge.organization.dto.UpdateOrganizationRequest;
import com.taskforge.security.CurrentUserId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Organizations")
@RestController
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

	private final OrganizationService organizationService;

	public OrganizationController(OrganizationService organizationService) {
		this.organizationService = organizationService;
	}

	@Operation(summary = "Create an organization")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request,
			@CurrentUserId UUID currentUserId) {
		return toResponse(organizationService.createOrganization(request.name(), currentUserId));
	}

	@Operation(summary = "Rename an organization")
	@PatchMapping("/{orgId}")
	public OrganizationResponse update(@PathVariable UUID orgId,
			@Valid @RequestBody UpdateOrganizationRequest request) {
		return toResponse(organizationService.renameOrganization(orgId, request.name()));
	}

	private OrganizationResponse toResponse(Organization organization) {
		return new OrganizationResponse(organization.getId(), organization.getName(), organization.getSlug(),
				organization.getCreatedAt());
	}

}
