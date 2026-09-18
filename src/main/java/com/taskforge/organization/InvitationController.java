package com.taskforge.organization;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.organization.dto.CreateInvitationRequest;
import com.taskforge.organization.dto.InvitationResponse;
import com.taskforge.organization.dto.MemberResponse;
import com.taskforge.security.CurrentUserId;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import jakarta.validation.Valid;

@RestController
public class InvitationController {

	private final InvitationService invitationService;
	private final UserRepository userRepository;

	public InvitationController(InvitationService invitationService, UserRepository userRepository) {
		this.invitationService = invitationService;
		this.userRepository = userRepository;
	}

	@PostMapping("/api/v1/organizations/{orgId}/invitations")
	@ResponseStatus(HttpStatus.CREATED)
	public InvitationResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateInvitationRequest request,
			@CurrentUserId UUID currentUserId) {
		return invitationService.createInvitation(orgId, request.email(), request.role(), currentUserId);
	}

	// Deliberately not nested under /organizations/{orgId}: the token alone
	// determines the organization, and the caller isn't a member yet - a
	// {orgId} path variable here would make TenantInterceptor reject the
	// request before the invitation is even looked up.
	@PostMapping("/api/v1/invitations/{token}/accept")
	@ResponseStatus(HttpStatus.CREATED)
	public MemberResponse accept(@PathVariable String token, @CurrentUserId UUID currentUserId) {
		User currentUser = userRepository.findById(currentUserId).orElseThrow();
		return invitationService.acceptInvitation(token, currentUser);
	}

	@PostMapping("/api/v1/invitations/{token}/decline")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void decline(@PathVariable String token, @CurrentUserId UUID currentUserId) {
		User currentUser = userRepository.findById(currentUserId).orElseThrow();
		invitationService.declineInvitation(token, currentUser);
	}

}
