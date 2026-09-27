package com.taskforge.task;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.common.PageResponse;
import com.taskforge.common.exception.ConflictException;
import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMember;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectRepository;
import com.taskforge.task.dto.TaskResponse;
import com.taskforge.task.dto.TaskSummaryProjection;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TaskServiceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private TaskService taskService;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectMemberRepository projectMemberRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MeterRegistry meterRegistry;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void canAssignTaskToAProjectMember() {
		Project project = createProject();
		User lead = createProjectMember(project, ProjectMemberRole.LEAD);
		User contributor = createProjectMember(project, ProjectMemberRole.CONTRIBUTOR);

		authenticateAs(lead);
		TaskResponse task = taskService.createTask(project.getId(), "Ship it", null, TaskPriority.HIGH, null,
				contributor.getId(), lead.getId());

		assertEquals(contributor.getId(), task.assigneeId());
	}

	@Test
	void cannotAssignTaskToSomeoneOutsideTheProject() {
		Project project = createProject();
		User lead = createProjectMember(project, ProjectMemberRole.LEAD);
		User outsider = userRepository.saveAndFlush(new User("outsider@acme.test", "hash", "Outsider"));

		authenticateAs(lead);

		assertThrows(ConflictException.class, () -> taskService.createTask(project.getId(), "Ship it", null,
				TaskPriority.HIGH, null, outsider.getId(), lead.getId()));
	}

	@Test
	void taskCanBeCreatedWithoutAnAssignee() {
		Project project = createProject();
		User lead = createProjectMember(project, ProjectMemberRole.LEAD);

		authenticateAs(lead);
		TaskResponse task = taskService.createTask(project.getId(), "Unassigned work", null, TaskPriority.LOW, null,
				null, lead.getId());

		assertNull(task.assigneeId());
	}

	@Test
	void creatingATaskRecordsItsLatency() {
		Project project = createProject();
		User lead = createProjectMember(project, ProjectMemberRole.LEAD);
		authenticateAs(lead);

		long countBefore = meterRegistry.find("task.creation.duration").timer() == null ? 0
				: meterRegistry.find("task.creation.duration").timer().count();

		taskService.createTask(project.getId(), "Timed task", null, TaskPriority.LOW, null, null, lead.getId());

		Timer timer = meterRegistry.find("task.creation.duration").timer();
		assertNotNull(timer, "creating a task should have registered the timer");
		assertEquals(countBefore + 1, timer.count());
	}

	@Test
	void nonProjectMemberCannotCreateTasks() {
		Project project = createProject();
		User outsider = userRepository.saveAndFlush(new User("outsider2@acme.test", "hash", "Outsider"));

		authenticateAs(outsider);

		assertThrows(AccessDeniedException.class, () -> taskService.createTask(project.getId(), "Ship it", null,
				TaskPriority.HIGH, null, null, outsider.getId()));
	}

	@Test
	void anyProjectRoleCanListTasks() {
		Project project = createProject();
		User viewer = createProjectMember(project, ProjectMemberRole.VIEWER);

		authenticateAs(viewer);
		PageResponse<TaskSummaryProjection> tasks = taskService.listTasks(project.getId(), null, null, null, null,
				PageRequest.of(0, 20));

		assertEquals(0, tasks.content().size());
	}

	private Project createProject() {
		Organization organization = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		return projectRepository.saveAndFlush(new Project(organization, "ENG-" + UUID.randomUUID(), "Engine"));
	}

	private User createProjectMember(Project project, ProjectMemberRole role) {
		User user = userRepository.saveAndFlush(
				new User(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@acme.test", "hash", role.name()));
		membershipRepository.saveAndFlush(new Membership(project.getOrganization(), user, MembershipRole.MEMBER));
		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, role));
		return user;
	}

	private void authenticateAs(User user) {
		SecurityContextHolder.getContext()
				.setAuthentication(new UsernamePasswordAuthenticationToken(user.getId(), null, List.of()));
	}

}
