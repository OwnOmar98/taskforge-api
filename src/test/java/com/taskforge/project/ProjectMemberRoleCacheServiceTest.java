package com.taskforge.project;

import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;
import com.taskforge.organization.Organization;
import com.taskforge.organization.OrganizationRepository;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class ProjectMemberRoleCacheServiceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private ProjectMemberRoleCacheService projectMemberRoleCacheService;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private ProjectRepository projectRepository;

	@Autowired
	private ProjectMemberRepository projectMemberRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void repeatedLookupWithinTheCacheDoesNotHitTheDatabaseAgain() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User user = userRepository.saveAndFlush(new User("member-" + UUID.randomUUID() + "@acme.test", "hash", "M"));
		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, ProjectMemberRole.CONTRIBUTOR));

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

		ProjectMemberRole first = projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role();
		statistics.clear();

		ProjectMemberRole second = projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role();

		assertEquals(ProjectMemberRole.CONTRIBUTOR, first);
		assertEquals(ProjectMemberRole.CONTRIBUTOR, second);
		assertEquals(0, statistics.getPrepareStatementCount(),
				"the second lookup should have been served entirely from the Redis cache, with no SQL at all");
	}

	@Test
	void evictingAfterARoleChangeMakesTheNextLookupSeeTheNewRole() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User user = userRepository.saveAndFlush(new User("member-" + UUID.randomUUID() + "@acme.test", "hash", "M"));
		ProjectMember member = projectMemberRepository
				.saveAndFlush(new ProjectMember(project, user, ProjectMemberRole.VIEWER));

		ProjectMemberRole cachedBeforeChange = projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role();
		assertEquals(ProjectMemberRole.VIEWER, cachedBeforeChange);

		member.changeRole(ProjectMemberRole.LEAD);
		projectMemberRepository.saveAndFlush(member);
		projectMemberRoleCacheService.evict(project.getId(), user.getId());

		ProjectMemberRole afterChange = projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role();
		assertEquals(ProjectMemberRole.LEAD, afterChange, "a stale cached role after a promotion/demotion would be "
				+ "a security bug, not just a performance nit");
	}

	@Test
	void aNegativeLookupIsAlsoCachedAndEvictable() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		Project project = projectRepository.saveAndFlush(new Project(org, "ENG-" + UUID.randomUUID(), "Engine"));
		User user = userRepository.saveAndFlush(new User("outsider-" + UUID.randomUUID() + "@acme.test", "hash", "O"));

		assertNull(projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role());

		projectMemberRepository.saveAndFlush(new ProjectMember(project, user, ProjectMemberRole.CONTRIBUTOR));
		projectMemberRoleCacheService.evict(project.getId(), user.getId());

		assertEquals(ProjectMemberRole.CONTRIBUTOR,
				projectMemberRoleCacheService.findRole(project.getId(), user.getId()).role(),
				"a stale cached 'not a member' result after being added would wrongly keep denying access");
	}

}
