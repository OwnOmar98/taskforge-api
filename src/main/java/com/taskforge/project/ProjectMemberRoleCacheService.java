package com.taskforge.project;

import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Same reasoning as MembershipRoleCacheService: kept separate so ProjectService
// can evict on project creation without depending on ProjectMemberService,
// which already depends on ProjectService - avoiding a circular dependency.
@Service
public class ProjectMemberRoleCacheService {

	private final ProjectMemberRepository projectMemberRepository;

	public ProjectMemberRoleCacheService(ProjectMemberRepository projectMemberRepository) {
		this.projectMemberRepository = projectMemberRepository;
	}

	// Returns the ProjectMemberRoleLookup wrapper, not a bare ProjectMemberRole
	// - see MembershipRoleCacheService.findRole for the full reasoning
	// (Redis JSON round-tripping, and why there's deliberately no unwrapping
	// convenience method here).
	@Cacheable(cacheNames = "projectMemberRole", key = "#projectId + ':' + #userId")
	@Transactional(readOnly = true)
	public ProjectMemberRoleLookup findRole(UUID projectId, UUID userId) {
		ProjectMemberRole role = projectMemberRepository.findActiveMembership(projectId, userId)
				.map(ProjectMember::getRole)
				.orElse(null);
		return new ProjectMemberRoleLookup(role);
	}

	@CacheEvict(cacheNames = "projectMemberRole", key = "#projectId + ':' + #userId")
	public void evict(UUID projectId, UUID userId) {
	}

}
