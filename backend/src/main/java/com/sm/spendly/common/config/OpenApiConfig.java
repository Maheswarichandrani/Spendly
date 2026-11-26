package com.sm.spendly.common.config;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sm.spendly.common.web.ApiProblem;

/**
 * The parts of the OpenAPI document that can't come from scanning controllers: title,
 * the JWT scheme, and the error responses every endpoint shares.
 *
 * <p>springdoc only builds the document when /v3/api-docs is first requested, and only if
 * {@code springdoc.api-docs.enabled=true}. With docs off, these beans exist but are unused.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	public static final String BEARER_AUTH = "bearerAuth";

	static final String PROBLEM_JSON = "application/problem+json";

	// Runs once when the class loads. JacksonConfig writes every BigDecimal as a JSON
	// string, so the docs must say "string" too, or the app developer would expect a number.
	static {
		SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class,
				new StringSchema().format("decimal").example("1250.50"));
	}

	@Bean
	OpenAPI spendlyOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("Spendly API")
				.version("v1")
				.description("""
						Personal finance API for the Spendly mobile app.

						**Auth:** call `POST /api/v1/auth/login`, then send `Authorization: Bearer <accessToken>` \
						on every other request. Click **Authorize** above to do that here.

						**Errors:** every error is `application/problem+json` (see the `ApiProblem` schema). \
						Switch on the `code` field.

						**Money** is a decimal string (`"1250.50"`), never a number. \
						**Times** are ISO-8601 UTC instants (`2026-11-26T08:15:00Z`)."""))
			.servers(List.of(new Server().url("/").description("This server")))
			// Applied to every operation. Public ones opt out with @SecurityRequirements().
			.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
			.components(new Components()
				.addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
					.type(SecurityScheme.Type.HTTP)
					.scheme("bearer")
					.bearerFormat("JWT")
					.description("Access token from /api/v1/auth/login. Expires after `expiresIn` seconds.")));
	}

	/**
	 * Runs on the finished document. Adds the responses every endpoint can give, so no
	 * controller has to repeat them, and they can't be forgotten on a new endpoint.
	 */
	@Bean
	OpenApiCustomizer commonErrorResponses() {
		return openApi -> {
			// Register ApiProblem (and ValidationError, which it uses) as named schemas.
			ModelConverters.getInstance().readAll(ApiProblem.class).forEach(openApi.getComponents()::addSchemas);

			if (openApi.getPaths() == null) {
				return;
			}
			openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
				ApiResponses responses = operation.getResponses();
				if (isSecured(operation)) {
					responses.putIfAbsent("401", problem("Missing, invalid or expired access token. code: `UNAUTHENTICATED`."));
				}
				responses.putIfAbsent("500", problem("Unexpected server error. code: `INTERNAL_ERROR`."));
			}));
		};
	}

	/**
	 * null security = the operation inherits the global bearer requirement.
	 * An empty list = @SecurityRequirements() on the method = public.
	 */
	private static boolean isSecured(Operation operation) {
		return operation.getSecurity() == null || !operation.getSecurity().isEmpty();
	}

	static ApiResponse problem(String description) {
		Schema<?> ref = new Schema<>().$ref("#/components/schemas/ApiProblem");
		return new ApiResponse()
			.description(description)
			.content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(ref)));
	}

}
