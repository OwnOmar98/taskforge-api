package com.taskforge.notification;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.taskforge.notification.dto.NotificationResponse;
import com.taskforge.realtime.EventReplaySource;
import com.taskforge.realtime.UserEvent;
import com.taskforge.realtime.UserEventType;

// Notifications are the one replayable event type: each is a persisted row,
// so a reconnecting client's Last-Event-ID (a notification id) can be
// resolved back to a position in the user's notification history.
@Component
public class NotificationReplaySource implements EventReplaySource {

	private final NotificationService notificationService;

	public NotificationReplaySource(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	// The same shape GET /notifications returns, with the notification id as
	// the event id - built in one place so live pushes and replays can't
	// drift apart.
	static UserEvent toEvent(NotificationResponse notification) {
		return UserEvent.replayable(UserEventType.NOTIFICATION, notification.id().toString(), notification);
	}

	@Override
	public boolean isValidEventId(String eventId) {
		try {
			UUID.fromString(eventId);
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}

	@Override
	public List<UserEvent> eventsAfter(UUID userId, String eventId, int limit) {
		return notificationService.listNewerThan(userId, UUID.fromString(eventId), limit)
				.stream()
				.map(NotificationReplaySource::toEvent)
				.toList();
	}

}
