package com.taskforge.integration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.PageResponse;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.integration.dto.WebhookCreatedResponse;
import com.taskforge.integration.dto.WebhookDeliveryResponse;
import com.taskforge.integration.dto.WebhookResponse;
import com.taskforge.security.SecureTokenGenerator;

@Service
public class WebhookService {

	private final WebhookRepository webhookRepository;
	private final WebhookMapper webhookMapper;
	private final WebhookDeliveryRepository deliveryRepository;

	public WebhookService(WebhookRepository webhookRepository, WebhookMapper webhookMapper,
			WebhookDeliveryRepository deliveryRepository) {
		this.webhookRepository = webhookRepository;
		this.webhookMapper = webhookMapper;
		this.deliveryRepository = deliveryRepository;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public WebhookCreatedResponse createWebhook(UUID organizationId, String url) {
		String secret = SecureTokenGenerator.generateRawToken();
		Webhook webhook = webhookRepository.save(new Webhook(organizationId, url, secret));
		return webhookMapper.toCreatedResponse(webhook);
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional(readOnly = true)
	public List<WebhookResponse> listWebhooks(UUID organizationId) {
		return webhookRepository.findByOrganizationId(organizationId).stream().map(webhookMapper::toResponse).toList();
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public void deleteWebhook(UUID organizationId, UUID webhookId) {
		Webhook webhook = webhookRepository.findByIdAndOrganizationId(webhookId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Webhook not found"));

		webhookRepository.delete(webhook);
	}

	// The outbox doubles as a delivery log: an admin can see what was sent,
	// what's still retrying, and what gave up - and why.
	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional(readOnly = true)
	public PageResponse<WebhookDeliveryResponse> listDeliveries(UUID organizationId, UUID webhookId,
			WebhookDeliveryStatus status, Pageable pageable) {
		Page<WebhookDelivery> page = status == null
				? deliveryRepository.findByWebhookIdAndOrganizationId(webhookId, organizationId, pageable)
				: deliveryRepository.findByWebhookIdAndOrganizationIdAndStatus(webhookId, organizationId, status,
						pageable);
		return PageResponse.from(page.map(webhookMapper::toResponse));
	}

	// Only from FAILED: a PENDING or IN_FLIGHT delivery is still being worked
	// on by the relay, and requeueing it underneath a send in progress would
	// race that attempt's outcome.
	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public WebhookDeliveryResponse redeliver(UUID organizationId, UUID webhookId, UUID deliveryId) {
		WebhookDelivery delivery = deliveryRepository
				.findByIdAndWebhookIdAndOrganizationId(deliveryId, webhookId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Webhook delivery not found"));
		if (delivery.getStatus() != WebhookDeliveryStatus.FAILED) {
			throw new ConflictException(WebhookErrorCode.DELIVERY_NOT_FAILED,
					WebhookErrorCode.DELIVERY_NOT_FAILED.defaultMessage());
		}
		delivery.requeue(Instant.now());
		return webhookMapper.toResponse(delivery);
	}

}
