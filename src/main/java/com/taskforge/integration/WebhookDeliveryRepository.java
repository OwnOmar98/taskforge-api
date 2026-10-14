package com.taskforge.integration;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

	Page<WebhookDelivery> findByWebhookIdAndOrganizationId(UUID webhookId, UUID organizationId, Pageable pageable);

	Page<WebhookDelivery> findByWebhookIdAndOrganizationIdAndStatus(UUID webhookId, UUID organizationId,
			WebhookDeliveryStatus status, Pageable pageable);

	Optional<WebhookDelivery> findByIdAndWebhookIdAndOrganizationId(UUID id, UUID webhookId, UUID organizationId);

}
