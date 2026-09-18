package com.taskforge.organization;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrganizationServiceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void ownerCanRenameOrganization() {
		Organization org = createOrganization();
		User owner = createMember(org, MembershipRole.OWNER);

		authenticateAs(owner);
		organizationService.renameOrganization(org.getId(), "Renamed by Owner");

		assertEquals("Renamed by Owner", organizationRepository.findById(org.getId()).orElseThrow().getName());
	}

	@Test
	void adminCanRenameOrganization() {
		Organization org = createOrganization();
		User admin = createMember(org, MembershipRole.ADMIN);

		authenticateAs(admin);
		organizationService.renameOrganization(org.getId(), "Renamed by Admin");

		assertEquals("Renamed by Admin", organizationRepository.findById(org.getId()).orElseThrow().getName());
	}

	@Test
	void memberCannotRenameOrganization() {
		Organization org = createOrganization();
		User member = createMember(org, MembershipRole.MEMBER);

		authenticateAs(member);

		assertThrows(AccessDeniedException.class,
				() -> organizationService.renameOrganization(org.getId(), "Should not happen"));
	}

	@Test
	void guestCannotRenameOrganization() {
		Organization org = createOrganization();
		User guest = createMember(org, MembershipRole.GUEST);

		authenticateAs(guest);

		assertThrows(AccessDeniedException.class,
				() -> organizationService.renameOrganization(org.getId(), "Should not happen"));
	}

	@Test
	void nonMemberCannotRenameOrganization() {
		Organization org = createOrganization();
		User outsider = userRepository.saveAndFlush(new User("outsider@acme.test", "hash", "Outsider"));

		authenticateAs(outsider);

		assertThrows(AccessDeniedException.class,
				() -> organizationService.renameOrganization(org.getId(), "Should not happen"));
	}

	private Organization createOrganization() {
		return organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
	}

	private User createMember(Organization organization, MembershipRole role) {
		User user = userRepository.saveAndFlush(
				new User(role.name().toLowerCase() + "@acme.test", "hash", role.name()));
		membershipRepository.saveAndFlush(new Membership(organization, user, role));
		return user;
	}

	private void authenticateAs(User user) {
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
	}

}
