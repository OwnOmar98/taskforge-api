package com.taskforge.auth;

import org.mapstruct.Mapper;

import com.taskforge.auth.dto.MeResponse;
import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.user.User;

@Mapper(config = MapStructConfig.class)
public interface UserMapper {

	MeResponse toMeResponse(User user);

}
