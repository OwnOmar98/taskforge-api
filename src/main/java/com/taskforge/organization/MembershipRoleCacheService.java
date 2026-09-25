package com.taskforge.organization;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Deliberately its own service, not a method added to MembershipService: the
// mutation sites that need to evict this cache span multiple other service
// classes (OrganizationService, InvitationService) that don't otherwise
// depend on MembershipService and shouldn't need to just to invalidate a
// cache entry - injecting this instead keeps the dependency graph one-way.
@Service
public class MembershipRoleCacheService {

	private final MembershipRepository membershipRepository;

	public MembershipRoleCacheService(MembershipRepository membershipRepository) {
		this.membershipRepository = membershipRepository;
	}

	// Returns the MembershipRoleLookup wrapper, not a bare MembershipRole - see
	// that record for why. No unwrapping convenience method here either: that
	// would need to call this one via "this.", which - like any self-invoked
	// call - bypasses the Spring proxy carrying this annotation entirely,
	// silently skipping the cache on every single call. Callers unwrap
	// .role() themselves instead.
	@Cacheable(cacheNames = "orgMembershipRole", key = "#organizationId + ':' + #userId")
	@Transactional(readOnly = true)
	public MembershipRoleLookup findRole(UUID organizationId, UUID userId) {
		MembershipRole role = membershipRepository.findByOrganization_IdAndUser_Id(organizationId, userId)
				.map(Membership::getRole)
				.orElse(null);
		return new MembershipRoleLookup(role);
	}

	// A no-op body: @CacheEvict runs on the way out regardless of what the
	// method does, so this exists purely to be called from wherever a
	// membership row for this (organizationId, userId) pair changes.
	@CacheEvict(cacheNames = "orgMembershipRole", key = "#organizationId + ':' + #userId")
	public void evict(UUID organizationId, UUID userId) {
	}

}
