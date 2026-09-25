package com.taskforge.integration;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.integration.dto.CreateWebhookRequest;
import com.taskforge.integration.dto.WebhookCreatedResponse;
import com.taskforge.integration.dto.WebhookResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/organizations/{orgId}/webhooks")
public class WebhookController {

	private final WebhookService webhookService;

	public WebhookController(WebhookService webhookService) {
		this.webhookService = webhookService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WebhookCreatedResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateWebhookRequest request) {
		return webhookService.createWebhook(orgId, request.url());
	}

	@GetMapping
	public List<WebhookResponse> list(@PathVariable UUID orgId) {
		return webhookService.listWebhooks(orgId);
	}

	@DeleteMapping("/{webhookId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID orgId, @PathVariable UUID webhookId) {
		webhookService.deleteWebhook(orgId, webhookId);
	}

}
