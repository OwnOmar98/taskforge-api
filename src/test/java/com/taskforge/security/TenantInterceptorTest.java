package com.taskforge.security;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static com.taskforge.common.exception.GeneralErrorCode.INSUFFICIENT_PERMISSIONS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TenantInterceptorTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Test
	void memberCanAccessOwnOrganizationScopedRoute() throws Exception {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User member = userRepository.saveAndFlush(new User("member@acme.test", "hash", "Member"));
		membershipRepository.saveAndFlush(new Membership(org, member, MembershipRole.MEMBER));

		String token = jwtService.generateAccessToken(member.getId());

		mockMvc.perform(get("/test-tenant/organizations/" + org.getId() + "/ping")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string("pong"));
	}

	@Test
	void tenantContextIsPopulatedWithTheRequestedOrganization() throws Exception {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User member = userRepository.saveAndFlush(new User("context@acme.test", "hash", "Member"));
		membershipRepository.saveAndFlush(new Membership(org, member, MembershipRole.MEMBER));

		String token = jwtService.generateAccessToken(member.getId());

		mockMvc.perform(get("/test-tenant/organizations/" + org.getId() + "/tenant-context")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string("\"" + org.getId() + "\""));
	}

	@Test
	void nonMemberCannotAccessOrganizationScopedRoute() throws Exception {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User outsider = userRepository.saveAndFlush(new User("outsider@acme.test", "hash", "Outsider"));

		String token = jwtService.generateAccessToken(outsider.getId());

		mockMvc.perform(get("/test-tenant/organizations/" + org.getId() + "/ping")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value(INSUFFICIENT_PERMISSIONS.code()));
	}

	@Test
	void routeWithoutOrgIdPathVariableIsUnaffectedByTheInterceptor() throws Exception {
		User user = userRepository.saveAndFlush(new User("noorg@acme.test", "hash", "Someone"));
		String token = jwtService.generateAccessToken(user.getId());

		mockMvc.perform(get("/test-tenant/no-org-path")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(content().string("pong"));
	}

}
