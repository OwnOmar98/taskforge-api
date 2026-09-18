package com.taskforge.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private UserRepository userRepository;

	@Test
	void findsUserByEmail() {
		userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));

		assertTrue(userRepository.findByEmail("owner@acme.test").isPresent());
		assertEquals("Owner", userRepository.findByEmail("owner@acme.test").orElseThrow().getFullName());
	}

	@Test
	void rejectsDuplicateEmail() {
		userRepository.saveAndFlush(new User("owner@acme.test", "hash", "Owner"));

		assertThrows(DataIntegrityViolationException.class,
				() -> userRepository.saveAndFlush(new User("owner@acme.test", "otherHash", "Someone Else")));
	}

	@Test
	void findByEmail_returnsEmptyForUnknownEmail() {
		assertFalse(userRepository.findByEmail("nobody@acme.test").isPresent());
	}

	@Test
	void emailIsNormalizedToLowerCaseOnSave() {
		userRepository.saveAndFlush(new User("Mixed.Case@Acme.test", "hash", "Someone"));

		assertEquals("mixed.case@acme.test",
				userRepository.findByEmail("mixed.case@acme.test").orElseThrow().getEmail());
	}

	@Test
	void rejectsNullEmail() {
		assertThrows(DataIntegrityViolationException.class,
				() -> userRepository.saveAndFlush(new User(null, "hash", "Owner")));
	}

}
