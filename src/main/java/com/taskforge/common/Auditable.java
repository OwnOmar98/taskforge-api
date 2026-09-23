package com.taskforge.common;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import lombok.Getter;

// Infrastructure-level auditing: cheap, automatic, populated on every entity
// that extends this. Kept deliberately separate from AuditLog, which is a
// selective, queryable business record of specific domain events instead.
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class Auditable {

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	// Nullable: rows created before this column existed have no reliable
	// historical author, and entities persisted outside an authenticated
	// request (test data seeded directly via a repository) have none either.
	@CreatedBy
	@Column(name = "created_by")
	private UUID createdBy;

}
