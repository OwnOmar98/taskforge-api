package com.taskforge.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	boolean existsByOrganization_IdAndKey(UUID organizationId, String key);

	Page<Project> findByOrganization_Id(UUID organizationId, Pageable pageable);

	Optional<Project> findByIdAndOrganization_Id(UUID id, UUID organizationId);

}
