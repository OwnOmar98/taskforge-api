package com.taskforge.support;

import java.util.UUID;

import org.springframework.stereotype.Component;

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
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

// Repository-level entity setup only - the ~15 test classes that build this
// same org/user/project/membership graph by hand each reimplement it
// slightly differently (different default names, different randomization).
// Deliberately doesn't cover the HTTP-level (MockMvc register/login/create)
// flavor of the same setup used by full controller tests: that variant is
// tightly coupled to each test's own MockMvc/ObjectMapper fields and token
// handling, and consolidating it would touch far more files for far less
// benefit than this one does.
@Component
public class TestDataFactory {

	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final MembershipRepository membershipRepository;
	private final ProjectRepository projectRepository;
	private final ProjectMemberRepository projectMemberRepository;

	public TestDataFactory(OrganizationRepository organizationRepository, UserRepository userRepository,
			MembershipRepository membershipRepository, ProjectRepository projectRepository,
			ProjectMemberRepository projectMemberRepository) {
		this.organizationRepository = organizationRepository;
		this.userRepository = userRepository;
		this.membershipRepository = membershipRepository;
		this.projectRepository = projectRepository;
		this.projectMemberRepository = projectMemberRepository;
	}

	public Organization organization() {
		return organizationRepository.saveAndFlush(new Organization("Acme", "acme-" + UUID.randomUUID()));
	}

	public User user() {
		return user("user-" + UUID.randomUUID() + "@acme.test");
	}

	public User user(String email) {
		return userRepository.saveAndFlush(new User(email, "hash", "Test User"));
	}

	public Membership membership(Organization organization, User user, MembershipRole role) {
		return membershipRepository.saveAndFlush(new Membership(organization, user, role));
	}

	// A fresh user who is already a member of the given org - the common
	// case, since a bare User with no Membership can't do anything org-scoped.
	public User memberOf(Organization organization, MembershipRole role) {
		User user = user();
		membership(organization, user, role);
		return user;
	}

	public Project project(Organization organization) {
		return projectRepository.saveAndFlush(new Project(organization, "ENG-" + UUID.randomUUID(), "Engine"));
	}

	public ProjectMember projectMember(Project project, User user, ProjectMemberRole role) {
		return projectMemberRepository.saveAndFlush(new ProjectMember(project, user, role));
	}

	// A fresh user with both the org membership and the project membership a
	// real project member needs - project membership alone isn't meaningful
	// without the org membership underneath it (PR11's rule, not relaxed here).
	public User memberOfProject(Project project, ProjectMemberRole role) {
		User user = memberOf(project.getOrganization(), MembershipRole.MEMBER);
		projectMember(project, user, role);
		return user;
	}

}
