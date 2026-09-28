package com.taskforge.organization;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.AfterCommit;
import com.taskforge.common.EmailNormalizer;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.organization.dto.InvitationResponse;
import com.taskforge.organization.dto.MemberResponse;
import com.taskforge.security.SecureTokenGenerator;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class InvitationService {

	private final InvitationRepository invitationRepository;
	private final MembershipRepository membershipRepository;
	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final InvitationProperties properties;
	private final MembershipRoleCacheService membershipRoleCacheService;

	public InvitationService(InvitationRepository invitationRepository, MembershipRepository membershipRepository,
			OrganizationRepository organizationRepository, UserRepository userRepository,
			InvitationProperties properties, MembershipRoleCacheService membershipRoleCacheService) {
		this.invitationRepository = invitationRepository;
		this.membershipRepository = membershipRepository;
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
		this.properties = properties;
		this.membershipRoleCacheService = membershipRoleCacheService;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public InvitationResponse createInvitation(UUID organizationId, String email, MembershipRole role,
			UUID invitedByUserId) {
		if (role == MembershipRole.OWNER) {
			throw new ConflictException(OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE,
					OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE.defaultMessage());
		}

		// Normalized once here so every lookup below compares against the exact
		// form User/Invitation store their own email in.
		String normalizedEmail = EmailNormalizer.normalize(email);

		Organization organization = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Organization not found"));

		userRepository.findByEmail(normalizedEmail).ifPresent(existingUser -> {
			if (membershipRepository.findByOrganization_IdAndUser_Id(organizationId, existingUser.getId())
					.isPresent()) {
				throw new ConflictException(OrganizationErrorCode.ALREADY_MEMBER,
						OrganizationErrorCode.ALREADY_MEMBER.defaultMessage());
			}
		});

		boolean alreadyPending = !invitationRepository
				.findByOrganization_IdAndEmailAndAcceptedAtIsNullAndDeclinedAtIsNullAndExpiresAtAfter(organizationId,
						normalizedEmail, Instant.now())
				.isEmpty();

		if (alreadyPending) {
			throw new ConflictException(OrganizationErrorCode.INVITATION_ALREADY_PENDING,
					OrganizationErrorCode.INVITATION_ALREADY_PENDING.defaultMessage());
		}

		User invitedBy = userRepository.findById(invitedByUserId).orElseThrow();

		String rawToken = SecureTokenGenerator.generateRawToken();
		Invitation invitation = invitationRepository.save(new Invitation(organization, normalizedEmail, role,
				SecureTokenGenerator.hash(rawToken), invitedBy, Instant.now().plus(properties.ttl())));

		return new InvitationResponse(invitation.getId(), invitation.getEmail(), invitation.getRole(), rawToken,
				invitation.getExpiresAt());
	}

	@Transactional
	public MemberResponse acceptInvitation(String rawToken, User currentUser) {
		Invitation invitation = findActiveInvitationForOrThrow(rawToken, currentUser);

		UUID organizationId = invitation.getOrganization().getId();
		// createInvitation blocks this at issue time, but that's a point-in-time
		// check - two invitations issued under different casing of the same
		// email, or a membership acquired through some other path since, could
		// still reach here. Fail cleanly instead of letting the unique
		// constraint on memberships surface as a raw 500.
		if (membershipRepository.findByOrganization_IdAndUser_Id(organizationId, currentUser.getId()).isPresent()) {
			throw new ConflictException(OrganizationErrorCode.ALREADY_MEMBER,
					OrganizationErrorCode.ALREADY_MEMBER.defaultMessage());
		}

		invitation.accept();
		Membership membership = membershipRepository.save(
				new Membership(invitation.getOrganization(), currentUser, invitation.getRole()));

		// Unlike a brand-new organization, this org already existed, so a prior
		// "not a member" lookup for this exact pair could already be cached -
		// e.g. this user tried an org route before accepting and got denied.
		// Without evicting, that stale negative result would keep denying them
		// until it expires. Deferred to after commit - see
		// MembershipService.changeRole.
		AfterCommit.run(() -> membershipRoleCacheService.evict(organizationId, currentUser.getId()));

		return new MemberResponse(currentUser.getId(), currentUser.getEmail(), currentUser.getFullName(),
				membership.getRole());
	}

	@Transactional
	public void declineInvitation(String rawToken, User currentUser) {
		findActiveInvitationForOrThrow(rawToken, currentUser).decline();
	}

	private Invitation findActiveInvitationForOrThrow(String rawToken, User currentUser) {
		Invitation invitation = invitationRepository.findByTokenHash(SecureTokenGenerator.hash(rawToken))
				.filter(Invitation::isActive)
				.orElseThrow(() -> new ResourceNotFoundException(OrganizationErrorCode.INVALID_OR_EXPIRED_INVITATION,
						OrganizationErrorCode.INVALID_OR_EXPIRED_INVITATION.defaultMessage()));

		// Same reasoning as login's normalized "invalid credentials" message: a
		// token that exists but belongs to a different email is treated as an
		// access problem, not exposed as a distinct "wrong email" outcome.
		if (!invitation.getEmail().equals(currentUser.getEmail())) {
			throw new AccessDeniedException("Invitation email does not match the authenticated user");
		}

		return invitation;
	}

}
