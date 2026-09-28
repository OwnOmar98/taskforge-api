package com.taskforge.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Same reasoning as AuditLog: userId/organizationId are plain columns, not
// relationships. A notification outliving the user/org it references isn't
// a real scenario worth designing around, but the pattern of standing on its
// own rather than joining into the live entity graph is worth staying
// consistent with.
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private NotificationType type;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String payload;

	@Column(name = "read_at")
	private Instant readAt;

	// Only OverdueTaskDigestJob sets this, via a native insert - mapped here
	// so reads still populate it.
	@Column(name = "digest_date")
	private LocalDate digestDate;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Notification(UUID userId, UUID organizationId, NotificationType type, String payload) {
		this.userId = userId;
		this.organizationId = organizationId;
		this.type = type;
		this.payload = payload;
	}

	// Truncated to Postgres's own microsecond precision so the value on this
	// in-memory instance - pushed to real-time streams straight after the
	// save - matches what reading the row back later returns, instead of
	// differing in the last few (rounded-off) digits.
	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public void markAsRead() {
		if (this.readAt == null) {
			this.readAt = Instant.now();
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Notification other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
