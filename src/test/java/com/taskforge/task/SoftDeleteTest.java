package com.taskforge.task;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.notification.OverdueTaskDigestJob;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectRepository;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.security.JwtService;
import com.taskforge.support.TestDataFactory;
import com.taskforge.task.dto.CreateCommentRequest;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.user.User;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

// "Hidden" is asserted as "answers exactly like an id that never existed",
// not as a hardcoded status: a soft-deleted task or project should be
// indistinguishable from a missing one on every route, whatever that route's
// existing not-found behavior already is (a service-level 404, or a
// permission-level denial for routes addressed by task id alone).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SoftDeleteTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private TestDataFactory testData;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private OverdueTaskDigestJob overdueTaskDigestJob;

	private Organization organization;
	private Project project;
	private User owner;
	private User contributor;
	private User orgMember;

	@BeforeEach
	void setUp() {
		organization = testData.organization();
		project = testData.project(organization);
		owner = testData.memberOf(organization, MembershipRole.OWNER);
		testData.projectMember(project, owner, ProjectMemberRole.LEAD);
		contributor = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		orgMember = testData.memberOf(organization, MembershipRole.MEMBER);
	}

	@Test
	void aDeletedTaskAnswersLikeAMissingOneButItsRowAndCommentsSurvive() throws Exception {
		UUID taskId = createTask(owner, "Doomed");
		addComment(owner, taskId, "keep me");

		assertEquals(204, status(delete(tasksUrl(project.getId()) + "/" + taskId), contributor));

		UUID missing = UUID.randomUUID();
		assertEquals(status(get(tasksUrl(project.getId()) + "/" + missing), owner),
				status(get(tasksUrl(project.getId()) + "/" + taskId), owner));
		assertEquals(status(get("/api/v1/tasks/" + missing + "/comments"), owner),
				status(get("/api/v1/tasks/" + taskId + "/comments"), owner));
		assertEquals(0, listTaskCount(owner));

		assertEquals(contributor.getId(), jdbcTemplate.queryForObject(
				"select deleted_by from tasks where id = ? and deleted_at is not null", UUID.class, taskId));
		assertEquals(1, commentRowCount(taskId));
	}

	@Test
	void restoringATaskBringsItBackWithItsCommentsAndIsAudited() throws Exception {
		UUID taskId = createTask(owner, "Comeback");
		addComment(owner, taskId, "still here");
		status(delete(tasksUrl(project.getId()) + "/" + taskId), owner);

		String restored = mockMvc.perform(authed(post(tasksUrl(project.getId()) + "/" + taskId + "/restore"), owner))
				.andReturn().getResponse().getContentAsString();

		assertEquals(taskId.toString(), objectMapper.readTree(restored).get("id").asString());
		assertEquals(1, listTaskCount(owner));
		assertEquals(200, status(get("/api/v1/tasks/" + taskId + "/comments"), owner));
		assertEquals(List.of("TASK_DELETED", "TASK_RESTORED"), auditActions(taskId));
		assertTrue(jdbcTemplate.queryForObject("select metadata::text from audit_logs where entity_id = ? limit 1",
				String.class, taskId).contains("Comeback"));
	}

	@Test
	void restoringATaskThatIsNotDeletedIsNotFound() throws Exception {
		UUID taskId = createTask(owner, "Alive");

		assertEquals(404, status(post(tasksUrl(project.getId()) + "/" + taskId + "/restore"), owner));
	}

	// The cache is the trap here: the contributor's role is cached by the
	// first request, so without eviction on delete the second request would
	// still be allowed straight through until the cache TTL expired.
	@Test
	void deletingAProjectHidesItsTasksEvenFromAMemberWhoseRoleWasAlreadyCached() throws Exception {
		UUID taskId = createTask(owner, "Inside");
		assertEquals(1, listTaskCount(contributor));

		assertEquals(204, status(delete(projectUrl(project.getId())), owner));

		UUID missingProject = UUID.randomUUID();
		assertEquals(status(get(tasksUrl(missingProject)), contributor),
				status(get(tasksUrl(project.getId())), contributor));
		assertEquals(status(get("/api/v1/tasks/" + UUID.randomUUID() + "/comments"), contributor),
				status(get("/api/v1/tasks/" + taskId + "/comments"), contributor));
		assertEquals(status(get(projectUrl(missingProject)), owner), status(get(projectUrl(project.getId())), owner));
		assertEquals(0, projectListCount(owner));
		assertEquals(List.of("PROJECT_DELETED"), auditActions(project.getId()));
	}

	@Test
	void restoringAProjectBringsBackEverythingUnderIt() throws Exception {
		createTask(owner, "Inside");
		status(delete(projectUrl(project.getId())), owner);

		assertEquals(200, status(post(projectUrl(project.getId()) + "/restore"), owner));

		assertEquals(1, listTaskCount(contributor));
		assertEquals(List.of("PROJECT_DELETED", "PROJECT_RESTORED"), auditActions(project.getId()));
	}

	@Test
	void onlyAnOrgAdminCanRestoreAProject() throws Exception {
		status(delete(projectUrl(project.getId())), owner);

		assertEquals(403, status(post(projectUrl(project.getId()) + "/restore"), orgMember));
	}

	@Test
	void aDeletedProjectsKeyCanBeReusedButThenBlocksItsRestore() throws Exception {
		status(delete(projectUrl(project.getId())), owner);

		String body = objectMapper.writeValueAsString(new CreateProjectRequest(project.getKey(), "Successor"));
		assertEquals(201, mockMvc.perform(authed(post("/api/v1/organizations/" + organization.getId() + "/projects")
				.contentType(MediaType.APPLICATION_JSON).content(body), owner)).andReturn().getResponse().getStatus());

		assertEquals(409, status(post(projectUrl(project.getId()) + "/restore"), owner));
	}

	@Test
	void theOverdueDigestSkipsDeletedTasksAndTasksInDeletedProjects() {
		User assignee = testData.memberOfProject(project, ProjectMemberRole.CONTRIBUTOR);
		Project doomedProject = testData.project(organization);
		testData.projectMember(doomedProject, assignee, ProjectMemberRole.CONTRIBUTOR);
		LocalDate yesterday = LocalDate.now().minusDays(1);

		Task live = overdueTask(project, assignee, yesterday);
		Task deleted = overdueTask(project, assignee, yesterday);
		deleted.markDeleted(owner.getId());
		taskRepository.saveAndFlush(deleted);
		overdueTask(doomedProject, assignee, yesterday);
		doomedProject.markDeleted(owner.getId());
		projectRepository.saveAndFlush(doomedProject);

		overdueTaskDigestJob.runForDate(LocalDate.now());

		String payload = jdbcTemplate.queryForObject(
				"select payload::text from notifications where user_id = ? and type = 'OVERDUE_TASK_DIGEST'",
				String.class, assignee.getId());
		assertNotNull(payload);
		assertEquals(1, objectMapper.readTree(payload).get("taskCount").asInt());
		assertTrue(payload.contains(live.getId().toString()));
	}

	private Task overdueTask(Project target, User assignee, LocalDate dueDate) {
		Task task = new Task(target, "Late", null, TaskPriority.HIGH, dueDate);
		task.assignTo(assignee);
		return taskRepository.saveAndFlush(task);
	}

	private UUID createTask(User actor, String title) throws Exception {
		String body = objectMapper.writeValueAsString(new CreateTaskRequest(title, null, TaskPriority.LOW, null, null));
		String response = mockMvc.perform(authed(post(tasksUrl(project.getId()))
				.contentType(MediaType.APPLICATION_JSON).content(body), actor)).andReturn().getResponse()
				.getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).get("id").asString());
	}

	private void addComment(User actor, UUID taskId, String text) throws Exception {
		String body = objectMapper.writeValueAsString(new CreateCommentRequest(text));
		assertEquals(201, mockMvc.perform(authed(post("/api/v1/tasks/" + taskId + "/comments")
				.contentType(MediaType.APPLICATION_JSON).content(body), actor)).andReturn().getResponse().getStatus());
	}

	private int listTaskCount(User actor) throws Exception {
		String response = mockMvc.perform(authed(get(tasksUrl(project.getId())), actor)).andReturn().getResponse()
				.getContentAsString();
		return objectMapper.readTree(response).get("content").size();
	}

	private int projectListCount(User actor) throws Exception {
		String response = mockMvc
				.perform(authed(get("/api/v1/organizations/" + organization.getId() + "/projects"), actor))
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("content").size();
	}

	private int commentRowCount(UUID taskId) {
		return jdbcTemplate.queryForObject("select count(*) from task_comments where task_id = ?", Integer.class,
				taskId);
	}

	private List<String> auditActions(UUID entityId) {
		return jdbcTemplate.queryForList("select action from audit_logs where entity_id = ? order by created_at",
				String.class, entityId);
	}

	private int status(MockHttpServletRequestBuilder request, User actor) throws Exception {
		return mockMvc.perform(authed(request, actor)).andReturn().getResponse().getStatus();
	}

	private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder request, User actor) {
		return request.header("Authorization", "Bearer " + jwtService.generateAccessToken(actor.getId()));
	}

	private String tasksUrl(UUID projectId) {
		return "/api/v1/projects/" + projectId + "/tasks";
	}

	private String projectUrl(UUID projectId) {
		return "/api/v1/organizations/" + organization.getId() + "/projects/" + projectId;
	}

}
