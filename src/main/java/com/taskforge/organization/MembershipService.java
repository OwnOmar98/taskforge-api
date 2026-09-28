package com.taskforge.organization;

import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.events.MemberRoleChangedEvent;
import com.taskforge.audit.events.MembershipRemovedEvent;
import com.taskforge.common.AfterCommit;
import com.taskforge.common.PageResponse;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.organization.dto.MemberResponse;

@Service
public class MembershipService {

	private final MembershipRepository membershipRepository;
	private final ApplicationEventPublisher eventPublisher;
	private final MembershipRoleCacheService membershipRoleCacheService;

	public MembershipService(MembershipRepository membershipRepository, ApplicationEventPublisher eventPublisher,
			MembershipRoleCacheService membershipRoleCacheService) {
		this.membershipRepository = membershipRepository;
		this.eventPublisher = eventPublisher;
		this.membershipRoleCacheService = membershipRoleCacheService;
	}

	// No @PreAuthorize here: TenantInterceptor already rejects non-members for
	// any {orgId} route, and listing members isn't a privileged action on top
	// of that - any member can see the roster.
	@Transactional(readOnly = true)
	public PageResponse<MemberResponse> listMembers(UUID organizationId, Pageable pageable) {
		Page<Membership> page = membershipRepository.findByOrganizationIdWithUser(organizationId, pageable);
		return PageResponse.from(page.map(this::toResponse));
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
		// Mandatory, not a nice-to-have: a stale cached role surviving until TTL
		// expiry after a demotion is a real security bug, not a performance nit.
		// Deferred to after commit - evicting while the row's own UPDATE is
		// still uncommitted lets a concurrent read repopulate the cache with
		// the pre-change role before anything evicts it again.
		AfterCommit.run(() -> membershipRoleCacheService.evict(organizationId, targetUserId));
		return toResponse(membership);
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public void removeMember(UUID organizationId, UUID targetUserId, UUID actorId) {
		Membership membership = findMembershipOrThrow(organizationId, targetUserId);

		if (membership.getRole() == MembershipRole.OWNER) {
			throw new ConflictException(OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE,
					OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE.defaultMessage());
		}

		MembershipRole removedRole = membership.getRole();
		membershipRepository.delete(membership);
		eventPublisher.publishEvent(new MembershipRemovedEvent(organizationId, actorId, targetUserId, removedRole));
		AfterCommit.run(() -> membershipRoleCacheService.evict(organizationId, targetUserId));
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
