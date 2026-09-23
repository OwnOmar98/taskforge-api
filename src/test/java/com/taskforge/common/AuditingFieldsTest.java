package com.taskforge.common;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectRepository;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class AuditingFieldsTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private UserRepository userRepository;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void createdByAndCreatedAtArePopulatedFromTheAuthenticatedUser() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User creator = userRepository.saveAndFlush(new User("creator-" + UUID.randomUUID() + "@acme.test", "hash", "Creator"));
		authenticateAs(creator);

		Project project = projectRepository.saveAndFlush(new Project(org, "AUD-" + UUID.randomUUID(), "Audit"));

		assertNotNull(project.getCreatedAt());
		assertNotNull(project.getUpdatedAt());
		assertEquals(creator.getId(), project.getCreatedBy());
	}

	@Test
	void createdByIsNullWhenThereIsNoAuthenticatedUser() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));

		Project project = projectRepository.saveAndFlush(new Project(org, "AUD-" + UUID.randomUUID(), "Audit"));

		assertNotNull(project.getCreatedAt());
		assertNull(project.getCreatedBy());
	}

	@Test
	void updatedAtAdvancesOnModificationButCreatedByDoesNot() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User creator = userRepository.saveAndFlush(new User("creator2-" + UUID.randomUUID() + "@acme.test", "hash", "Creator"));
		authenticateAs(creator);

		Project project = projectRepository.saveAndFlush(new Project(org, "AUD-" + UUID.randomUUID(), "Audit"));
		var firstUpdatedAt = project.getUpdatedAt();

		project.rename("Renamed");
		projectRepository.saveAndFlush(project);

		assertTrue(project.getUpdatedAt().isAfter(firstUpdatedAt) || project.getUpdatedAt().equals(firstUpdatedAt));
		assertEquals(creator.getId(), project.getCreatedBy());
	}

	private void authenticateAs(User user) {
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
	}

}
