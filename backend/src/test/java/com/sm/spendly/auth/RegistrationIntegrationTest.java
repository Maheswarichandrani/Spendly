package com.sm.spendly.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.sm.spendly.TestcontainersConfiguration;

/**
 * Everything real: HTTP layer, validation, services, transaction, Hibernate, Flyway and
 * Postgres. Rows are not rolled back here (the request commits its own transaction),
 * so each test uses its own email.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RegistrationIntegrationTest {

	private static final String URL = "/api/v1/auth/register";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Test
	void register_storesBcryptHash() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Ravi Kumar", "email": "Hash.Test@Mail.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("hash.test@mail.com"))
			.andExpect(jsonPath("$.passwordHash").doesNotExist());

		String hash = jdbcTemplate.queryForObject("select password_hash from users where email = ?", String.class,
				"hash.test@mail.com");
		assertThat(hash).startsWith("$2a$12$");
		assertThat(passwordEncoder.matches("correct-horse-42", hash)).isTrue();
	}

	@Test
	void register_sameEmailDifferentCase_returns409() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "First", "email": "a@x.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isCreated());

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Second", "email": "A@X.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
	}

	@Test
	void register_emojiPasswordOver72Bytes_returns400PasswordTooLong() throws Exception {
		// 30 emoji pass @Size (60 Java chars) but are 120 bytes: AuthService rejects it.
		String password = "😀".repeat(30);

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Emoji", "email": "emoji@mail.com", "password": "%s" }
				""".formatted(password)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));

		Integer rows = jdbcTemplate.queryForObject("select count(*) from users where email = 'emoji@mail.com'",
				Integer.class);
		assertThat(rows).isZero();
	}

}
