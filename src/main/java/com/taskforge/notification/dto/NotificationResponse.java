package com.taskforge.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.taskforge.notification.NotificationType;

public record NotificationResponse(UUID id, NotificationType type, Object payload, Instant readAt,
		Instant createdAt) {
}
