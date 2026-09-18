package com.taskforge.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.taskforge.common.exception.support.TestExceptionController;
import com.taskforge.organization.MembershipRepository;
import com.taskforge.security.TenantContext;

import static com.taskforge.common.exception.GeneralErrorCode.INSUFFICIENT_PERMISSIONS;
import static com.taskforge.common.exception.GeneralErrorCode.RESOURCE_CONFLICT;
import static com.taskforge.common.exception.GeneralErrorCode.RESOURCE_NOT_FOUND;
import static com.taskforge.common.exception.GeneralErrorCode.SERVER_UNEXPECTED_ERROR;
import static com.taskforge.common.exception.GeneralErrorCode.VALIDATION_FAILED;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestExceptionController.class)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	// TenantInterceptor/WebMvcConfig are swept into every @WebMvcTest slice
	// (HandlerInterceptor and WebMvcConfigurer are both auto-scanned types),
	// so their dependencies need mocks here even though this test never
	// exercises tenant scoping itself.
	@MockitoBean
	private MembershipRepository membershipRepository;

	@MockitoBean
	private TenantContext tenantContext;

	@Test
	void resourceNotFoundException_returns404ProblemDetail() throws Exception {
		mockMvc.perform(get("/test/not-found"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.errorCode").value(RESOURCE_NOT_FOUND.code()))
				.andExpect(jsonPath("$.detail").value("Widget 42 not found"));
	}

	@Test
	void conflictException_returns409ProblemDetail() throws Exception {
		mockMvc.perform(get("/test/conflict"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.errorCode").value(RESOURCE_CONFLICT.code()))
				.andExpect(jsonPath("$.detail").value("Widget 42 already exists"));
	}

	@Test
	void invalidRequestBody_returns400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/test/validate")
						.contentType("application/json")
						.content("{\"name\": \"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value(VALIDATION_FAILED.code()))
				.andExpect(jsonPath("$.errors", hasSize(1)))
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void unexpectedException_returns500ProblemDetail() throws Exception {
		mockMvc.perform(get("/test/boom"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.errorCode").value(SERVER_UNEXPECTED_ERROR.code()))
				.andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
	}

	@Test
	void accessDeniedException_returns403ProblemDetail() throws Exception {
		mockMvc.perform(get("/test/forbidden"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value(INSUFFICIENT_PERMISSIONS.code()));
	}

}
