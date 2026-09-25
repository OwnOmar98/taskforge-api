package com.taskforge.integration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {

	List<Webhook> findByOrganizationId(UUID organizationId);

	Optional<Webhook> findByIdAndOrganizationId(UUID id, UUID organizationId);

}
