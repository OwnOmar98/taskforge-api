package com.taskforge.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	boolean existsByOrganization_IdAndKey(UUID organizationId, String key);

	List<Project> findByOrganization_Id(UUID organizationId);

	Optional<Project> findByIdAndOrganization_Id(UUID id, UUID organizationId);

}
