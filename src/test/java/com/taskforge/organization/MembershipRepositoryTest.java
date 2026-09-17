package com.taskforge.organization;

import jakarta.persistence.EntityManager;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MembershipRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void roleIsStoredAsStringNotOrdinal() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		User user = userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));
		Membership membership = membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.OWNER));

		String storedRole = jdbcTemplate.queryForObject(
				"select role from memberships where id = ?", String.class, membership.getId());

		assertEquals("OWNER", storedRole);
	}

	@Test
	void organizationAndUserAreLazyByDefault() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		User user = userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));
		Membership membership = membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.OWNER));

		entityManager.clear();

		Membership reloaded = membershipRepository.findById(membership.getId()).orElseThrow();

		assertFalse(Hibernate.isInitialized(reloaded.getOrganization()));
		assertFalse(Hibernate.isInitialized(reloaded.getUser()));
	}

	@Test
	void rejectsDuplicateMembership() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		User user = userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));
		membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.OWNER));

		assertThrows(DataIntegrityViolationException.class,
				() -> membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.ADMIN)));
	}

	@Test
	void rejectsNullRole() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		User user = userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));

		assertThrows(DataIntegrityViolationException.class,
				() -> membershipRepository.saveAndFlush(new Membership(org, user, null)));
	}

	@Test
	void cannotDeleteOrganizationWhileMembershipsExist() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		User user = userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));
		membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.OWNER));

		// Hibernate's own pre-flush consistency check catches this before the DELETE
		// ever reaches Postgres - it refuses to remove an entity still referenced by
		// a managed Membership in the same session, rather than relying on the DB's
		// FK constraint to be what stops it.
		assertThrows(InvalidDataAccessApiUsageException.class, () -> {
			organizationRepository.delete(org);
			organizationRepository.flush();
		});
	}

}
