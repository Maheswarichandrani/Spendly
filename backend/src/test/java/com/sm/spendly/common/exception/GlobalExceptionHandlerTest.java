package com.sm.spendly.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.sm.spendly.common.logging.RequestIdFilter;
import com.sm.spendly.common.security.JwtConfig;
import com.sm.spendly.common.security.SecurityConfig;

/**
 * Web-layer slice: starts only MVC (controllers, advice, Jackson, validation).
 * No database, no Docker, so it runs in seconds.
 *
 * <p>This test is about error <em>shapes</em>, not security. The probe URLs are locked like
 * every other URL, so {@code @WithMockUser} runs each test as a logged-in user.
 */
@WebMvcTest(ErrorProbeController.class)
@Import({ SecurityConfig.class, JwtConfig.class })
@WithMockUser
class GlobalExceptionHandlerTest {

	@Autowired
	MockMvc mockMvc;

	@Test
	void notFoundException_returns404ProblemWithCode() throws Exception {
		mockMvc.perform(get("/test/errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.title").value("Not found"))
			.andExpect(jsonPath("$.detail").value("Probe not found."))
			.andExpect(jsonPath("$.instance").value("/test/errors/not-found"))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void invalidBody_returns400WithFieldErrors() throws Exception {
		mockMvc.perform(post("/test/errors/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{ "name": "", "amount": -5 }
						"""))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.detail").value("2 fields are invalid."))
			.andExpect(jsonPath("$.errors", hasSize(2)))
			// sorted by field name, so the order is stable
			.andExpect(jsonPath("$.errors[0].field").value("amount"))
			.andExpect(jsonPath("$.errors[1].field").value("name"))
			// never echo what the client sent
			.andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
	}

	@Test
	void conflictException_returns409() throws Exception {
		mockMvc.perform(get("/test/errors/conflict"))
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.detail").value("Probe conflicts."));
	}

	@Test
	void businessRuleException_returns422() throws Exception {
		mockMvc.perform(get("/test/errors/business-rule"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
			.andExpect(jsonPath("$.detail").value("Probe rule violated."));
	}

	@Test
	void malformedJson_returns400MalformedRequest() throws Exception {
		mockMvc.perform(post("/test/errors/validate")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
			.andExpect(jsonPath("$.detail").value("The request body could not be read."));
	}

	@Test
	void nonUuidPathVariable_returns400InvalidParameter() throws Exception {
		mockMvc.perform(get("/test/errors/items/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
			// exact detail: the bad value "abc" is not echoed back in the message
			.andExpect(jsonPath("$.detail").value("Parameter 'id' is missing or invalid."));
	}

	@Test
	void unknownRoute_returns404NotFound() throws Exception {
		mockMvc.perform(get("/test/errors/nope"))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.instance").value("/test/errors/nope"));
	}

	@Test
	void wrongMethod_returns405() throws Exception {
		mockMvc.perform(delete("/test/errors/not-found"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void wrongContentType_returns415() throws Exception {
		mockMvc.perform(post("/test/errors/validate")
				.contentType(MediaType.TEXT_PLAIN)
				.content("name=x"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void unexpectedException_returns500WithoutLeakingMessage() throws Exception {
		mockMvc.perform(get("/test/errors/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.detail").value("Unexpected error."))
			.andExpect(content().string(not(containsString("secret internal detail"))))
			.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void dataIntegrityViolation_returns409WithoutLeakingSql() throws Exception {
		mockMvc.perform(get("/test/errors/integrity"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(content().string(not(containsString("users_email_key"))))
			.andExpect(content().string(not(containsString("duplicate key"))));
	}

	@Test
	void errorBody_containsSameRequestIdAsHeader() throws Exception {
		// @WebMvcTest also registers Filter beans, so RequestIdFilter runs here too.
		MvcResult result = mockMvc.perform(get("/test/errors/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(header().exists(RequestIdFilter.HEADER))
			.andReturn();

		String headerId = result.getResponse().getHeader(RequestIdFilter.HEADER);
		String bodyId = JsonPath.read(result.getResponse().getContentAsString(), "$.requestId");
		assertThat(bodyId).isEqualTo(headerId);
	}

}
