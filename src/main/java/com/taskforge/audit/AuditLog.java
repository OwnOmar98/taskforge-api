package com.taskforge.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// A selective, queryable business record of specific domain events - unlike
// Auditable, which is infrastructure-level and populated automatically on
// every entity. Deliberately not linked back to its subject via a JPA
// relationship: the subject may since have been deleted, and the log should
// still stand on its own.
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(name = "actor_id")
	private UUID actorId;

	@Column(nullable = false, length = 100)
	private String action;

	@Column(name = "entity_type", nullable = false, length = 100)
	private String entityType;

	@Column(name = "entity_id", nullable = false)
	private UUID entityId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String metadata;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public AuditLog(UUID organizationId, UUID actorId, String action, String entityType, UUID entityId,
			String metadata) {
		this.organizationId = organizationId;
		this.actorId = actorId;
		this.action = action;
		this.entityType = entityType;
		this.entityId = entityId;
		this.metadata = metadata;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

}
