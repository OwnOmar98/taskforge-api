package com.taskforge.project;

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.taskforge.common.Auditable;
import com.taskforge.organization.Organization;

@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@Column(nullable = false, length = 50)
	private String key;

	@Column(nullable = false)
	private String name;

	@Version
	@Column(nullable = false)
	private Long version;

	public Project(Organization organization, String key, String name) {
		this.organization = organization;
		// Same lesson as EmailNormalizer: a case-variant key ("eng" vs "ENG")
		// must collide with the existing one, not slip past the uniqueness check.
		this.key = key == null ? null : key.strip().toUpperCase(Locale.ROOT);
		this.name = name;
	}

	public void rename(String newName) {
		this.name = newName;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Project other)) {
			return false;
		}
		return id != null && id.equals(other.id);
	}

	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}
