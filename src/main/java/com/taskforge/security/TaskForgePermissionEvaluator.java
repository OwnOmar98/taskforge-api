package com.taskforge.security;

import java.io.Serializable;
import java.util.UUID;

import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;

@Component
public class TaskForgePermissionEvaluator implements PermissionEvaluator {

	private final MembershipRepository membershipRepository;

	public TaskForgePermissionEvaluator(MembershipRepository membershipRepository) {
		this.membershipRepository = membershipRepository;
	}

	@Override
	public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
		return false;
	}

	@Override
	public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
			Object permission) {
		if (!"Organization".equals(targetType)) {
			return false;
		}

		UUID userId = (UUID) authentication.getPrincipal();
		UUID organizationId = UUID.fromString(targetId.toString());
		MembershipRole requiredRole = MembershipRole.valueOf(permission.toString());

		return membershipRepository.findByOrganization_IdAndUser_Id(organizationId, userId)
				.map(membership -> membership.getRole().isAtLeast(requiredRole))
				.orElse(false);
	}

}
