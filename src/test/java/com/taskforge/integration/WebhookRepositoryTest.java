package com.taskforge.integration;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

// Unlike other @DataJpaTest slices in this project, this one needs
// @ActiveProfiles("test") - the encryption converter requires
// app.encryption-key, which (like app.jwt.secret) only exists in
// profile-specific config, not the profile-less base application.yml.
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class WebhookRepositoryTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private WebhookRepository webhookRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void secretIsEncryptedAtRestAndDecryptedOnReload() {
		Organization organization = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		String plainSecret = "plaintext-webhook-secret";
		Webhook webhook = webhookRepository
				.saveAndFlush(new Webhook(organization.getId(), "https://example.test/hook", plainSecret));

		String storedValue = jdbcTemplate.queryForObject(
				"select secret from webhooks where id = ?", String.class, webhook.getId());
		assertNotEquals(plainSecret, storedValue, "the raw column value must never be the plaintext secret");

		entityManager.clear();

		Webhook reloaded = webhookRepository.findById(webhook.getId()).orElseThrow();
		assertEquals(plainSecret, reloaded.getSecret(), "decrypting on read must round-trip to the original secret");
	}

	@Test
	void twoEncryptionsOfTheSameSecretProduceDifferentCiphertext() {
		Organization organization = organizationRepository.saveAndFlush(new Organization("Acme", "acme"));
		String plainSecret = "same-secret-both-times";

		Webhook first = webhookRepository
				.saveAndFlush(new Webhook(organization.getId(), "https://example.test/hook-a", plainSecret));
		Webhook second = webhookRepository
				.saveAndFlush(new Webhook(organization.getId(), "https://example.test/hook-b", plainSecret));

		String firstStored = jdbcTemplate.queryForObject(
				"select secret from webhooks where id = ?", String.class, first.getId());
		String secondStored = jdbcTemplate.queryForObject(
				"select secret from webhooks where id = ?", String.class, second.getId());

		// A fresh random IV per encryption means identical plaintexts never
		// produce identical ciphertext - a DB leak can't reveal that two
		// webhooks happen to share a secret.
		assertNotEquals(firstStored, secondStored);
	}

}
