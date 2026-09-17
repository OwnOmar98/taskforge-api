package com.taskforge.organization;

import java.time.Instant;
import java.util.UUID;

import com.taskforge.user.User;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "memberships", uniqueConstraints = @UniqueConstraint(columnNames = { "organization_id", "user_id" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Membership {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private MembershipRole role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public Membership(Organization organization, User user, MembershipRole role) {
		this.organization = organization;
		this.user = user;
		this.role = role;
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
		if (!(o instanceof Membership other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
