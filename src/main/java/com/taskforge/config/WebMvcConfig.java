package com.taskforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.taskforge.security.TenantInterceptor;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

	private final TenantInterceptor tenantInterceptor;

	public WebMvcConfig(TenantInterceptor tenantInterceptor) {
		this.tenantInterceptor = tenantInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// No path restriction: the interceptor itself no-ops on any route with
		// no "orgId" path variable, so every future org-scoped controller is
		// protected automatically without needing to remember to register it here.
		registry.addInterceptor(tenantInterceptor);
	}

}
