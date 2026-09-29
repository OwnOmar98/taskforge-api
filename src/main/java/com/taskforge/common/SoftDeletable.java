package com.taskforge.common;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

import lombok.Getter;

// Soft deletion is a state change on the row, not a JPA remove: markDeleted()
// is a plain field update, so no cascade fires and whatever hangs off the row
// (a task's comments and attachments, a project's tasks) stays intact for
// restore(), unreachable only because every path to it goes through this row.
// repository.delete() keeps meaning a real, cascading delete.
//
// Each subclass still needs its own @SQLRestriction("deleted_at is null") -
// declared on the entity itself, where the filtering is visible.
@Getter
@MappedSuperclass
public abstract class SoftDeletable extends Auditable {

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	public void markDeleted(UUID actorId) {
		if (deletedAt == null) {
			this.deletedAt = Instant.now();
			this.deletedBy = actorId;
		}
	}

	public void restore() {
		this.deletedAt = null;
		this.deletedBy = null;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

}
