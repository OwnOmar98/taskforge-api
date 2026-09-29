package com.taskforge.common.mapper;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

// Shared by every mapper. unmappedTargetPolicy = ERROR is the reason to use
// MapStruct here at all: a response DTO field that no mapping populates fails
// the build instead of silently serializing as null.
@MapperConfig(componentModel = MappingConstants.ComponentModel.SPRING,
		injectionStrategy = InjectionStrategy.CONSTRUCTOR, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface MapStructConfig {
}
