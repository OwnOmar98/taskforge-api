package com.taskforge.security;

import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.taskforge.organization.MembershipRepository;

@Component
public class TenantInterceptor implements HandlerInterceptor {

	private final MembershipRepository membershipRepository;
	private final TenantContext tenantContext;

	public TenantInterceptor(MembershipRepository membershipRepository, TenantContext tenantContext) {
		this.membershipRepository = membershipRepository;
		this.tenantContext = tenantContext;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String orgIdValue = pathVariable(request, "orgId");

		if (orgIdValue == null) {
			// No org-scoped path variable on this route - nothing to check.
			return true;
		}

		UUID organizationId = UUID.fromString(orgIdValue);
		UUID userId = (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

		boolean isMember = membershipRepository.findByOrganization_IdAndUser_Id(organizationId, userId).isPresent();

		if (!isMember) {
			throw new AccessDeniedException("Not a member of this organization");
		}

		tenantContext.setOrganizationId(organizationId);
		return true;
	}

	@SuppressWarnings("unchecked")
	private String pathVariable(HttpServletRequest request, String name) {
		Object attribute = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

		if (!(attribute instanceof Map<?, ?> variables)) {
			return null;
		}

		return ((Map<String, String>) variables).get(name);
	}

}
