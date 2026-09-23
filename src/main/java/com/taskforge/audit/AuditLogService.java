package com.taskforge.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.dto.AuditLogResponse;
import com.taskforge.common.PageResponse;

import tools.jackson.databind.ObjectMapper;

@Service
public class AuditLogService {

	private final AuditLogRepository auditLogRepository;
	private final ObjectMapper objectMapper;

	public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
		this.auditLogRepository = auditLogRepository;
		this.objectMapper = objectMapper;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional(readOnly = true)
	public PageResponse<AuditLogResponse> listAuditLogs(UUID organizationId, Pageable pageable) {
		Page<AuditLog> page = auditLogRepository.findByOrganizationId(organizationId, pageable);
		return PageResponse.from(page.map(this::toResponse));
	}

	private AuditLogResponse toResponse(AuditLog auditLog) {
		// Parsed back into a plain Object rather than left as a raw JSON string,
		// so it serializes as nested JSON in the response instead of an
		// escaped string.
		Object metadata = auditLog.getMetadata() == null ? null
				: objectMapper.readValue(auditLog.getMetadata(), Object.class);
		return new AuditLogResponse(auditLog.getId(), auditLog.getAction(), auditLog.getEntityType(),
				auditLog.getEntityId(), auditLog.getActorId(), metadata, auditLog.getCreatedAt());
	}

}
