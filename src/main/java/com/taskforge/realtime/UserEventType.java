package com.taskforge.realtime;

// The single registry of every event name the stream can carry. Adding a
// new kind of real-time event is one constant here plus one
// UserEventPublisher call at the point where it happens - nothing else
// (Redis subscription, stream endpoint, registry) needs to know about it.
public enum UserEventType {

	// Persisted and replayable: carries the notification id as its event id.
	NOTIFICATION("notification"),
	// A signal to refetch, not a record: nothing to replay if missed.
	TASK_UPDATED("task.updated"),
	// Signal: the task left the recipient's list. A restore is sent as
	// task.updated - it's back, refetch.
	TASK_DELETED("task.deleted");

	private final String wireName;

	UserEventType(String wireName) {
		this.wireName = wireName;
	}

	// What clients see as the SSE "event:" field - dotted lowercase, decoupled
	// from the Java constant name so either can change independently.
	public String wireName() {
		return wireName;
	}

}
