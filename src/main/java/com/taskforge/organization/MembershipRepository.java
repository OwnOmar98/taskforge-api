package com.taskforge.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	Optional<Membership> findByOrganization_IdAndUser_Id(UUID organizationId, UUID userId);

	// open-in-view is disabled, so listing endpoints need the user loaded within
	// the same query rather than left to lazy-load after the session is closed.
	@Query("select m from Membership m join fetch m.user where m.organization.id = :organizationId")
	List<Membership> findByOrganizationIdWithUser(@Param("organizationId") UUID organizationId);

}
