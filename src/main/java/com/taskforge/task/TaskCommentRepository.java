package com.taskforge.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

	Optional<TaskComment> findByIdAndTask_Id(UUID id, UUID taskId);

	// Unpaged - kept for the cascade-behavior test's plain existence check, which
	// has no use for a Pageable and predates the paginated listing endpoint.
	@Query("select c from TaskComment c join fetch c.author where c.task.id = :taskId order by c.createdAt asc")
	List<TaskComment> findByTaskIdWithAuthor(@Param("taskId") UUID taskId);

	@Query("select c from TaskComment c join fetch c.author where c.task.id = :taskId")
	Page<TaskComment> findByTaskIdWithAuthor(@Param("taskId") UUID taskId, Pageable pageable);

}
