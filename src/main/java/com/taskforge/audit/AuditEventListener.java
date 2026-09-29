package com.taskforge.audit;

import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.taskforge.audit.events.EntityDeletedEvent;
import com.taskforge.audit.events.EntityRestoredEvent;
import com.taskforge.audit.events.MemberRoleChangedEvent;
import com.taskforge.audit.events.MembershipRemovedEvent;
import com.taskforge.audit.events.ProjectMemberAddedEvent;
import com.taskforge.audit.events.ProjectMemberRemovedEvent;
import com.taskforge.audit.events.ProjectMemberRoleChangedEvent;
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

	// entityType "Task"/"Project" becomes TASK_DELETED/PROJECT_DELETED. With a
	// soft delete, entity_id here keeps resolving to a real row - with the
	// previous hard delete it silently dangled the moment the task was gone.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onEntityDeleted(EntityDeletedEvent event) {
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(),
				event.entityType().toUpperCase(Locale.ROOT) + "_DELETED", event.entityType(), event.entityId(),
				toJson(event.metadata())));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onEntityRestored(EntityRestoredEvent event) {
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(),
				event.entityType().toUpperCase(Locale.ROOT) + "_RESTORED", event.entityType(), event.entityId(),
				toJson(event.metadata())));
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

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onMembershipRemoved(MembershipRemovedEvent event) {
		String metadata = toJson(Map.of("role", event.role()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "MEMBER_REMOVED", "Membership",
				event.targetUserId(), metadata));
	}

	// ProjectMember* events use "ProjectMember" as the entity type (not
	// "Membership") to stay distinguishable from the org-level events above,
	// even though both ultimately key off targetUserId as the entity id, same
	// as MemberRoleChangedEvent/MembershipRemovedEvent do for their own level.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onProjectMemberAdded(ProjectMemberAddedEvent event) {
		String metadata = toJson(Map.of("projectId", event.projectId(), "role", event.role()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "PROJECT_MEMBER_ADDED",
				"ProjectMember", event.targetUserId(), metadata));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onProjectMemberRoleChanged(ProjectMemberRoleChangedEvent event) {
		String metadata = toJson(
				Map.of("projectId", event.projectId(), "oldRole", event.oldRole(), "newRole", event.newRole()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "PROJECT_MEMBER_ROLE_CHANGED",
				"ProjectMember", event.targetUserId(), metadata));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onProjectMemberRemoved(ProjectMemberRemovedEvent event) {
		String metadata = toJson(Map.of("projectId", event.projectId(), "role", event.role()));
		auditLogRepository.save(new AuditLog(event.organizationId(), event.actorId(), "PROJECT_MEMBER_REMOVED",
				"ProjectMember", event.targetUserId(), metadata));
	}

	private String toJson(Map<String, ?> metadata) {
		return objectMapper.writeValueAsString(metadata);
	}

}
