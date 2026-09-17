package com.taskforge.organization;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MembershipRepository extends JpaRepository<Membership, UUID> {
}
