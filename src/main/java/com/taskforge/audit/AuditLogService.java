package com.taskforge.audit;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.audit.dto.AuditLogResponse;
import com.taskforge.common.PageResponse;

@Service
public class AuditLogService {

	private final AuditLogRepository auditLogRepository;
	private final AuditLogMapper auditLogMapper;

	public AuditLogService(AuditLogRepository auditLogRepository, AuditLogMapper auditLogMapper) {
		this.auditLogRepository = auditLogRepository;
		this.auditLogMapper = auditLogMapper;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional(readOnly = true)
	public PageResponse<AuditLogResponse> listAuditLogs(UUID organizationId, Pageable pageable) {
		Page<AuditLog> page = auditLogRepository.findByOrganizationId(organizationId, pageable);
		return PageResponse.from(page.map(auditLogMapper::toResponse));
	}

}
