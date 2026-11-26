package com.sm.spendly.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.sm.spendly.TestcontainersConfiguration;

/**
 * Profile, onboarding, change password and delete account, over HTTP against real Postgres.
 * Each test registers its own user (rows are committed between tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProfileIntegrationTest {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	record Session(String email, String access, String refresh) {
	}

	// ------------------------------------------------------------------- PATCH /me

	@Test
	void patch_changesOnlySentFields_andIsReallySaved() throws Exception {
		Session s = newSession();

		patchMe(s, """
				{ "currency": "inr", "timezone": "Asia/Kolkata", "weekStart": "SUNDAY" }
				""")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currency").value("INR"))
			.andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
			.andExpect(jsonPath("$.weekStart").value("SUNDAY"))
			.andExpect(jsonPath("$.name").value("Profile Test"))   // not sent: unchanged
			.andExpect(jsonPath("$.theme").value("SYSTEM"));       // not sent: unchanged

		// Proof that dirty checking wrote the UPDATE, read straight from the table.
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"select currency, timezone, week_start from users where email = ?", s.email());
		assertThat(row).containsEntry("currency", "INR").containsEntry("timezone", "Asia/Kolkata")
			.containsEntry("week_start", "SUNDAY");
	}

	@Test
	void patch_emptyBody_changesNothing() throws Exception {
		Session s = newSession();
		patchMe(s, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Profile Test"));
	}

	@Test
	void patch_invalidValues_return400WithOneErrorPerField() throws Exception {
		Session s = newSession();

		patchMe(s, """
				{ "name": "   ", "currency": "RUPEE", "timezone": "+05:30" }
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(3))
			.andExpect(jsonPath("$.errors[?(@.field == 'currency')].message")
				.value("Currency must be a valid 3-letter ISO 4217 code, like INR or USD."))
			.andExpect(jsonPath("$.errors[?(@.field == 'timezone')].message")
				.value("Timezone must be an IANA name, like Asia/Kolkata or Europe/London."));
	}

	@Test
	void patch_unknownWeekStart_returns400Malformed() throws Exception {
		Session s = newSession();
		patchMe(s, """
				{ "weekStart": "FRIDAY" }
				""")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void patch_onlyChangesTheCaller_notOtherUsers() throws Exception {
		Session a = newSession();
		Session b = newSession();

		patchMe(a, """
				{ "name": "Changed By A" }
				""").andExpect(status().isOk());

		getMe(b).andExpect(jsonPath("$.name").value("Profile Test"));
	}

	@Test
	void patch_withoutToken_returns401() throws Exception {
		mockMvc.perform(patch("/api/v1/users/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	// ------------------------------------------------------------------ onboarding

	@Test
	void onboarding_needsCurrency_thenCompletes() throws Exception {
		Session s = newSession();
		getMe(s).andExpect(jsonPath("$.onboardingCompleted").value(false));

		completeOnboarding(s)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CURRENCY_REQUIRED"));

		patchMe(s, """
				{ "currency": "INR" }
				""").andExpect(status().isOk());
		completeOnboarding(s).andExpect(status().isOk()).andExpect(jsonPath("$.onboardingCompleted").value(true));
		// Twice is fine.
		completeOnboarding(s).andExpect(status().isOk());
		getMe(s).andExpect(jsonPath("$.onboardingCompleted").value(true));
	}

	// ------------------------------------------------------------- change password

	@Test
	void changePassword_newPasswordWorks_oldOneDoesNot_andOtherDevicesAreLoggedOut() throws Exception {
		Session phone = newSession();
		Session tablet = login(phone.email(), PASSWORD);

		String json = changePassword(phone, PASSWORD, "battery-staple-77")
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		String newRefresh = JsonPath.read(json, "$.refreshToken");

		// Every old session is gone, on both devices.
		refresh(phone.refresh()).andExpect(status().isUnauthorized());
		refresh(tablet.refresh()).andExpect(status().isUnauthorized());
		// The fresh session from the response works.
		refresh(newRefresh).andExpect(status().isOk());
		// Old password no longer logs in, new one does.
		loginRequest(phone.email(), PASSWORD).andExpect(status().isUnauthorized());
		loginRequest(phone.email(), "battery-staple-77").andExpect(status().isOk());
	}

	@Test
	void changePassword_wrongCurrent_returns400NotUnauthorized() throws Exception {
		Session s = newSession();

		changePassword(s, "not-my-password", "battery-staple-77")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

		// Nothing changed: the session and the old password still work.
		refresh(s.refresh()).andExpect(status().isOk());
		loginRequest(s.email(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void changePassword_tooShort_returns400Validation() throws Exception {
		Session s = newSession();
		changePassword(s, PASSWORD, "short")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("newPassword"));
	}

	// -------------------------------------------------------------- delete account

	@Test
	void deleteAccount_removesUserAndSessions() throws Exception {
		Session s = newSession();

		mockMvc.perform(post("/api/v1/auth/delete-account").header(HttpHeaders.AUTHORIZATION, bearer(s))
			.contentType(MediaType.APPLICATION_JSON).content("{ \"password\": \"%s\" }".formatted(PASSWORD)))
			.andExpect(status().isNoContent());

		assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = ?", Integer.class, s.email()))
			.isZero();
		refresh(s.refresh()).andExpect(status().isUnauthorized());
		loginRequest(s.email(), PASSWORD).andExpect(status().isUnauthorized());
		// The access token is still a valid signature for up to 15 minutes, but the user is gone.
		getMe(s).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	@Test
	void deleteAccount_wrongPassword_keepsEverything() throws Exception {
		Session s = newSession();

		mockMvc.perform(post("/api/v1/auth/delete-account").header(HttpHeaders.AUTHORIZATION, bearer(s))
			.contentType(MediaType.APPLICATION_JSON).content("{ \"password\": \"wrong-password\" }"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

		getMe(s).andExpect(status().isOk());
	}

	// --------------------------------------------------------------------- helpers

	private Session newSession() throws Exception {
		String email = "profile-" + UUID.randomUUID() + "@mail.com";
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Profile Test", "email": "%s", "password": "%s" }
				""".formatted(email, PASSWORD))).andExpect(status().isCreated());
		return login(email, PASSWORD);
	}

	private Session login(String email, String password) throws Exception {
		String json = loginRequest(email, password).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
		return new Session(email, JsonPath.read(json, "$.accessToken"), JsonPath.read(json, "$.refreshToken"));
	}

	private ResultActions loginRequest(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, password)));
	}

	private ResultActions getMe(Session s) throws Exception {
		return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private ResultActions patchMe(Session s, String body) throws Exception {
		return mockMvc.perform(patch("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, bearer(s))
			.contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions completeOnboarding(Session s) throws Exception {
		return mockMvc.perform(post("/api/v1/users/me/onboarding/complete").header(HttpHeaders.AUTHORIZATION, bearer(s)));
	}

	private ResultActions changePassword(Session s, String current, String next) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/change-password").header(HttpHeaders.AUTHORIZATION, bearer(s))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{ \"currentPassword\": \"%s\", \"newPassword\": \"%s\" }".formatted(current, next)));
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"refreshToken\": \"%s\" }".formatted(refreshToken)));
	}

	private static String bearer(Session s) {
		return "Bearer " + s.access();
	}

}
