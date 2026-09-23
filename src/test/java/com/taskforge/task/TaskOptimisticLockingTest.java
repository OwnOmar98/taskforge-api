package com.taskforge.task;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectRepository;

import static org.junit.jupiter.api.Assertions.assertThrows;

// Distinct from TaskControllerTest#updatingWithAStaleVersionIsRejected, which
// only proves our own manual version-comparison check in TaskService. That
// check can never fire for two requests that both read the same in-memory
// version simultaneously - only Hibernate's own @Version-driven UPDATE ...
// WHERE clause catches that, which is what this test provokes directly
// against the repository, bypassing the service layer's manual check
// entirely.
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class TaskOptimisticLockingTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void concurrentUpdatesToTheSameTaskThrowOptimisticLockingFailureException() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);

		UUID taskId = tx.execute(status -> {
			Organization org = organizationRepository
					.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
			Project project = projectRepository.saveAndFlush(new Project(org, "OL-" + UUID.randomUUID(), "Lock"));
			Task task = taskRepository.saveAndFlush(new Task(project, "Original", null, TaskPriority.LOW, null));
			return task.getId();
		});

		// Two independent reads, each carrying version 0 once detached at the
		// end of its own transaction - simulating two concurrent requests that
		// both loaded the task before either one wrote back.
		Task firstReaderCopy = tx.execute(status -> taskRepository.findById(taskId).orElseThrow());
		Task secondReaderCopy = tx.execute(status -> taskRepository.findById(taskId).orElseThrow());

		tx.executeWithoutResult(status -> {
			firstReaderCopy.rename("Renamed by first writer");
			taskRepository.saveAndFlush(firstReaderCopy);
		});

		assertThrows(OptimisticLockingFailureException.class, () -> tx.executeWithoutResult(status -> {
			secondReaderCopy.rename("Renamed by second writer");
			taskRepository.saveAndFlush(secondReaderCopy);
		}));
	}

}
