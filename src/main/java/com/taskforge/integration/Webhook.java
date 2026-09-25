package com.taskforge.integration;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import com.taskforge.security.EncryptedStringConverter;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// organizationId is a plain column, not a relationship - same reasoning as
// AuditLog/Notification: this is infrastructure config, not part of the
// live domain graph.
@Entity
@Table(name = "webhooks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Webhook {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(nullable = false, length = 2048)
	private String url;

	// Generated server-side (SecureTokenGenerator.generateRawToken()), never
	// client-supplied. Stored in retrievable form, unlike refresh/invitation
	// tokens (which store only a hash): those only ever need to verify a
	// presented value, but this needs to be read back on every delivery to
	// compute that request's HMAC signature. Encrypted at rest (not just
	// retrievable) via EncryptedStringConverter, so a DB-only compromise
	// doesn't also hand over every org's webhook secret.
	@Column(nullable = false)
	@Convert(converter = EncryptedStringConverter.class)
	private String secret;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Webhook(UUID organizationId, String url, String secret) {
		this.organizationId = organizationId;
		this.url = url;
		this.secret = secret;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Webhook other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
