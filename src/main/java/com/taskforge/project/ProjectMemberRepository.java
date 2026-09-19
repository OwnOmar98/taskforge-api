package com.taskforge.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

	Optional<ProjectMember> findByProject_IdAndUser_Id(UUID projectId, UUID userId);

	@Query("select pm from ProjectMember pm join fetch pm.user where pm.project.id = :projectId")
	List<ProjectMember> findByProjectIdWithUser(@Param("projectId") UUID projectId);

}
