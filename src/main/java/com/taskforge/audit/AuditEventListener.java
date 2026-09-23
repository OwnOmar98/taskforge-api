package com.taskforge.audit;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.MemberRoleChangedEvent;
import com.taskforge.audit.events.TaskStatusChangedEvent;

import tools.jackson.databind.ObjectMapper;

// AFTER_COMMIT: an audit log entry should only exist for a change that
// actually happened, not one whose enclosing transaction later rolled back.
// That means this runs outside the original transaction, so persisting the
// AuditLog itself needs its own, separate one (REQUIRES_NEW).
@Component
public class AuditEventListener {

	private final AuditLogRepository auditLogRepository;
	private final ObjectMapper objectMapper;

	public AuditEventListener(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
		this.auditLogRepository = auditLogRepository;
		this.objectMapper = objectMapper;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onTaskStatusChanged(TaskStatusChangedEvent event) {
		String metadata = toJson(Map.of("oldStatus", event.oldStatus(), "newStatus", event.newStatus()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "TASK_STATUS_CHANGED", "Task",
				event.taskId(), metadata));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onMemberRoleChanged(MemberRoleChangedEvent event) {
		String metadata = toJson(Map.of("oldRole", event.oldRole(), "newRole", event.newRole()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "MEMBER_ROLE_CHANGED",
				"Membership", event.targetUserId(), metadata));
	}

	private String toJson(Map<String, ?> metadata) {
		return objectMapper.writeValueAsString(metadata);
	}

}
