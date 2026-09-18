package com.taskforge.organization;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InvitationProperties.class)
public class OrganizationConfig {
}
