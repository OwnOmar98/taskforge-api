package com.taskforge.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	boolean existsByOrganization_IdAndKey(UUID organizationId, String key);

	Page<Project> findByOrganization_Id(UUID organizationId, Pageable pageable);

	Optional<Project> findByIdAndOrganization_Id(UUID id, UUID organizationId);

	// Native for the same reason as TaskRepository.findDeletedByIdAndProjectId.
	@Query(value = "select * from projects where id = :id and organization_id = :organizationId "
			+ "and deleted_at is not null", nativeQuery = true)
	Optional<Project> findDeletedByIdAndOrganizationId(@Param("id") UUID id,
			@Param("organizationId") UUID organizationId);

}
