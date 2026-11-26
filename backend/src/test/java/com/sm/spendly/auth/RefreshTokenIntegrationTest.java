package com.sm.spendly.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
 * Refresh, rotation, reuse detection and logout, over HTTP against real Postgres.
 * Each test registers its own user, because rows are committed (no rollback between tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefreshTokenIntegrationTest {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	record Tokens(String access, String refresh) {
	}

	// ------------------------------------------------------------------ rotation

	@Test
	void login_returnsRefreshToken_storedOnlyAsHash() throws Exception {
		String email = newUser();
		Tokens tokens = login(email, "Pixel 8");

		assertThat(tokens.refresh()).hasSize(43);
		Integer rawStored = jdbcTemplate.queryForObject(
				"select count(*) from refresh_tokens where token_hash = ?", Integer.class, tokens.refresh());
		assertThat(rawStored).isZero();
		String label = jdbcTemplate.queryForObject("""
				select t.device_label from refresh_tokens t join users u on u.id = t.user_id where u.email = ?""",
				String.class, email);
		assertThat(label).isEqualTo("Pixel 8");
	}

	@Test
	void refresh_returnsNewPair_andNewAccessTokenWorks() throws Exception {
		Tokens first = login(newUser(), null);

		Tokens second = refresh(first.refresh()).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString().transform(RefreshTokenIntegrationTest::parse);

		assertThat(second.refresh()).isNotEqualTo(first.refresh());
		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + second.access()))
			.andExpect(status().isOk());
	}

	@Test
	void refresh_oldTokenAfterRotation_isReuse_andKillsTheWholeFamily() throws Exception {
		Tokens first = login(newUser(), null);
		Tokens second = parse(refresh(first.refresh()).andReturn().getResponse().getContentAsString());

		// The thief (or a buggy retry) uses the first token again.
		expectInvalidRefresh(refresh(first.refresh()));

		// The reuse logged out the device: the newest token is dead too.
		expectInvalidRefresh(refresh(second.refresh()));
	}

	@Test
	void refresh_reuseDoesNotTouchOtherDevices() throws Exception {
		String email = newUser();
		Tokens phone = login(email, "phone");
		Tokens tablet = login(email, "tablet");

		refresh(phone.refresh()).andExpect(status().isOk());
		expectInvalidRefresh(refresh(phone.refresh()));

		refresh(tablet.refresh()).andExpect(status().isOk());
	}

	@Test
	void refresh_concurrentUseOfOneToken_atMostOneSucceeds() throws Exception {
		Tokens tokens = login(newUser(), null);

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				calls.add(() -> refresh(tokens.refresh()).andReturn().getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
			assertThat(statuses).containsOnly(200, 401);
			assertThat(statuses.stream().filter(s -> s == 200).count()).isLessThanOrEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void refresh_expiredToken_returns401() throws Exception {
		String email = newUser();
		Tokens tokens = login(email, null);
		jdbcTemplate.update("""
				update refresh_tokens set expires_at = now() - interval '1 minute'
				where user_id = (select id from users where email = ?)""", email);

		expectInvalidRefresh(refresh(tokens.refresh()));
	}

	@Test
	void refresh_unknownToken_returns401() throws Exception {
		expectInvalidRefresh(refresh("not-a-token-we-ever-issued"));
	}

	@Test
	void refresh_blankToken_returns400() throws Exception {
		refresh("").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	// -------------------------------------------------------------------- logout

	@Test
	void logout_needsNoAccessToken_andKillsThatDevice() throws Exception {
		Tokens tokens = login(newUser(), null);

		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content(body(tokens.refresh()))).andExpect(status().isNoContent());

		expectInvalidRefresh(refresh(tokens.refresh()));
	}

	@Test
	void logout_unknownToken_stillReturns204() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
			.content(body("nobody-has-this"))).andExpect(status().isNoContent());
	}

	@Test
	void logoutAll_killsEveryDeviceOfThatUserOnly() throws Exception {
		String emailA = newUser();
		Tokens phoneA = login(emailA, "phone");
		Tokens tabletA = login(emailA, "tablet");
		Tokens userB = login(newUser(), null);

		mockMvc.perform(post("/api/v1/auth/logout-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + phoneA.access()))
			.andExpect(status().isNoContent());

		expectInvalidRefresh(refresh(phoneA.refresh()));
		expectInvalidRefresh(refresh(tabletA.refresh()));
		// User B is untouched.
		refresh(userB.refresh()).andExpect(status().isOk());
	}

	@Test
	void logoutAll_withoutAccessToken_returns401() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout-all"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void deletingUser_deletesTheirRefreshTokens() throws Exception {
		String email = newUser();
		login(email, null);

		jdbcTemplate.update("delete from users where email = ?", email);

		Integer left = jdbcTemplate.queryForObject("""
				select count(*) from refresh_tokens t where not exists (select 1 from users u where u.id = t.user_id)""",
				Integer.class);
		assertThat(left).isZero();
	}

	// ------------------------------------------------------------------- helpers

	private String newUser() throws Exception {
		String email = "rt-" + UUID.randomUUID() + "@mail.com";
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Refresh Test", "email": "%s", "password": "%s" }
				""".formatted(email, PASSWORD))).andExpect(status().isCreated());
		return email;
	}

	private Tokens login(String email, String deviceLabel) throws Exception {
		String label = deviceLabel == null ? "null" : "\"" + deviceLabel + "\"";
		String json = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "%s", "password": "%s", "deviceLabel": %s }
				""".formatted(email, PASSWORD, label)))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		return parse(json);
	}

	private ResultActions refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
			.content(body(refreshToken)));
	}

	private static void expectInvalidRefresh(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
	}

	private static String body(String refreshToken) {
		return "{ \"refreshToken\": \"%s\" }".formatted(refreshToken);
	}

	private static Tokens parse(String json) {
		return new Tokens(JsonPath.read(json, "$.accessToken"), JsonPath.read(json, "$.refreshToken"));
	}

}
