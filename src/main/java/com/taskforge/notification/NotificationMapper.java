package com.taskforge.notification;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.common.mapper.JsonColumnMapper;
import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.notification.dto.NotificationResponse;

@Mapper(config = MapStructConfig.class, uses = JsonColumnMapper.class)
public interface NotificationMapper {

	@Mapping(target = "payload", source = "payload", qualifiedByName = "jsonToObject")
	NotificationResponse toResponse(Notification notification);

}
