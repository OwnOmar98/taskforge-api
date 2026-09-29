package com.taskforge.audit;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.audit.dto.AuditLogResponse;
import com.taskforge.common.mapper.JsonColumnMapper;
import com.taskforge.common.mapper.MapStructConfig;

@Mapper(config = MapStructConfig.class, uses = JsonColumnMapper.class)
public interface AuditLogMapper {

	@Mapping(target = "metadata", source = "metadata", qualifiedByName = "jsonToObject")
	AuditLogResponse toResponse(AuditLog auditLog);

}
