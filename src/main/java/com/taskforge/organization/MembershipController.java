package com.taskforge.organization;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.common.PageResponse;
import com.taskforge.organization.dto.ChangeRoleRequest;
import com.taskforge.organization.dto.MemberResponse;
import com.taskforge.security.CurrentUserId;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Organization Members")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/members")
public class MembershipController {

	private final MembershipService membershipService;

	public MembershipController(MembershipService membershipService) {
		this.membershipService = membershipService;
	}

	@Operation(summary = "List members of an organization")
	@GetMapping
	public PageResponse<MemberResponse> list(@PathVariable UUID orgId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
		return membershipService.listMembers(orgId, pageable);
	}

	@Operation(summary = "Change a member's role")
	@PatchMapping("/{userId}")
	public MemberResponse changeRole(@PathVariable UUID orgId, @PathVariable UUID userId,
			@Valid @RequestBody ChangeRoleRequest request, @CurrentUserId UUID currentUserId) {
		return membershipService.changeRole(orgId, userId, request.role(), currentUserId);
	}

	@Operation(summary = "Remove a member from an organization")
	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
		membershipService.removeMember(orgId, userId);
	}

}
