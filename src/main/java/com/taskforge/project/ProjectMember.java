package com.taskforge.project;

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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.taskforge.user.User;

@Entity
@Table(name = "project_members", uniqueConstraints = @UniqueConstraint(columnNames = { "project_id", "user_id" }))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMember {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 50)
	private ProjectMemberRole role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public ProjectMember(Project project, User user, ProjectMemberRole role) {
		this.project = project;
		this.user = user;
		this.role = role;
	}

	@PrePersist
	void onCreate() {
		this.createdAt = Instant.now();
	}

	public void changeRole(ProjectMemberRole newRole) {
		this.role = newRole;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ProjectMember other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
