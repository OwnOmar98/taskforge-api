package com.taskforge.organization;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.taskforge.common.mapper.MapStructConfig;
import com.taskforge.organization.dto.InvitationResponse;
import com.taskforge.organization.dto.MemberResponse;
import com.taskforge.organization.dto.OrganizationResponse;
import com.taskforge.user.User;

@Mapper(config = MapStructConfig.class)
public interface OrganizationMapper {

	OrganizationResponse toResponse(Organization organization);

	@Mapping(target = "userId", source = "user.id")
	@Mapping(target = "email", source = "user.email")
	@Mapping(target = "fullName", source = "user.fullName")
	MemberResponse toResponse(Membership membership);

	@Mapping(target = "userId", source = "user.id")
	@Mapping(target = "email", source = "user.email")
	@Mapping(target = "fullName", source = "user.fullName")
	@Mapping(target = "role", source = "role")
	MemberResponse toMemberResponse(User user, MembershipRole role);

	// The entity only stores the token's hash; the raw token exists solely in
	// memory at creation time and is returned exactly once, here.
	@Mapping(target = "token", source = "rawToken")
	InvitationResponse toResponse(Invitation invitation, String rawToken);

}
