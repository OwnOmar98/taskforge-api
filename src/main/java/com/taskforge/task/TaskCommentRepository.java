package com.taskforge.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskCommentRepository extends JpaRepository<TaskComment, UUID> {

	Optional<TaskComment> findByIdAndTask_Id(UUID id, UUID taskId);

	@Query("select c from TaskComment c join fetch c.author where c.task.id = :taskId order by c.createdAt asc")
	List<TaskComment> findByTaskIdWithAuthor(@Param("taskId") UUID taskId);

}
