package com.taskforge.notification;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
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
import com.taskforge.task.TaskStatus;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OverdueTaskDigestJobTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

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

	@Test
	void runningTheJobTwiceForTheSameDayProducesExactlyOneDigestPerUser() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User assignee = userRepository.saveAndFlush(new User("late-" + UUID.randomUUID() + "@acme.test", "hash", "L"));

		LocalDate today = LocalDate.now();
		Task overdueOne = new Task(project, "Overdue 1", null, TaskPriority.HIGH, today.minusDays(3));
		overdueOne.assignTo(assignee);
		taskRepository.saveAndFlush(overdueOne);
		Task overdueTwo = new Task(project, "Overdue 2", null, TaskPriority.LOW, today.minusDays(1));
		overdueTwo.assignTo(assignee);
		taskRepository.saveAndFlush(overdueTwo);

		job.runForDate(today);
		job.runForDate(today);

		assertEquals(1, notificationRepository.countByUserId(assignee.getId()));
		Notification digest = notificationRepository.findFirstPageByUserId(assignee.getId(), 10).get(0);
		assertEquals(NotificationType.OVERDUE_TASK_DIGEST, digest.getType());
		assertEquals(today, digest.getDigestDate());
	}

	@Test
	void aTaskThatIsNotOverdueOrAlreadyDoneProducesNoDigest() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme2", "acme2-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User assignee = userRepository
				.saveAndFlush(new User("ontime-" + UUID.randomUUID() + "@acme.test", "hash", "O"));

		LocalDate today = LocalDate.now();
		Task futureTask = new Task(project, "Not due yet", null, TaskPriority.LOW, today.plusDays(3));
		futureTask.assignTo(assignee);
		taskRepository.saveAndFlush(futureTask);

		Task doneTask = new Task(project, "Already finished", null, TaskPriority.LOW, today.minusDays(5));
		doneTask.assignTo(assignee);
		doneTask.changeStatus(TaskStatus.DONE);
		taskRepository.saveAndFlush(doneTask);

		job.runForDate(today);

		assertEquals(0, notificationRepository.countByUserId(assignee.getId()));
	}

	@Test
	void eachOverdueAssigneeGetsTheirOwnSeparateDigest() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme3", "acme3-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User assigneeA = userRepository.saveAndFlush(new User("a-" + UUID.randomUUID() + "@acme.test", "hash", "A"));
		User assigneeB = userRepository.saveAndFlush(new User("b-" + UUID.randomUUID() + "@acme.test", "hash", "B"));

		LocalDate today = LocalDate.now();
		Task taskA = new Task(project, "A's overdue task", null, TaskPriority.LOW, today.minusDays(1));
		taskA.assignTo(assigneeA);
		taskRepository.saveAndFlush(taskA);
		Task taskB = new Task(project, "B's overdue task", null, TaskPriority.LOW, today.minusDays(1));
		taskB.assignTo(assigneeB);
		taskRepository.saveAndFlush(taskB);

		job.runForDate(today);

		assertEquals(1, notificationRepository.countByUserId(assigneeA.getId()));
		assertEquals(1, notificationRepository.countByUserId(assigneeB.getId()));
	}

}
