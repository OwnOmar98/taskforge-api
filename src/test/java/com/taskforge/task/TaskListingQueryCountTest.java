package com.taskforge.task;

import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
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
import com.taskforge.security.JwtService;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskListingQueryCountTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private MockMvc mockMvc;

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
	private TaskRepository taskRepository;

	@Autowired
	private LabelRepository labelRepository;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	// Regression test for a real N+1: the naive implementation lazily touched
	// task.getLabels() per row, so the query count grew linearly with the task
	// count (8 tasks -> 12 queries, 16 tasks -> 20 queries). The fix (an
	// @EntityGraph for the to-one assignee + a single batched label query)
	// keeps the count flat regardless of how many tasks are on the page.
	@ParameterizedTest
	@ValueSource(ints = { 8, 16 })
	void listingQueryCountStaysBoundedRegardlessOfTaskCount(int taskCount) throws Exception {
		Organization org = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User owner = userRepository
				.saveAndFlush(new User("owner-" + UUID.randomUUID() + "@acme.test", "hash", "Owner"));
		membershipRepository.saveAndFlush(new Membership(org, owner, MembershipRole.OWNER));
		Project project = projectRepository.saveAndFlush(new Project(org, "QC-" + UUID.randomUUID(), "Query Count"));
		projectMemberRepository.saveAndFlush(new ProjectMember(project, owner, ProjectMemberRole.LEAD));

		Label label = labelRepository.saveAndFlush(new Label(org, "bug-" + UUID.randomUUID()));

		for (int i = 0; i < taskCount; i++) {
			Task task = taskRepository.saveAndFlush(new Task(project, "Task " + i, null, TaskPriority.LOW, null));
			task.assignTo(owner);
			task.getLabels().add(label);
			taskRepository.saveAndFlush(task);
		}

		String token = jwtService.generateAccessToken(owner.getId());

		SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
		Statistics statistics = sessionFactory.getStatistics();
		statistics.clear();

		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?size=20")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk());

		long queryCount = statistics.getPrepareStatementCount();
		assertTrue(queryCount <= 5,
				"expected a bounded, constant query count regardless of task count, but got " + queryCount);
	}

}
