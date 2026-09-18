package com.taskforge.organization;

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

import com.taskforge.common.EmailNormalizer;
import com.taskforge.user.User;

@Entity
@Table(name = "invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Invitation {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@Column(nullable = false)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MembershipRole role;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "invited_by", nullable = false)
	private User invitedBy;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "declined_at")
	private Instant declinedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Invitation(Organization organization, String email, MembershipRole role, String tokenHash,
			User invitedBy, Instant expiresAt) {
		this.organization = organization;
		this.email = EmailNormalizer.normalize(email);
		this.role = role;
		this.tokenHash = tokenHash;
		this.invitedBy = invitedBy;
		this.expiresAt = expiresAt;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	public void accept() {
		this.acceptedAt = Instant.now();
	}

	public void decline() {
		this.declinedAt = Instant.now();
	}

	public boolean isActive() {
		return acceptedAt == null && declinedAt == null && expiresAt.isAfter(Instant.now());
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Invitation other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
