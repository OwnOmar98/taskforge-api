package com.taskforge.security;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

// Request-scoped: Spring creates a fresh instance per request and discards it
// afterward, so there is nothing to manually clear between requests - unlike
// a ThreadLocal-based holder, leakage across requests isn't possible here.
@Component
@RequestScope
public class TenantContext {

	private UUID organizationId;

	public UUID getOrganizationId() {
		return organizationId;
	}

	public void setOrganizationId(UUID organizationId) {
		this.organizationId = organizationId;
	}

}
