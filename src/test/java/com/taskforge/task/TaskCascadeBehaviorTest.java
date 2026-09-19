package com.taskforge.task;

import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectRepository;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TaskCascadeBehaviorTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private TaskCommentRepository taskCommentRepository;

	@Autowired
	private LabelRepository labelRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void deletingATaskCascadesToItsCommentsButNotToASharedLabel() {
		Organization org = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User author = userRepository.saveAndFlush(new User("author-" + UUID.randomUUID() + "@acme.test", "hash",
				"Author"));
		Task task = taskRepository.saveAndFlush(new Task(project, "Task", null, TaskPriority.LOW, null));
		taskCommentRepository.saveAndFlush(new TaskComment(task, author, "A comment"));

		Label label = labelRepository.saveAndFlush(new Label(org, "bug-" + UUID.randomUUID()));
		task.getLabels().add(label);
		taskRepository.saveAndFlush(task);

		UUID taskId = task.getId();
		UUID labelId = label.getId();

		// Reload first: `task`'s in-memory `comments` is still the plain, empty
		// list from construction, since the comment above was saved directly
		// through the repository rather than through task.getComments().add(...).
		// Deleting that stale `task` reference would cascade over an empty
		// collection while the (still-managed) TaskComment keeps its required
		// reference to the now-deleted row - exactly the inconsistency
		// Hibernate's pre-flush check exists to catch. Reloading gives Hibernate
		// a real, lazily-loadable collection to cascade through correctly.
		entityManager.clear();
		Task reloaded = taskRepository.findById(taskId).orElseThrow();

		taskRepository.delete(reloaded);
		taskRepository.flush();

		assertTrue(taskCommentRepository.findByTaskIdWithAuthor(taskId).isEmpty());
		assertTrue(labelRepository.findById(labelId).isPresent());
	}

	@Test
	void removingACommentFromTheTasksCollectionDeletesItViaOrphanRemoval() {
		Organization org = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User author = userRepository.saveAndFlush(new User("author-" + UUID.randomUUID() + "@acme.test", "hash",
				"Author"));
		Task task = taskRepository.saveAndFlush(new Task(project, "Task", null, TaskPriority.LOW, null));
		TaskComment comment = taskCommentRepository.saveAndFlush(new TaskComment(task, author, "A comment"));

		// Force a fresh load so `comments` is genuinely lazy-initialized here,
		// not just reusing the in-memory instance from the save above.
		entityManager.clear();

		Task reloaded = taskRepository.findById(task.getId()).orElseThrow();
		TaskComment managedComment = taskCommentRepository.findById(comment.getId()).orElseThrow();
		reloaded.getComments().remove(managedComment);
		taskRepository.flush();

		assertFalse(taskCommentRepository.findById(comment.getId()).isPresent());
		assertTrue(taskRepository.findById(task.getId()).isPresent());
	}

}
