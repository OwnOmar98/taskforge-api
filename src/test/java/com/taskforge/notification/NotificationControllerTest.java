package com.taskforge.notification;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.taskforge.auth.dto.LoginRequest;
import com.taskforge.auth.dto.RegisterRequest;
import com.taskforge.organization.Membership;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.organization.MembershipRole;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.project.Project;
import com.taskforge.project.ProjectMember;
import com.taskforge.project.ProjectMemberRepository;
import com.taskforge.project.ProjectMemberRole;
import com.taskforge.project.ProjectRepository;
import com.taskforge.project.dto.CreateProjectRequest;
import com.taskforge.task.TaskPriority;
import com.taskforge.task.dto.CreateTaskRequest;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class NotificationControllerTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectMemberRepository projectMemberRepository;

	@Autowired
	private NotificationRepository notificationRepository;

	@Test
	void listingReturnsOnlyTheCallersOwnNotifications() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme1");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee1@acme.test", ProjectMemberRole.CONTRIBUTOR);
		String assigneeToken = loginAndGetToken("assignee1@acme.test");
		createAssignedTask(projectId, ownerToken, "Ship it", assignee.getId());

		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertEquals(1, notificationRepository.countByUserId(assignee.getId())));

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + assigneeToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(1)))
				.andExpect(jsonPath("$.content[0].type").value("TASK_ASSIGNED"));

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content", hasSize(0)));
	}

	@Test
	void ownerCanMarkTheirOwnNotificationAsRead() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme2");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee2@acme.test", ProjectMemberRole.CONTRIBUTOR);
		String assigneeToken = loginAndGetToken("assignee2@acme.test");
		createAssignedTask(projectId, ownerToken, "Ship it", assignee.getId());

		await().atMost(Duration.ofSeconds(5))
				.until(() -> notificationRepository.countByUserId(assignee.getId()) == 1);
		UUID notificationId = notificationRepository.findFirstPageByUserId(assignee.getId(), 10).get(0).getId();

		mockMvc.perform(patch("/api/v1/notifications/" + notificationId + "/read")
						.header("Authorization", "Bearer " + assigneeToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + assigneeToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].readAt").exists());
	}

	@Test
	void cannotMarkSomeoneElsesNotificationAsRead() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme3");
		UUID projectId = createProject(orgId, ownerToken, "eng", "Engine");
		User assignee = addProjectMember(orgId, projectId, "assignee3@acme.test", ProjectMemberRole.CONTRIBUTOR);
		createAssignedTask(projectId, ownerToken, "Ship it", assignee.getId());

		await().atMost(Duration.ofSeconds(5))
				.until(() -> notificationRepository.countByUserId(assignee.getId()) == 1);
		UUID notificationId = notificationRepository.findFirstPageByUserId(assignee.getId(), 10).get(0).getId();

		mockMvc.perform(patch("/api/v1/notifications/" + notificationId + "/read")
						.header("Authorization", "Bearer " + ownerToken))
				.andExpect(status().isNotFound());
	}

	@Test
	void markingAllAsReadOnlyAffectsTheCallersOwnNotifications() throws Exception {
		String tokenA = registerAndGetToken("usera@acme.test");
		User userA = userRepository.findByEmail("usera@acme.test").orElseThrow();
		registerAndGetToken("userb@acme.test");
		User userB = userRepository.findByEmail("userb@acme.test").orElseThrow();
		Organization organization = organizationRepository
				.saveAndFlush(new Organization("Acme6", "acme6-" + UUID.randomUUID()));

		for (int i = 0; i < 3; i++) {
			notificationRepository
					.save(new Notification(userA.getId(), organization.getId(), NotificationType.TASK_ASSIGNED, null));
		}
		for (int i = 0; i < 2; i++) {
			notificationRepository
					.save(new Notification(userB.getId(), organization.getId(), NotificationType.TASK_ASSIGNED, null));
		}

		mockMvc.perform(patch("/api/v1/notifications/read").header("Authorization", "Bearer " + tokenA))
				.andExpect(status().isNoContent());

		assertTrue(notificationRepository.findFirstPageByUserId(userA.getId(), 10).stream()
				.allMatch(n -> n.getReadAt() != null));
		assertTrue(notificationRepository.findFirstPageByUserId(userB.getId(), 10).stream()
				.allMatch(n -> n.getReadAt() == null));
	}

	@Test
	void cursorPaginationWalksThroughAllNotificationsWithoutGapsOrDuplicates() throws Exception {
		String token = registerAndGetToken("owner4@acme.test");
		User user = userRepository.findByEmail("owner4@acme.test").orElseThrow();
		Organization organization = organizationRepository
				.saveAndFlush(new Organization("Acme4", "acme4-" + UUID.randomUUID()));

		Set<UUID> insertedIds = new HashSet<>();
		for (int i = 0; i < 25; i++) {
			Notification notification = notificationRepository
					.save(new Notification(user.getId(), organization.getId(), NotificationType.TASK_ASSIGNED, null));
			insertedIds.add(notification.getId());
		}

		Set<UUID> seenIds = new HashSet<>();
		String cursor = null;
		int pageCount = 0;
		boolean hasMore = true;
		while (hasMore) {
			pageCount++;
			assertTrue(pageCount <= 10, "too many pages - pagination is probably looping");

			var request = get("/api/v1/notifications").header("Authorization", "Bearer " + token).param("size", "10");
			if (cursor != null) {
				request = request.param("cursor", cursor);
			}
			MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();

			var body = objectMapper.readTree(result.getResponse().getContentAsString());
			body.get("content").forEach(node -> {
				UUID id = UUID.fromString(node.get("id").stringValue());
				// Getting the same id back on a later page would mean the
				// cursor didn't actually advance past it.
				assertTrue(seenIds.add(id), "notification " + id + " was returned on more than one page");
			});

			hasMore = body.get("hasMore").booleanValue();
			cursor = hasMore ? body.get("nextCursor").stringValue() : null;
		}

		assertEquals(3, pageCount);
		assertEquals(insertedIds, seenIds);
	}

	private UUID createAssignedTask(UUID projectId, String token, String title, UUID assigneeId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/projects/" + projectId + "/tasks")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper
								.writeValueAsString(new CreateTaskRequest(title, null, TaskPriority.MEDIUM, null, assigneeId))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private User addProjectMember(UUID orgId, UUID projectId, String email, ProjectMemberRole role) throws Exception {
		registerAndGetToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Organization organization = organizationRepository.findById(orgId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, user, MembershipRole.MEMBER));
		Project project = projectRepository.findById(projectId).orElseThrow();
		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, role));
		return user;
	}

	private UUID createProject(UUID orgId, String token, String key, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + orgId + "/projects")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateProjectRequest(key, name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private UUID createOrganization(String token, String name) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateOrganizationRequest(name))))
				.andExpect(status().isCreated())
				.andReturn();

		return UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private String registerAndGetToken(String email) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest(email, "supersecret", "Name"))))
				.andExpect(status().isCreated())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

	private String loginAndGetToken(String email) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, "supersecret"))))
				.andExpect(status().isOk())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

}
