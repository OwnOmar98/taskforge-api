package com.taskforge.audit;

import java.util.UUID;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.taskforge.audit.dto.AuditLogResponse;
import com.taskforge.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Audit Logs")
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/audit-logs")
public class AuditLogController {

	private final AuditLogService auditLogService;

	public AuditLogController(AuditLogService auditLogService) {
		this.auditLogService = auditLogService;
	}

	@Operation(summary = "List audit log entries for an organization")
	@GetMapping
	public PageResponse<AuditLogResponse> list(@PathVariable UUID orgId,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt",
					direction = Sort.Direction.DESC) Pageable pageable) {
		return auditLogService.listAuditLogs(orgId, pageable);
	}

}
