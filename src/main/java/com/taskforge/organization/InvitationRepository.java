package com.taskforge.organization;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

	Optional<Invitation> findByTokenHash(String tokenHash);

	List<Invitation> findByOrganization_IdAndEmailAndAcceptedAtIsNullAndDeclinedAtIsNullAndExpiresAtAfter(
			UUID organizationId, String email, Instant now);

}
