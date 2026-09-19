package com.taskforge.media;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.taskforge.storage.MediaVisibility;
import com.taskforge.user.User;

// Deliberately standalone: no reference to Task, Comment, or anything that
// links to it. Whatever feature attaches this to something else does so
// through its own thin join table, so this stays reusable without changes.
@Entity
@Table(name = "media")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Media {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "storage_key", nullable = false, unique = true)
	private String storageKey;

	@Column(nullable = false)
	private String filename;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MediaVisibility visibility;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "uploaded_by", nullable = false)
	private User uploadedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Media(String storageKey, String filename, String contentType, long sizeBytes,
			MediaVisibility visibility, User uploadedBy) {
		this.storageKey = storageKey;
		this.filename = filename;
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.visibility = visibility;
		this.uploadedBy = uploadedBy;
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
		if (!(o instanceof Media other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
