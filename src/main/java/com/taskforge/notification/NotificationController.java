package com.taskforge.notification;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.notification.dto.CursorPageResponse;
import com.taskforge.notification.dto.NotificationResponse;
import com.taskforge.security.CurrentUserId;

// No {orgId}/{projectId} in either route: notifications are inherently
// user-centric, not org-scoped - a user may have notifications from several
// organizations, listed together, same reasoning as /auth/me.
//
// Cursor-based, not the PageResponse/Pageable convention used everywhere
// else: a notification feed gets new rows inserted at the head continuously
// while a caller might be paging through it, which is exactly where offset
// pagination's page-drift problem (skipped or duplicated rows as the offset
// shifts under concurrent inserts) actually bites. It also never needs
// jump-to-page-N or a total count, only "load more" - what cursor pagination
// is for.
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

	private final NotificationService notificationService;

	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	@GetMapping
	public CursorPageResponse<NotificationResponse> list(@CurrentUserId UUID currentUserId,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return notificationService.listNotifications(currentUserId, cursor, size);
	}

	@PatchMapping("/{notificationId}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAsRead(@PathVariable UUID notificationId, @CurrentUserId UUID currentUserId) {
		notificationService.markAsRead(currentUserId, notificationId);
	}

	@PatchMapping("/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllAsRead(@CurrentUserId UUID currentUserId) {
		notificationService.markAllAsRead(currentUserId);
	}

}
