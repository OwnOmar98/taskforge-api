package com.taskforge.organization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrganizationRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationRepository organizationRepository;

	@Test
	void savesAndFindsOrganization() {
		Organization saved = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));

		assertNotNull(saved.getId());
		assertNotNull(saved.getCreatedAt());
		assertTrue(organizationRepository.findById(saved.getId()).isPresent());
	}

	@Test
	void rejectsDuplicateSlug() {
		organizationRepository.saveAndFlush(new Organization("Acme", "acme"));

		assertThrows(DataIntegrityViolationException.class,
				() -> organizationRepository.saveAndFlush(new Organization("Acme Two", "acme")));
	}

	@Test
	void rejectsNullName() {
		assertThrows(DataIntegrityViolationException.class,
				() -> organizationRepository.saveAndFlush(new Organization(null, "acme")));
	}

}
