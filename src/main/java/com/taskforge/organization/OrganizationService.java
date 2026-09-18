package com.taskforge.organization;

import java.util.Locale;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.taskforge.common.exception.GeneralErrorCode;
import com.taskforge.common.exception.ResourceNotFoundException;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

@Service
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final MembershipRepository membershipRepository;
	private final UserRepository userRepository;

	public OrganizationService(OrganizationRepository organizationRepository,
			MembershipRepository membershipRepository, UserRepository userRepository) {
		this.organizationRepository = organizationRepository;
		this.membershipRepository = membershipRepository;
		this.userRepository = userRepository;
	}

	@Transactional
	public Organization createOrganization(String name, UUID ownerId) {
		User owner = userRepository.findById(ownerId).orElseThrow();

		Organization organization = organizationRepository.save(new Organization(name, generateUniqueSlug(name)));
		membershipRepository.save(new Membership(organization, owner, MembershipRole.OWNER));

		return organization;
	}

	@PreAuthorize("hasPermission(#organizationId, 'Organization', 'ADMIN')")
	@Transactional
	public Organization renameOrganization(UUID organizationId, String newName) {
		Organization organization = organizationRepository.findById(organizationId)
				.orElseThrow(() -> new ResourceNotFoundException(GeneralErrorCode.RESOURCE_NOT_FOUND,
						"Organization not found"));

		organization.rename(newName);
		return organization;
	}

	private String generateUniqueSlug(String name) {
		String base = name.strip().toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9\\s-]", "")
				.replaceAll("[\\s-]+", "-")
				.replaceAll("^-|-$", "");

		if (base.isEmpty()) {
			base = "org";
		}

		String slug = base;
		int suffix = 2;
		while (organizationRepository.existsBySlug(slug)) {
			slug = base + "-" + suffix++;
		}

		return slug;
	}

}
