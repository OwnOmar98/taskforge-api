package com.taskforge.notification;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.TaskforgeApiApplication;
import com.taskforge.organization.Organization;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.Task;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.TaskRepository;
import com.taskforge.user.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The first digest is deleted before instance B's trigger, so ON CONFLICT
// can't hide a second run.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OverdueTaskDigestSchedulerLockTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private OverdueTaskDigestScheduler scheduler;

	@Autowired
	private TestDataFactory testData;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void onlyOneInstanceRunsTheDigestPerTriggerUntilTheLockIsReleased() {
		User assignee = userWithAnOverdueTask();

		scheduler.run();                                              // instance A takes the lock and runs
		assertEquals(1, digestCount(assignee));
		assertTrue(lockIsHeld(), "lockAtLeastFor keeps the lock held well after the job itself finished");
		deleteDigests(assignee);

		try (ConfigurableApplicationContext instanceB = startSecondInstance()) {
			OverdueTaskDigestScheduler schedulerOnB = instanceB.getBean(OverdueTaskDigestScheduler.class);

			schedulerOnB.run();                                       // lock still held by A → skipped
			assertEquals(0, digestCount(assignee), "instance B must not run while instance A holds the lock");

			releaseLock();                                            // as if lockAtLeastFor had elapsed
			schedulerOnB.run();
			assertEquals(1, digestCount(assignee), "once the lock is free, the next trigger runs normally");
		}
	}

	private User userWithAnOverdueTask() {
		Organization organization = testData.organization();
		Project project = testData.project(organization);
		User assignee = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		Task overdue = new Task(project, "Late", null, TaskPriority.HIGH, LocalDate.now().minusDays(1));
		overdue.assignTo(assignee);
		taskRepository.saveAndFlush(overdue);
		return assignee;
	}

	private int digestCount(User user) {
		return jdbcTemplate.queryForObject(
				"select count(*) from notifications where user_id = ? and type = 'OVERDUE_TASK_DIGEST'", Integer.class,
				user.getId());
	}

	private void deleteDigests(User user) {
		jdbcTemplate.update("delete from notifications where user_id = ? and type = 'OVERDUE_TASK_DIGEST'",
				user.getId());
	}

	// Same clock as usingDbTime().
	private boolean lockIsHeld() {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select exists (select 1 from shedlock where name = 'overdue-task-digest' "
						+ "and lock_until > timezone('utc', current_timestamp))",
				Boolean.class));
	}

	private void releaseLock() {
		jdbcTemplate.update("update shedlock set lock_until = timezone('utc', current_timestamp) - interval '1 second' "
				+ "where name = 'overdue-task-digest'");
	}

	private ConfigurableApplicationContext startSecondInstance() {
		return new SpringApplicationBuilder(TaskforgeApiApplication.class).profiles("test")
				.properties("server.port=0", "spring.datasource.url=" + postgres.getJdbcUrl(),
						"spring.datasource.username=" + postgres.getUsername(),
						"spring.datasource.password=" + postgres.getPassword(),
						"spring.data.redis.host=" + redis.getRedisHost(),
						"spring.data.redis.port=" + redis.getRedisPort())
				.run();
	}

}
