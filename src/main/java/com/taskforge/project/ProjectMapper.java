package com.taskforge.project;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.project.dto.ProjectMemberResponse;
import com.taskforge.project.dto.ProjectResponse;

@Mapper(config = MapStructConfig.class)
public interface ProjectMapper {

	@Mapping(target = "organizationId", source = "organization.id")
	ProjectResponse toResponse(Project project);

	@Mapping(target = "userId", source = "user.id")
	@Mapping(target = "email", source = "user.email")
	@Mapping(target = "fullName", source = "user.fullName")
	ProjectMemberResponse toResponse(ProjectMember member);

}
