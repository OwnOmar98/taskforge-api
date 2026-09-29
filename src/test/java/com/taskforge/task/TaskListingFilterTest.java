package com.taskforge.task;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskListingFilterTest {

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

	private Project project;

	private User owner;

	private User contributor;

	private Label urgentLabel;

	private String token;

	@BeforeEach
	void setUp() {
		Organization org = organizationRepository
				.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		owner = userRepository.saveAndFlush(new User("owner-" + UUID.randomUUID() + "@acme.test", "hash", "Owner"));
		membershipRepository.saveAndFlush(new Membership(org, owner, MembershipRole.OWNER));
		contributor = userRepository
				.saveAndFlush(new User("contributor-" + UUID.randomUUID() + "@acme.test", "hash", "Contributor"));
		membershipRepository.saveAndFlush(new Membership(org, contributor, MembershipRole.MEMBER));

		project = projectRepository.saveAndFlush(new Project(org, "FLT-" + UUID.randomUUID(), "Filters"));
		projectMemberRepository.saveAndFlush(new ProjectMember(project, owner, ProjectMemberRole.LEAD));
		projectMemberRepository.saveAndFlush(new ProjectMember(project, contributor, ProjectMemberRole.CONTRIBUTOR));

		urgentLabel = labelRepository.saveAndFlush(new Label(org, "urgent-" + UUID.randomUUID()));

		Task todoHighUnassigned = new Task(project, "Todo high", null, TaskPriority.HIGH, null);
		taskRepository.saveAndFlush(todoHighUnassigned);

		Task doneLowOwner = new Task(project, "Done low", null, TaskPriority.LOW, null);
		doneLowOwner.assignTo(owner);
		doneLowOwner.changeStatus(TaskStatus.DONE);
		taskRepository.saveAndFlush(doneLowOwner);

		Task todoMediumContributorLabeled = new Task(project, "Todo medium labeled", null, TaskPriority.MEDIUM, null);
		todoMediumContributorLabeled.assignTo(contributor);
		todoMediumContributorLabeled.getLabels().add(urgentLabel);
		taskRepository.saveAndFlush(todoMediumContributorLabeled);

		token = jwtService.generateAccessToken(owner.getId());
	}

	@Test
	void filtersByStatus() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?status=DONE")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].title").value("Done low"));
	}

	@Test
	void filtersByPriority() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?priority=HIGH")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].title").value("Todo high"));
	}

	@Test
	void filtersByAssignee() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?assigneeId=" + contributor.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].title").value("Todo medium labeled"));
	}

	@Test
	void filtersByLabel() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?labelId=" + urgentLabel.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].title").value("Todo medium labeled"))
				.andExpect(jsonPath("$.content[0].labelNames", hasSize(1)));
	}

	@Test
	void labelNamesAreListedAlphabeticallyRegardlessOfAttachOrder() throws Exception {
		String suffix = UUID.randomUUID().toString();
		Label zebra = labelRepository.saveAndFlush(new Label(project.getOrganization(), "zebra-" + suffix));
		Label apple = labelRepository.saveAndFlush(new Label(project.getOrganization(), "apple-" + suffix));
		Task task = new Task(project, "Two labels", null, TaskPriority.LOW, null);
		task.getLabels().add(zebra);
		task.getLabels().add(apple);
		taskRepository.saveAndFlush(task);

		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?labelId=" + zebra.getId())
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].labelNames[0]").value("apple-" + suffix))
				.andExpect(jsonPath("$.content[0].labelNames[1]").value("zebra-" + suffix));
	}

	@Test
	void combinesMultipleFilters() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?status=TODO&priority=MEDIUM")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].title").value("Todo medium labeled"));
	}

	@Test
	void combinationOfFiltersMatchingNothingReturnsEmptyPage() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks?status=DONE&priority=HIGH")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(0)))
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void noFiltersReturnsAllTasksInProject() throws Exception {
		mockMvc.perform(get("/api/v1/projects/" + project.getId() + "/tasks")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(3)))
				.andExpect(jsonPath("$.totalElements").value(3));
	}

}
