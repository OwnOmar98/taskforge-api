package com.taskforge.organization;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.organization.dto.ChangeRoleRequest;
import com.taskforge.organization.dto.MemberResponse;
import com.taskforge.security.CurrentUserId;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/members")
public class MembershipController {

	private final MembershipService membershipService;

	public MembershipController(MembershipService membershipService) {
		this.membershipService = membershipService;
	}

	@GetMapping
	public List<MemberResponse> list(@PathVariable UUID orgId) {
		return membershipService.listMembers(orgId);
	}

	@PatchMapping("/{userId}")
	public MemberResponse changeRole(@PathVariable UUID orgId, @PathVariable UUID userId,
			@Valid @RequestBody ChangeRoleRequest request, @CurrentUserId UUID currentUserId) {
		return membershipService.changeRole(orgId, userId, request.role(), currentUserId);
	}

	@DeleteMapping("/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@PathVariable UUID orgId, @PathVariable UUID userId) {
		membershipService.removeMember(orgId, userId);
	}

}
