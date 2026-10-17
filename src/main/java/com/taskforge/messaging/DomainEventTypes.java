package com.taskforge.messaging;

// Also the broker routing keys.
public final class DomainEventTypes {

	public static final String TASK_STATUS_CHANGED = "task.status_changed";
	public static final String TASK_ASSIGNED = "task.assigned";
	public static final String TASK_COMMENT_ADDED = "task.comment_added";

	private DomainEventTypes() {
	}

}
