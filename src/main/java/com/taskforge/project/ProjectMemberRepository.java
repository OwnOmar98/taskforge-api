package com.taskforge.project;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, UUID> {

	Optional<ProjectMember> findByProject_IdAndUser_Id(UUID projectId, UUID userId);

	// The authorization lookup: a soft-deleted project has no members as far
	// as permissions are concerned, which is what makes its whole subtree
	// (tasks, comments, labels, attachments) unreachable without marking any
	// of it individually. The deletedAt check is explicit for the same reason
	// as TaskRepository's digest query.
	@Query("select pm from ProjectMember pm join pm.project p "
			+ "where p.id = :projectId and pm.user.id = :userId and p.deletedAt is null")
	Optional<ProjectMember> findActiveMembership(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

	@Query("select pm.user.id from ProjectMember pm where pm.project.id = :projectId")
	List<UUID> findUserIdsByProjectId(@Param("projectId") UUID projectId);

	@Query("select pm from ProjectMember pm join fetch pm.user where pm.project.id = :projectId")
	Page<ProjectMember> findByProjectIdWithUser(@Param("projectId") UUID projectId, Pageable pageable);

}
