package com.taskforge.config;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

	private static final String BEARER_SCHEME = "bearerAuth";
	private static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";

	// Applied globally so every operation defaults to requiring a bearer token
	// in the docs; the handful of genuinely public auth endpoints override this
	// per-method with an empty @SecurityRequirements.
	@Bean
	public OpenAPI taskForgeOpenApi() {
		return new OpenAPI()
				.info(new Info()
						.title("TaskForge API")
						.description("Multi-tenant SaaS task management API.")
						.version("v1"))
				.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
				.components(new Components()
						.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT"))
						.addSchemas(PROBLEM_DETAIL_SCHEMA, problemDetailSchema()));
	}

	// GlobalExceptionHandler is the single place every error in this API is
	// produced, always as this same RFC 7807 ProblemDetail (+errorCode) shape -
	// so the error responses below are added here, once, instead of repeating
	// @ApiResponse across every one of the ~45 endpoints. Coverage is a
	// reasonable heuristic per operation, not a per-exception-type audit: 500
	// always applies; 400 when there's a request body to validate; 401/403 when
	// the operation isn't one of the explicitly public auth endpoints (a
	// permission check failure is possible anywhere a token is required); 404
	// when a path variable identifies the resource being looked up. 409
	// (conflict) is genuinely endpoint-specific (duplicate key, stale version)
	// and isn't heuristically detectable here, so it's left undocumented rather
	// than guessed at.
	@Bean
	public OperationCustomizer problemDetailResponses() {
		return (operation, handlerMethod) -> {
			operation.getResponses().addApiResponse("500", problemDetailResponse("Unexpected server error"));

			if (operation.getRequestBody() != null) {
				operation.getResponses().addApiResponse("400", problemDetailResponse("Validation failed"));
			}

			boolean requiresAuth = operation.getSecurity() == null || !operation.getSecurity().isEmpty();
			if (requiresAuth) {
				operation.getResponses()
						.addApiResponse("401", problemDetailResponse("Missing or invalid bearer token"))
						.addApiResponse("403", problemDetailResponse("Insufficient permissions"));
			}

			boolean hasPathVariable = operation.getParameters() != null
					&& operation.getParameters().stream().anyMatch(p -> "path".equals(p.getIn()));
			if (hasPathVariable) {
				operation.getResponses().addApiResponse("404", problemDetailResponse("Resource not found"));
			}

			return operation;
		};
	}

	private ApiResponse problemDetailResponse(String description) {
		return new ApiResponse()
				.description(description)
				.content(new Content().addMediaType("application/json",
						new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL_SCHEMA))));
	}

	private Schema<?> problemDetailSchema() {
		return new Schema<>()
				.type("object")
				.addProperty("type", new Schema<>().type("string").format("uri"))
				.addProperty("title", new Schema<>().type("string"))
				.addProperty("status", new Schema<>().type("integer"))
				.addProperty("detail", new Schema<>().type("string"))
				.addProperty("instance", new Schema<>().type("string").format("uri"))
				.addProperty("errorCode", new Schema<>().type("string"));
	}

}
