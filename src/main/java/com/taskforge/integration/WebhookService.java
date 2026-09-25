package com.taskforge.integration;

import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.integration.dto.WebhookCreatedResponse;
import com.taskforge.integration.dto.WebhookResponse;
import com.taskforge.security.SecureTokenGenerator;

@Service
public class WebhookService {

	private final WebhookRepository webhookRepository;

	public WebhookService(WebhookRepository webhookRepository) {
		this.webhookRepository = webhookRepository;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public WebhookCreatedResponse createWebhook(UUID organizationId, String url) {
		String secret = SecureTokenGenerator.generateRawToken();
		Webhook webhook = webhookRepository.save(new Webhook(organizationId, url, secret));
		return new WebhookCreatedResponse(webhook.getId(), webhook.getUrl(), webhook.getSecret(),
				webhook.getCreatedAt());
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional(readOnly = true)
	public List<WebhookResponse> listWebhooks(UUID organizationId) {
		return webhookRepository.findByOrganizationId(organizationId).stream().map(this::toResponse).toList();
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public void deleteWebhook(UUID organizationId, UUID webhookId) {
		Webhook webhook = webhookRepository.findByIdAndOrganizationId(webhookId, organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Webhook not found"));

		webhookRepository.delete(webhook);
	}

	private WebhookResponse toResponse(Webhook webhook) {
		return new WebhookResponse(webhook.getId(), webhook.getUrl(), webhook.getCreatedAt());
	}

}
