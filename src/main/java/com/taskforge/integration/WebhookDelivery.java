package com.taskforge.integration;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// webhookId/organizationId are plain columns, same reasoning as Webhook and
// AuditLog: a delivery is an infrastructure record, written once and read by
// the relay by id, not navigated through the domain graph.
@Entity
@Table(name = "webhook_deliveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookDelivery {

	// Assigned here rather than generated on persist: the relay and tests
	// reference a delivery by id before and after it's flushed.
	@Id
	private UUID id;

	@Column(name = "webhook_id", nullable = false)
	private UUID webhookId;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(name = "event_type", nullable = false, length = 100)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private String payload;

	@Column(name = "idempotency_key", nullable = false, unique = true)
	private UUID idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private WebhookDeliveryStatus status;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "claimed_until")
	private Instant claimedUntil;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "delivered_at")
	private Instant deliveredAt;

	public WebhookDelivery(Webhook webhook, String eventType, String payload) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		this.id = UUID.randomUUID();
		this.webhookId = webhook.getId();
		this.organizationId = webhook.getOrganizationId();
		this.eventType = eventType;
		this.payload = payload;
		this.idempotencyKey = UUID.randomUUID();
		this.status = WebhookDeliveryStatus.PENDING;
		this.attempts = 0;
		this.nextAttemptAt = now;
		this.createdAt = now;
	}

	void markDelivered(Instant now) {
		this.status = WebhookDeliveryStatus.DELIVERED;
		this.deliveredAt = now;
		this.claimedUntil = null;
		this.lastError = null;
	}

	void scheduleRetry(Instant nextAttemptAt, String error) {
		this.status = WebhookDeliveryStatus.PENDING;
		this.nextAttemptAt = nextAttemptAt;
		this.claimedUntil = null;
		this.lastError = error;
	}

	void markFailed(String error) {
		this.status = WebhookDeliveryStatus.FAILED;
		this.claimedUntil = null;
		this.lastError = error;
	}

	// A fresh attempt budget, but the SAME idempotency key: it's still the same
	// logical delivery, so a receiver that already processed it can dedupe.
	void requeue(Instant now) {
		this.status = WebhookDeliveryStatus.PENDING;
		this.attempts = 0;
		this.nextAttemptAt = now;
		this.lastError = null;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof WebhookDelivery other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
