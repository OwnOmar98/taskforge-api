package com.taskforge.realtime;

// id is set only for events backed by a persisted row an EventReplaySource
// can find again (notifications). Signal events leave it null on purpose:
// per the SSE spec an event with no id doesn't change the client's
// last-event-id, so signals interleaved on the same stream never disturb
// the Last-Event-ID a reconnect replays from.
public record UserEvent(UserEventType type, String id, Object data) {

	public static UserEvent replayable(UserEventType type, String id, Object data) {
		return new UserEvent(type, id, data);
	}

	// Missed signals aren't replayed - a reconnecting client refetches the
	// state they point at instead.
	public static UserEvent signal(UserEventType type, Object data) {
		return new UserEvent(type, null, data);
	}

}
