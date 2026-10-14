package com.taskforge.integration;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.common.mapper.JsonColumnMapper;
import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.integration.dto.WebhookCreatedResponse;
import com.taskforge.integration.dto.WebhookDeliveryResponse;
import com.taskforge.integration.dto.WebhookResponse;

@Mapper(config = MapStructConfig.class, uses = JsonColumnMapper.class)
public interface WebhookMapper {

	WebhookResponse toResponse(Webhook webhook);

	// Only ever used for the create response - the one time the secret is shown.
	WebhookCreatedResponse toCreatedResponse(Webhook webhook);

	@Mapping(target = "payload", source = "payload", qualifiedByName = "jsonToObject")
	WebhookDeliveryResponse toResponse(WebhookDelivery delivery);

}
