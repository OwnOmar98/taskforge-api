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
	private final OrganizationMapper organizationMapper;

	public OrganizationController(OrganizationService organizationService, OrganizationMapper organizationMapper) {
		this.organizationService = organizationService;
		this.organizationMapper = organizationMapper;
	}

	@Operation(summary = "Create an organization")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public OrganizationResponse create(@Valid @RequestBody CreateOrganizationRequest request,
			@CurrentUserId UUID currentUserId) {
		return organizationMapper.toResponse(organizationService.createOrganization(request.name(), currentUserId));
	}

	@Operation(summary = "Rename an organization")
	@PatchMapping("/{orgId}")
	public OrganizationResponse update(@PathVariable UUID orgId,
			@Valid @RequestBody UpdateOrganizationRequest request) {
		return organizationMapper.toResponse(organizationService.renameOrganization(orgId, request.name()));
	}

}
