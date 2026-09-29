package com.taskforge.notification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.notification.dto.CursorPageResponse;
import com.taskforge.notification.dto.NotificationResponse;

// No @PreAuthorize anywhere here: every method is already scoped to the
// caller's own userId, so there's no other-user permission decision to make
// - unlike Task/Project/Organization resources, which need a real
// membership/role check.
@Service
public class NotificationService {

	private final NotificationRepository notificationRepository;
	private final NotificationMapper notificationMapper;

	public NotificationService(NotificationRepository notificationRepository, NotificationMapper notificationMapper) {
		this.notificationRepository = notificationRepository;
		this.notificationMapper = notificationMapper;
	}

	@Transactional(readOnly = true)
	public CursorPageResponse<NotificationResponse> listNotifications(UUID userId, String cursor, int size) {
		// Fetch one extra row: getting size+1 back means there's a next page,
		// without a separate COUNT(*) query to find out.
		List<Notification> rows = cursor == null ? notificationRepository.findFirstPageByUserId(userId, size + 1)
				: fetchAfter(userId, NotificationCursor.decode(cursor), size + 1);

		boolean hasMore = rows.size() > size;
		List<Notification> page = hasMore ? rows.subList(0, size) : rows;

		String nextCursor = hasMore
				? new NotificationCursor(page.get(page.size() - 1).getCreatedAt(), page.get(page.size() - 1).getId())
						.encode()
				: null;

		return new CursorPageResponse<>(page.stream().map(notificationMapper::toResponse).toList(), nextCursor, hasMore);
	}

	// Empty, not an error, when the anchor isn't one of this user's
	// notifications: there's nothing to replay from, and a stale or foreign
	// Last-Event-ID shouldn't block the stream from opening.
	@Transactional(readOnly = true)
	public List<NotificationResponse> listNewerThan(UUID userId, UUID afterNotificationId, int limit) {
		return notificationRepository.findByIdAndUserId(afterNotificationId, userId)
				.map(anchor -> notificationRepository
						.findNewerThanByUserId(userId, anchor.getCreatedAt(), anchor.getId(), limit)
						.stream()
						.map(notificationMapper::toResponse)
						.toList())
				.orElse(List.of());
	}

	private List<Notification> fetchAfter(UUID userId, NotificationCursor cursor, int limit) {
		return notificationRepository.findNextPageByUserId(userId, cursor.createdAt(), cursor.id(), limit);
	}

	@Transactional
	public void markAsRead(UUID userId, UUID notificationId) {
		Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Notification not found"));

		notification.markAsRead();
	}

	@Transactional
	public void markAllAsRead(UUID userId) {
		notificationRepository.markAllAsRead(userId, Instant.now());
	}

}
