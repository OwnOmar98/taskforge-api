package com.taskforge.task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

	Optional<Task> findByIdAndProject_Id(UUID id, UUID projectId);

	// Left join, not inner join: an unassigned task (assignee is null) must
	// still show up in the list, not be silently dropped.
	@Query("select t from Task t left join fetch t.assignee where t.project.id = :projectId")
	List<Task> findByProjectIdWithAssignee(@Param("projectId") UUID projectId);

}
