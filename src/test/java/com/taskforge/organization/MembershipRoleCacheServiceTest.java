package com.taskforge.organization;

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
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MembershipRoleCacheServiceTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Container
	@ServiceConnection
	static RedisContainer redis = new RedisContainer("redis:7");

	@Autowired
	private MembershipRoleCacheService membershipRoleCacheService;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private MembershipRepository membershipRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void repeatedLookupWithinTheCacheDoesNotHitTheDatabaseAgain() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User user = userRepository.saveAndFlush(new User("member-" + UUID.randomUUID() + "@acme.test", "hash", "M"));
		membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.ADMIN));

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

		MembershipRole first = membershipRoleCacheService.findRole(org.getId(), user.getId()).role();
		statistics.clear();

		MembershipRole second = membershipRoleCacheService.findRole(org.getId(), user.getId()).role();

		assertEquals(MembershipRole.ADMIN, first);
		assertEquals(MembershipRole.ADMIN, second);
		assertEquals(0, statistics.getPrepareStatementCount(),
				"the second lookup should have been served entirely from the Redis cache, with no SQL at all");
	}

	@Test
	void evictingAfterARoleChangeMakesTheNextLookupSeeTheNewRole() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User user = userRepository.saveAndFlush(new User("member-" + UUID.randomUUID() + "@acme.test", "hash", "M"));
		Membership membership = membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.MEMBER));

		MembershipRole cachedBeforeChange = membershipRoleCacheService.findRole(org.getId(), user.getId()).role();
		assertEquals(MembershipRole.MEMBER, cachedBeforeChange);

		membership.changeRole(MembershipRole.ADMIN);
		membershipRepository.saveAndFlush(membership);
		membershipRoleCacheService.evict(org.getId(), user.getId());

		MembershipRole afterChange = membershipRoleCacheService.findRole(org.getId(), user.getId()).role();
		assertEquals(MembershipRole.ADMIN, afterChange, "a stale cached role after a demotion/promotion would be "
				+ "a security bug, not just a performance nit");
	}

	@Test
	void aNegativeLookupIsAlsoCachedAndEvictable() {
		Organization org = organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
		User user = userRepository.saveAndFlush(new User("outsider-" + UUID.randomUUID() + "@acme.test", "hash", "O"));

		assertNull(membershipRoleCacheService.findRole(org.getId(), user.getId()).role());

		membershipRepository.saveAndFlush(new Membership(org, user, MembershipRole.MEMBER));
		membershipRoleCacheService.evict(org.getId(), user.getId());

		assertEquals(MembershipRole.MEMBER, membershipRoleCacheService.findRole(org.getId(), user.getId()).role(),
				"a stale cached 'not a member' result after being added would wrongly keep denying access");
	}

}
