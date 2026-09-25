package com.taskforge.task;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

	Optional<Task> findByIdAndProject_Id(UUID id, UUID projectId);

	// assignee is a @ManyToOne - fetching it here doesn't multiply rows, so it's
	// safe to combine with Pageable's LIMIT/OFFSET. labels is a @ManyToMany and
	// deliberately NOT included here: fetch-joining a collection alongside
	// pagination is a well-known trap (Hibernate has to paginate in memory,
	// silently defeating the point) - see findLabelNamesForTasks instead.
	@EntityGraph(attributePaths = "assignee")
	@Override
	Page<Task> findAll(Specification<Task> spec, Pageable pageable);

	@Query("select t.id as taskId, l.name as labelName from Task t join t.labels l where t.id in :taskIds")
	List<TaskLabelRow> findLabelNamesForTasks(@Param("taskIds") Collection<UUID> taskIds);

	// assignee/project/project.organization are all fetched together: the
	// digest job groups the result by (assignee, organization), so touching
	// either lazily per row while iterating would be exactly the N+1 this
	// project has already had to fix once (see PR15).
	@EntityGraph(attributePaths = { "assignee", "project", "project.organization" })
	List<Task> findByDueDateBeforeAndStatusNotAndAssigneeIsNotNull(LocalDate date, TaskStatus status);

	interface TaskLabelRow {

		UUID getTaskId();

		String getLabelName();

	}

}
