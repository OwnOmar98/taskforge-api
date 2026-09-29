package com.taskforge.common.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.taskforge.audit.AuditLog;
import com.taskforge.audit.AuditLogMapper;
import com.taskforge.audit.AuditLogMapperImpl;
import com.taskforge.audit.dto.AuditLogResponse;
import com.taskforge.organization.Invitation;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationMapper;
import com.taskforge.organization.OrganizationMapperImpl;
import com.taskforge.organization.dto.InvitationResponse;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMapper;
import com.taskforge.project.ProjectMapperImpl;
import com.taskforge.project.dto.ProjectResponse;
import com.taskforge.task.Task;
import com.taskforge.task.TaskMapper;
import com.taskforge.task.TaskMapperImpl;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.TaskSummaryProjection;
import com.taskforge.user.User;

import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

// The generated implementations, called directly - no Spring context. The
// controller tests already pin the full JSON shapes; these cover the mapping
// decisions that are easy to get subtly wrong: nested sources, null nested
// associations, extra parameters, and the jsonb qualifier.
class MapperTest {

	private final TaskMapper taskMapper = new TaskMapperImpl();
	private final ProjectMapper projectMapper = new ProjectMapperImpl();
	private final OrganizationMapper organizationMapper = new OrganizationMapperImpl();
	private final AuditLogMapper auditLogMapper = new AuditLogMapperImpl(
			new JsonColumnMapper(JsonMapper.builder().build()));

	@Test
	void anUnassignedTaskMapsToNullAssigneeFieldsInsteadOfThrowing() {
		Task task = new Task(project(), "Unassigned", null, TaskPriority.LOW, null);
		UUID projectId = UUID.randomUUID();

		TaskResponse response = taskMapper.toResponse(task, projectId);

		assertNull(response.assigneeId());
		assertNull(response.assigneeEmail());
		assertEquals(projectId, response.projectId());
	}

	@Test
	void anAssignedTaskExposesTheAssigneesIdAndEmail() {
		User assignee = user("assignee@acme.test");
		Task task = new Task(project(), "Assigned", null, TaskPriority.LOW, null);
		task.assignTo(assignee);

		TaskSummaryProjection summary = taskMapper.toSummary(task, List.of("a", "b"));

		assertEquals(assignee.getId(), summary.assigneeId());
		assertEquals("assignee@acme.test", summary.assigneeEmail());
		assertEquals(List.of("a", "b"), summary.labelNames());
	}

	@Test
	void aProjectExposesItsOrganizationIdFromTheNestedAssociation() {
		Project project = project();

		ProjectResponse response = projectMapper.toResponse(project);

		assertEquals(project.getOrganization().getId(), response.organizationId());
	}

	@Test
	void anInvitationResponseCarriesTheRawTokenNotTheStoredHash() {
		Invitation invitation = new Invitation(organization(), "invitee@acme.test", MembershipRole.MEMBER,
				"stored-hash", user("inviter@acme.test"), Instant.now());

		InvitationResponse response = organizationMapper.toResponse(invitation, "raw-token");

		assertEquals("raw-token", response.token());
	}

	// The case the qualifiedByName exists for: without it, MapStruct would
	// assign the String straight to the Object field, and the API would return
	// the metadata as one escaped JSON string instead of a nested object.
	@Test
	void jsonbMetadataIsParsedIntoAStructureNotPassedThroughAsAString() {
		AuditLog auditLog = new AuditLog(UUID.randomUUID(), UUID.randomUUID(), "TASK_STATUS_CHANGED", "Task",
				UUID.randomUUID(), "{\"from\":\"TODO\",\"to\":\"DONE\"}");

		AuditLogResponse response = auditLogMapper.toResponse(auditLog);

		Map<?, ?> metadata = assertInstanceOf(Map.class, response.metadata());
		assertEquals("DONE", metadata.get("to"));
	}

	@Test
	void nullJsonbMetadataStaysNull() {
		AuditLog auditLog = new AuditLog(UUID.randomUUID(), UUID.randomUUID(), "TASK_STATUS_CHANGED", "Task",
				UUID.randomUUID(), null);

		assertNull(auditLogMapper.toResponse(auditLog).metadata());
	}

	private Organization organization() {
		Organization organization = new Organization("Acme", "acme");
		ReflectionTestUtils.setField(organization, "id", UUID.randomUUID());
		return organization;
	}

	private Project project() {
		return new Project(organization(), "ENG", "Engine");
	}

	private User user(String email) {
		User user = new User(email, "hash", "Test User");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

}
