package com.taskforge.integration;

import org.mapstruct.Mapper;

import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.integration.dto.WebhookCreatedResponse;
import com.taskforge.integration.dto.WebhookResponse;

@Mapper(config = MapStructConfig.class)
public interface WebhookMapper {

	WebhookResponse toResponse(Webhook webhook);

	// Only ever used for the create response - the one time the secret is shown.
	WebhookCreatedResponse toCreatedResponse(Webhook webhook);

}
