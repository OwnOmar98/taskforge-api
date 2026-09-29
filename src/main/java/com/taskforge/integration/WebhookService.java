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
	private final WebhookMapper webhookMapper;

	public WebhookService(WebhookRepository webhookRepository, WebhookMapper webhookMapper) {
		this.webhookRepository = webhookRepository;
		this.webhookMapper = webhookMapper;
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

}
