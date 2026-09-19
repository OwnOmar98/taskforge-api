package com.taskforge.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LabelRepository extends JpaRepository<Label, UUID> {

	boolean existsByOrganization_IdAndName(UUID organizationId, String name);

	List<Label> findByOrganization_Id(UUID organizationId);

	Optional<Label> findByIdAndOrganization_Id(UUID id, UUID organizationId);

}
