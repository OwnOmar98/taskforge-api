package com.taskforge.realtime;

import java.util.List;
import java.util.UUID;

// Implemented by whichever feature owns persisted, replayable events
// (notification, today) - an interface so this generic package never
// depends on a feature package, while the feature depends on it to publish.
//
// Event ids are currently only ever notification ids, so one source owns
// every Last-Event-ID. A second persisted event type would need its ids
// namespaced (e.g. "<type>:<id>") so the right source can be picked.
public interface EventReplaySource {

	// Checked before the stream is registered, so a malformed id is a clean 400.
	boolean isValidEventId(String eventId);

	// Oldest first. Empty (not an error) when the id isn't one of this user's.
	List<UserEvent> eventsAfter(UUID userId, String eventId, int limit);

}
