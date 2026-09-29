package com.taskforge.common.mapper;

import org.mapstruct.Named;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

// For jsonb columns mapped as String on the entity (AuditLog.metadata,
// Notification.payload): parsed back into a plain Object so the response
// serializes it as nested JSON instead of an escaped string.
//
// Mappers must reference this with qualifiedByName, never rely on automatic
// selection: String is already assignable to Object, so without the explicit
// qualifier MapStruct would happily copy the raw JSON string straight across.
@Component
public class JsonColumnMapper {

	private final ObjectMapper objectMapper;

	public JsonColumnMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Named("jsonToObject")
	public Object jsonToObject(String json) {
		return json == null ? null : objectMapper.readValue(json, Object.class);
	}

}
