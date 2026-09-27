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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// Not converted to PageResponse/Pageable like the other listing endpoints in
// PR29: webhook count per organization is small and operator-managed, not a
// growing, user-generated collection - there's no realistic case for paging.
@Tag(name = "Webhooks")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/webhooks")
public class WebhookController {

	private final WebhookService webhookService;

	public WebhookController(WebhookService webhookService) {
		this.webhookService = webhookService;
	}

	@Operation(summary = "Register a webhook")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public WebhookCreatedResponse create(@PathVariable UUID orgId, @Valid @RequestBody CreateWebhookRequest request) {
		return webhookService.createWebhook(orgId, request.url());
	}

	@Operation(summary = "List webhooks registered for an organization")
	@GetMapping
	public List<WebhookResponse> list(@PathVariable UUID orgId) {
		return webhookService.listWebhooks(orgId);
	}

	@Operation(summary = "Delete a webhook")
	@DeleteMapping("/{webhookId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID orgId, @PathVariable UUID webhookId) {
		webhookService.deleteWebhook(orgId, webhookId);
	}

}
