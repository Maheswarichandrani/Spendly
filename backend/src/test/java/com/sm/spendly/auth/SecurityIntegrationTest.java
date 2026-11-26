package com.sm.spendly.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sm.spendly.TestcontainersConfiguration;
import com.sm.spendly.common.security.TestKeys;

/**
 * The whole security chain for real: filter chain, JWT signing and checking, login,
 * /me, against Postgres. Every test uses its own emails (rows are committed).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIntegrationTest {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	JdbcTemplate jdbcTemplate;

	// --------------------------------------------------------------- happy path

	@Test
	void registerThenLoginThenMe_returnsOwnProfile() throws Exception {
		register("flow@mail.com");
		String token = login("Flow@Mail.com");

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("flow@mail.com"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void me_returnsOnlyTheTokenOwner() throws Exception {
		// Ownership: user A vs user B. There's no id in the URL, so the token alone decides.
		register("owner-a@mail.com");
		register("owner-b@mail.com");
		String tokenA = login("owner-a@mail.com");
		String tokenB = login("owner-b@mail.com");

		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
			.andExpect(jsonPath("$.email").value("owner-a@mail.com"));
		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
			.andExpect(jsonPath("$.email").value("owner-b@mail.com"));
	}

	@Test
	void me_forDeletedUser_returns404() throws Exception {
		register("deleted@mail.com");
		String token = login("deleted@mail.com");
		jdbcTemplate.update("delete from users where email = ?", "deleted@mail.com");

		// The token is still valid for a few minutes, but the user is gone.
		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	// ---------------------------------------------------------------- bad tokens

	@Test
	void me_withoutToken_returns401ProblemDetails() throws Exception {
		mockMvc.perform(get("/api/v1/users/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value("Authentication is required."))
			.andExpect(jsonPath("$.requestId").exists());
	}

	@Test
	void me_withExpiredToken_returns401() throws Exception {
		Instant past = Instant.now().minus(20, ChronoUnit.MINUTES);
		String token = sign(jwtEncoder, "spendly", past, past.plus(18, ChronoUnit.MINUTES)); // expired 2 min ago

		expectUnauthenticated(token);
	}

	@Test
	void me_withTokenSignedByOtherKey_returns401() throws Exception {
		// Right claims, right "kid", but signed with a key that isn't ours: a forgery.
		KeyPair other = TestKeys.newRsaKeyPair(2048);
		RSAKey jwk = new RSAKey.Builder((RSAPublicKey) other.getPublic())
			.privateKey((RSAPrivateKey) other.getPrivate())
			.keyID("spendly-test")
			.build();
		JwtEncoder forger = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
		Instant now = Instant.now();

		expectUnauthenticated(sign(forger, "spendly", now, now.plus(15, ChronoUnit.MINUTES)));
	}

	@Test
	void me_withWrongIssuer_returns401() throws Exception {
		Instant now = Instant.now();

		expectUnauthenticated(sign(jwtEncoder, "evil", now, now.plus(15, ChronoUnit.MINUTES)));
	}

	@Test
	void me_withAlgNoneToken_returns401() throws Exception {
		// The classic attack: an unsigned token that says "trust me, no signature needed".
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		long exp = Instant.now().plus(15, ChronoUnit.MINUTES).getEpochSecond();
		String payload = b64.encodeToString(("{\"sub\":\"" + UUID.randomUUID() + "\",\"iss\":\"spendly\",\"exp\":" + exp
				+ "}").getBytes(StandardCharsets.UTF_8));

		expectUnauthenticated(header + "." + payload + ".");
	}

	@Test
	void me_withGarbageToken_returns401() throws Exception {
		expectUnauthenticated("not-a-jwt");
	}

	// --------------------------------------------------------------------- login

	@Test
	void login_wrongPassword_and_unknownEmail_lookIdentical() throws Exception {
		register("same-answer@mail.com");

		MvcResult wrongPassword = mockMvc.perform(loginRequest("same-answer@mail.com", "wrong-password-1"))
			.andExpect(status().isUnauthorized())
			.andReturn();
		MvcResult unknownEmail = mockMvc.perform(loginRequest("nobody-here@mail.com", PASSWORD))
			.andExpect(status().isUnauthorized())
			.andReturn();

		for (String field : new String[] { "$.code", "$.title", "$.detail", "$.status" }) {
			Object a = JsonPath.read(wrongPassword.getResponse().getContentAsString(), field);
			Object b = JsonPath.read(unknownEmail.getResponse().getContentAsString(), field);
			assertThat(a).as(field).isEqualTo(b);
		}
		assertThat((String) JsonPath.read(wrongPassword.getResponse().getContentAsString(), "$.code"))
			.isEqualTo("INVALID_CREDENTIALS");
	}

	@Test
	void login_setsNoSessionCookie() throws Exception {
		register("cookie@mail.com");

		mockMvc.perform(loginRequest("cookie@mail.com", PASSWORD))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	// ------------------------------------------------------------ public / locked

	@Test
	void unknownPath_withoutToken_returns401() throws Exception {
		mockMvc.perform(get("/api/v1/nope"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void docsDisabled_apiDocsAndSwaggerUiNeedAuth() throws Exception {
		// Default properties = docs off (like prod): the docs URLs are not public.
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	void health_isPublic() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	// ------------------------------------------------------------------- helpers

	private void register(String email) throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Test User", "email": "%s", "password": "%s" }
				""".formatted(email, PASSWORD)))
			.andExpect(status().isCreated());
	}

	private String login(String email) throws Exception {
		MvcResult result = mockMvc.perform(loginRequest(email, PASSWORD)).andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
	}

	private static org.springframework.test.web.servlet.RequestBuilder loginRequest(String email, String password) {
		return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "%s", "password": "%s" }
				""".formatted(email, password));
	}

	private void expectUnauthenticated(String token) throws Exception {
		mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	private static String sign(JwtEncoder encoder, String issuer, Instant issuedAt, Instant expiresAt) {
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("spendly-test").build();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject(UUID.randomUUID().toString())
			.issuedAt(issuedAt)
			.expiresAt(expiresAt)
			.build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
