package com.taskforge.task;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelRepository extends JpaRepository<Label, UUID> {

	boolean existsByOrganization_IdAndName(UUID organizationId, String name);

	Page<Label> findByOrganization_Id(UUID organizationId, Pageable pageable);

	Optional<Label> findByIdAndOrganization_Id(UUID id, UUID organizationId);

}
