package com.sm.spendly.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.sm.spendly.TestcontainersConfiguration;

/**
 * Docs switched on, like the dev profile. Reads the generated spec as JSON and checks the
 * parts the frontend relies on. The "docs off" case is in SecurityIntegrationTest, which
 * runs with the default properties.
 */
@SpringBootTest(properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpenApiDocsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	DocumentContext spec;

	@BeforeEach
	void loadSpec() throws Exception {
		// No Authorization header: the docs must be public when enabled.
		String json = mockMvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		spec = JsonPath.parse(json);
	}

	@Test
	void docsEnabled_apiDocsArePublicAndListEndpoints() {
		Map<String, Object> paths = spec.read("$.paths");
		assertThat(paths).containsKeys("/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/users/me");
		assertThat(spec.read("$.components.securitySchemes.bearerAuth.scheme", String.class)).isEqualTo("bearer");
		assertThat(spec.read("$.components.schemas.ApiProblem.properties", Map.class)).containsKeys("code", "requestId", "errors");
	}

	@Test
	void docsEnabled_loginIsPublic_meIsSecured() {
		List<?> loginSecurity = spec.read("$.paths['/api/v1/auth/login'].post.security");
		assertThat(loginSecurity).isEmpty();
		assertThat(spec.read("$.paths['/api/v1/auth/login'].post.responses", Map.class)).containsKey("401");

		Map<String, Object> meResponses = spec.read("$.paths['/api/v1/users/me'].get.responses");
		assertThat(meResponses).containsKeys("200", "401", "404", "500");
		assertThat(spec.read("$.paths['/api/v1/users/me'].get.responses.401.description", String.class))
			.contains("UNAUTHENTICATED");
	}

	@Test
	void docsEnabled_registerHasNoGenericUnauthenticated() {
		// Public endpoints must not advertise a 401 they can never return.
		Map<String, Object> responses = spec.read("$.paths['/api/v1/auth/register'].post.responses");
		assertThat(responses).containsKeys("201", "400", "409", "500").doesNotContainKey("401");
	}

	@Test
	void docsEnabled_everyOperationHas500() {
		List<Object> all500 = spec.read("$.paths.*.*.responses.500");
		List<Object> allOps = spec.read("$.paths.*.*.responses");
		assertThat(all500).hasSameSizeAs(allOps);
	}

	@Test
	void docsEnabled_swaggerUiIsPublic() throws Exception {
		// /swagger-ui.html redirects to /swagger-ui/index.html.
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

}
