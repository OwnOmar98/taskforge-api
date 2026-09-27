package com.taskforge.project;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

	Optional<ProjectMember> findByProject_IdAndUser_Id(UUID projectId, UUID userId);

	@Query("select pm from ProjectMember pm join fetch pm.user where pm.project.id = :projectId")
	Page<ProjectMember> findByProjectIdWithUser(@Param("projectId") UUID projectId, Pageable pageable);

}
