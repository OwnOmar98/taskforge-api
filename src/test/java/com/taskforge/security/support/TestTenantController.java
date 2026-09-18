package com.taskforge.security.support;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.security.TenantContext;

@RestController
@RequestMapping("/test-tenant")
public class TestTenantController {

	private final TenantContext tenantContext;

	public TestTenantController(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@GetMapping("/organizations/{orgId}/ping")
	public String ping() {
		return "pong";
	}

	@GetMapping("/organizations/{orgId}/tenant-context")
	public UUID tenantContextOrganizationId() {
		return tenantContext.getOrganizationId();
	}

	@GetMapping("/no-org-path")
	public String noOrgPath() {
		return "pong";
	}

}
