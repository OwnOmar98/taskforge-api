package com.taskforge.organization;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.events.MemberRoleChangedEvent;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.organization.dto.MemberResponse;

@Service
public class MembershipService {

	private final MembershipRepository membershipRepository;
	private final ApplicationEventPublisher eventPublisher;

	public MembershipService(MembershipRepository membershipRepository, ApplicationEventPublisher eventPublisher) {
		this.membershipRepository = membershipRepository;
		this.eventPublisher = eventPublisher;
	}

	// No @PreAuthorize here: TenantInterceptor already rejects non-members for
	// any {orgId} route, and listing members isn't a privileged action on top
	// of that - any member can see the roster.
	@Transactional(readOnly = true)
	public List<MemberResponse> listMembers(UUID organizationId) {
		return membershipRepository.findByOrganizationIdWithUser(organizationId).stream()
				.map(this::toResponse)
				.toList();
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public MemberResponse changeRole(UUID organizationId, UUID targetUserId, MembershipRole newRole, UUID actorId) {
		if (newRole == MembershipRole.OWNER) {
			throw new ConflictException(OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE,
					OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE.defaultMessage());
		}

		Membership membership = findMembershipOrThrow(organizationId, targetUserId);

		if (membership.getRole() == MembershipRole.OWNER) {
			throw new ConflictException(OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE,
					OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE.defaultMessage());
		}

		MembershipRole oldRole = membership.getRole();
		membership.changeRole(newRole);
		if (newRole != oldRole) {
			eventPublisher.publishEvent(
					new MemberRoleChangedEvent(organizationId, actorId, targetUserId, oldRole, newRole));
		}
		return toResponse(membership);
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public void removeMember(UUID organizationId, UUID targetUserId) {
		Membership membership = findMembershipOrThrow(organizationId, targetUserId);

		if (membership.getRole() == MembershipRole.OWNER) {
			throw new ConflictException(OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE,
					OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE.defaultMessage());
		}

		membershipRepository.delete(membership);
	}

	private Membership findMembershipOrThrow(UUID organizationId, UUID userId) {
		return membershipRepository.findByOrganization_IdAndUser_Id(organizationId, userId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Membership not found"));
	}

	private MemberResponse toResponse(Membership membership) {
		return new MemberResponse(membership.getUser().getId(), membership.getUser().getEmail(),
				membership.getUser().getFullName(), membership.getRole());
	}

}
