package com.taskforge.organization;

import java.time.Duration;
import java.time.Instant;
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
import com.taskforge.organization.dto.CreateInvitationRequest;
import com.taskforge.organization.dto.CreateOrganizationRequest;
import com.taskforge.security.SecureTokenGenerator;
import com.taskforge.user.User;
import com.taskforge.user.UserRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import static com.taskforge.organization.OrganizationErrorCode.ALREADY_MEMBER;
import static com.taskforge.organization.OrganizationErrorCode.CANNOT_MODIFY_OWNER_ROLE;
import static com.taskforge.organization.OrganizationErrorCode.INVALID_OR_EXPIRED_INVITATION;
import static com.taskforge.organization.OrganizationErrorCode.INVITATION_ALREADY_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class InvitationControllerTest {

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
	private MembershipRepository membershipRepository;

	@Autowired
	private OrganizationRepository organizationRepository;

	@Autowired
	private InvitationRepository invitationRepository;

	@Test
	void invitingWithDifferentEmailCasingIsTreatedAsADuplicate() throws Exception {
		String ownerToken = registerAndGetToken("owner12@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");

		createInvitation(orgId, ownerToken, "Casing@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("casing@acme.test", MembershipRole.ADMIN))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(INVITATION_ALREADY_PENDING.code()));
	}

	@Test
	void invitingAnAlreadyMemberIsDetectedRegardlessOfEmailCasing() throws Exception {
		String ownerToken = registerAndGetToken("owner13@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String memberToken = registerAndGetToken("caseuser@acme.test");
		String rawToken = createInvitation(orgId, ownerToken, "caseuser@acme.test", MembershipRole.MEMBER);
		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("CaseUser@acme.test", MembershipRole.ADMIN))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(ALREADY_MEMBER.code()));
	}

	@Test
	void acceptingAnInvitationForAnOrganizationYouAreAlreadyInFailsCleanly() throws Exception {
		String ownerToken = registerAndGetToken("owner14@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String memberToken = registerAndGetToken("already-in@acme.test");
		User member = userRepository.findByEmail("already-in@acme.test").orElseThrow();
		User owner = userRepository.findByEmail("owner14@acme.test").orElseThrow();
		Organization organization = organizationRepository.findById(orgId).orElseThrow();
		membershipRepository.saveAndFlush(new Membership(organization, member, MembershipRole.MEMBER));

		// Bypasses createInvitation's own guard on purpose, simulating an invite
		// that was issued before the membership existed (or under different
		// email casing) and is still active by the time accept is called.
		String rawToken = SecureTokenGenerator.generateRawToken();
		invitationRepository.saveAndFlush(new Invitation(organization, "already-in@acme.test", MembershipRole.ADMIN,
				SecureTokenGenerator.hash(rawToken), owner, Instant.now().plus(Duration.ofDays(1))));

		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(ALREADY_MEMBER.code()));
	}

	@Test
	void invitingAnExistingUserThenAcceptingCreatesMembership() throws Exception {
		String ownerToken = registerAndGetToken("owner1@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		registerAndGetToken("invitee1@acme.test");
		String inviteeToken = loginAndGetToken("invitee1@acme.test");

		String rawToken = createInvitation(orgId, ownerToken, "invitee1@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + inviteeToken))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("MEMBER"));

		User invitee = userRepository.findByEmail("invitee1@acme.test").orElseThrow();
		assertTrue(membershipRepository.findByOrganization_IdAndUser_Id(orgId, invitee.getId()).isPresent());
	}

	@Test
	void registeringWithAMatchingInvitationTokenAcceptsIt() throws Exception {
		String ownerToken = registerAndGetToken("owner2@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");

		// The invited email has no account yet - createInvitation's own
		// "already a member" check has nothing to match against, so this is
		// exactly the deferred-until-registration path.
		String rawToken = createInvitation(orgId, ownerToken, "newperson@acme.test", MembershipRole.ADMIN);

		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new RegisterRequest("newperson@acme.test", "supersecret", "New Person", rawToken))))
				.andExpect(status().isCreated());

		User newUser = userRepository.findByEmail("newperson@acme.test").orElseThrow();
		Membership membership = membershipRepository.findByOrganization_IdAndUser_Id(orgId, newUser.getId())
				.orElseThrow();
		assertEquals(MembershipRole.ADMIN, membership.getRole());
	}

	@Test
	void registeringWithoutATokenLeavesAPendingInvitationUntouched() throws Exception {
		String ownerToken = registerAndGetToken("owner9@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");

		createInvitation(orgId, ownerToken, "uninvited-by-default@acme.test", MembershipRole.ADMIN);

		registerAndGetToken("uninvited-by-default@acme.test");

		User newUser = userRepository.findByEmail("uninvited-by-default@acme.test").orElseThrow();
		assertTrue(membershipRepository.findByOrganization_IdAndUser_Id(orgId, newUser.getId()).isEmpty());
	}

	@Test
	void registeringWithAnInvalidTokenFailsTheWholeRegistration() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest("baduser@acme.test",
								"supersecret", "Bad User", "not-a-real-token"))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value(INVALID_OR_EXPIRED_INVITATION.code()));

		assertTrue(userRepository.findByEmail("baduser@acme.test").isEmpty());
	}

	@Test
	void decliningAnInvitationPreventsItFromBeingAcceptedLater() throws Exception {
		String ownerToken = registerAndGetToken("owner10@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String declinerToken = registerAndGetToken("decliner1@acme.test");
		String rawToken = createInvitation(orgId, ownerToken, "decliner1@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/decline")
						.header("Authorization", "Bearer " + declinerToken))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + declinerToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value(INVALID_OR_EXPIRED_INVITATION.code()));
	}

	@Test
	void aDeclinedInvitationDoesNotBlockReInviting() throws Exception {
		String ownerToken = registerAndGetToken("owner11@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String declinerToken = registerAndGetToken("decliner2@acme.test");
		String firstToken = createInvitation(orgId, ownerToken, "decliner2@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/invitations/" + firstToken + "/decline")
						.header("Authorization", "Bearer " + declinerToken))
				.andExpect(status().isNoContent());

		createInvitation(orgId, ownerToken, "decliner2@acme.test", MembershipRole.ADMIN);
	}

	@Test
	void cannotInviteSomeoneWhoIsAlreadyAMember() throws Exception {
		String ownerToken = registerAndGetToken("owner3@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String memberToken = registerAndGetToken("member3@acme.test");
		String rawToken = createInvitation(orgId, ownerToken, "member3@acme.test", MembershipRole.MEMBER);
		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("member3@acme.test", MembershipRole.ADMIN))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(ALREADY_MEMBER.code()));
	}

	@Test
	void cannotCreateASecondPendingInvitationForTheSameEmail() throws Exception {
		String ownerToken = registerAndGetToken("owner4@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");

		createInvitation(orgId, ownerToken, "duplicate@acme.test", MembershipRole.MEMBER);

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("duplicate@acme.test", MembershipRole.MEMBER))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(INVITATION_ALREADY_PENDING.code()));
	}

	@Test
	void cannotInviteSomeoneAsOwner() throws Exception {
		String ownerToken = registerAndGetToken("owner5@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("wannabe-owner@acme.test", MembershipRole.OWNER))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value(CANNOT_MODIFY_OWNER_ROLE.code()));
	}

	@Test
	void nonAdminCannotCreateAnInvitation() throws Exception {
		String ownerToken = registerAndGetToken("owner6@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		String memberToken = registerAndGetToken("member6@acme.test");
		String rawToken = createInvitation(orgId, ownerToken, "member6@acme.test", MembershipRole.MEMBER);
		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + memberToken))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/organizations/" + orgId + "/invitations")
						.header("Authorization", "Bearer " + memberToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(
								new CreateInvitationRequest("someone-else@acme.test", MembershipRole.MEMBER))))
				.andExpect(status().isForbidden());
	}

	@Test
	void acceptingWithAnEmailMismatchIsRejected() throws Exception {
		String ownerToken = registerAndGetToken("owner7@acme.test");
		UUID orgId = createOrganization(ownerToken, "Acme");
		registerAndGetToken("invitee7@acme.test");
		String rawToken = createInvitation(orgId, ownerToken, "invitee7@acme.test", MembershipRole.MEMBER);

		String wrongUserToken = registerAndGetToken("wrong-person@acme.test");

		mockMvc.perform(post("/api/v1/invitations/" + rawToken + "/accept")
						.header("Authorization", "Bearer " + wrongUserToken))
				.andExpect(status().isForbidden());
	}

	@Test
	void acceptingAnInvalidTokenReturnsNotFound() throws Exception {
		String token = registerAndGetToken("someone8@acme.test");

		mockMvc.perform(post("/api/v1/invitations/not-a-real-token/accept")
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value(INVALID_OR_EXPIRED_INVITATION.code()));
	}

	private String createInvitation(UUID organizationId, String inviterToken, String email, MembershipRole role)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/organizations/" + organizationId + "/invitations")
						.header("Authorization", "Bearer " + inviterToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new CreateInvitationRequest(email, role))))
				.andExpect(status().isCreated())
				.andReturn();

		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		return body.get("token").stringValue();
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
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(email, "supersecret"))))
				.andExpect(status().isOk())
				.andReturn();

		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").stringValue();
	}

}
