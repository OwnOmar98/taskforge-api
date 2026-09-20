package com.taskforge.task;

import java.util.UUID;

import org.springframework.data.jpa.domain.Specification;

public final class TaskSpecifications {

	private TaskSpecifications() {
	}

	public static Specification<Task> belongsToProject(UUID projectId) {
		return (root, query, cb) -> cb.equal(root.get("project").get("id"), projectId);
	}

	public static Specification<Task> hasStatus(TaskStatus status) {
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<Task> hasPriority(TaskPriority priority) {
		return (root, query, cb) -> cb.equal(root.get("priority"), priority);
	}

	public static Specification<Task> hasAssignee(UUID assigneeId) {
		return (root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId);
	}

	// distinct(true): joining to a @ManyToMany multiplies a task's row per
	// matching label, which would otherwise duplicate it in the result page.
	public static Specification<Task> hasLabel(UUID labelId) {
		return (root, query, cb) -> {
			query.distinct(true);
			return cb.equal(root.join("labels").get("id"), labelId);
		};
	}

}
