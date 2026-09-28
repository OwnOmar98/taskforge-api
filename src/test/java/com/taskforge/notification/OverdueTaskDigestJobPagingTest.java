package com.taskforge.notification;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectRepository;
import com.taskforge.task.Task;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskRepository;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Separate from OverdueTaskDigestJobTest: page-size needs its own
// @DynamicPropertySource override (2 here, the real 500 default there), and
// this proves the job's paging loop actually spans multiple pages rather
// than just happening to fit everything in one, which a handful of tasks
// under the real default page size would never exercise.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OverdueTaskDigestJobPagingTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@DynamicPropertySource
	static void pageSize(DynamicPropertyRegistry registry) {
		registry.add("app.notification.overdue-digest.page-size", () -> "2");
	}

	@Autowired
	private OverdueTaskDigestJob job;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void oneAssigneesOverdueTasksSpanningMultiplePagesStillProduceASingleCompleteDigest() {
		Organization org = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-paging-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User assignee = userRepository
				.saveAndFlush(new User("late-paging-" + UUID.randomUUID() + "@acme.test", "hash", "L"));

		LocalDate today = LocalDate.now();
		// 5 overdue tasks against a page size of 2: the job's collection loop
		// must run across 3 pages to see all of them.
		for (int i = 0; i < 5; i++) {
			Task overdue = new Task(project, "Overdue " + i, null, TaskPriority.LOW, today.minusDays(1));
			overdue.assignTo(assignee);
			taskRepository.saveAndFlush(overdue);
		}

		job.runForDate(today);

		assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
		Notification digest = notificationRepository.findFirstPageByUserId(assignee.getId(), 10).get(0);
		JsonNode payload = objectMapper.readTree(digest.getPayload());
		assertEquals(5, payload.get("taskCount").asInt());
		assertEquals(5, payload.get("taskIds").size());
	}

}
