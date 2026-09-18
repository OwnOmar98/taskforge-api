package com.taskforge.organization;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {

	Optional<Membership> findByOrganization_IdAndUser_Id(UUID organizationId, UUID userId);

}
