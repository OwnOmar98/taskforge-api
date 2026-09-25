package com.taskforge.integration.dto;

import jakarta.validation.constraints.NotBlank;

import org.hibernate.validator.constraints.URL;

public record CreateWebhookRequest(@NotBlank @URL String url) {
}
