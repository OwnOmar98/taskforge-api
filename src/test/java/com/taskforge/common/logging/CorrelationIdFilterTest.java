package com.taskforge.common.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

import com.taskforge.auth.dto.RegisterRequest;

import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class CorrelationIdFilterTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void generatesACorrelationIdWhenNoneIsSupplied() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me")).andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
	}

	@Test
	void echoesBackAClientSuppliedCorrelationId() throws Exception {
		String clientId = "client-supplied-id-123";

		mockMvc.perform(get("/api/v1/auth/me").header(CorrelationIdFilter.HEADER_NAME, clientId))
				.andExpect(header().string(CorrelationIdFilter.HEADER_NAME, clientId));
	}

	// A missing/invalid token is rejected by Spring Security before the
	// request ever reaches a controller - proving the filter runs early
	// enough to cover even security-rejected requests, not just successful
	// ones a HandlerInterceptor would also see.
	@Test
	void correlationIdIsPresentOnASecurityRejectedResponse() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized())
				.andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
	}

	// An unauthenticated request to a nonexistent path is rejected by Spring
	// Security's authenticated() rule before Spring MVC ever gets to decide
	// there's no handler for it, so it 401s rather than reaching this case at
	// all. Authenticating first gets past that, reaching GlobalExceptionHandler's
	// catch-all Exception handler - which currently maps every unhandled
	// exception, including a route with no matching handler, to a 500. That
	// mapping is arguably a separate pre-existing bug (an unmatched route
	// should be a 404, not a 500), but it's still a genuine unhandled-error
	// response, which is exactly the case this filter needs to cover.
	@Test
	void correlationIdIsPresentEvenOnAnUnhandledRouteResponse() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RegisterRequest("cid@acme.test", "supersecret", "Name"))))
				.andExpect(status().isCreated())
				.andReturn();
		String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken")
				.stringValue();

		mockMvc.perform(get("/api/v1/this-route-does-not-exist").header("Authorization", "Bearer " + token))
				.andExpect(status().is5xxServerError())
				.andExpect(header().exists(CorrelationIdFilter.HEADER_NAME));
	}

	@Test
	void mdcIsClearedAfterTheRequestCompletes() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"));

		assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
	}

}
