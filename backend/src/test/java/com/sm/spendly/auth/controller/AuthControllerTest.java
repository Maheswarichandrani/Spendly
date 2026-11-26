package com.sm.spendly.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sm.spendly.user.entity.WeekStart;
import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.auth.dto.TokenResponse;
import com.sm.spendly.auth.service.AuthService;
import com.sm.spendly.common.exception.ConflictException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.UnauthorizedException;
import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.security.JwtConfig;
import com.sm.spendly.common.security.SecurityConfig;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.entity.ThemePreference;

/**
 * Web slice: only AuthController, the exception handler, Jackson and validation.
 * {@code @MockitoBean} puts a Mockito mock of AuthService into the Spring context,
 * so this tests HTTP behaviour alone (status codes, JSON, validation).
 *
 * <p>{@code @Import}: a web slice doesn't scan our @Configuration classes, so we bring in
 * the real security rules. That proves register and login are reachable without a token.
 */
@WebMvcTest(AuthController.class)
@Import({ SecurityConfig.class, JwtConfig.class })
class AuthControllerTest {

	private static final String URL = "/api/v1/auth/register";

	private static final String LOGIN_URL = "/api/v1/auth/login";

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	AuthService authService;

	// AuthController needs it for logout-all. Not used by these tests.
	@MockitoBean
	CurrentUser currentUser;

	@Test
	void register_returns201WithUser() throws Exception {
		UUID id = UUID.randomUUID();
		when(authService.register(any())).thenReturn(new UserResponse(id, "Ravi Kumar", "ravi@mail.com", false,
				null, "UTC", WeekStart.MONDAY, ThemePreference.SYSTEM, false, Instant.parse("2026-11-22T10:15:02Z")));

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Ravi Kumar", "email": "Ravi@Mail.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.email").value("ravi@mail.com"))
			.andExpect(jsonPath("$.weekStart").value("MONDAY"))
			.andExpect(jsonPath("$.createdAt").value("2026-11-22T10:15:02Z"))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void register_blankFields_returns400WithFieldErrors() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "", "email": "nope", "password": "short" }
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("email", "name", "password")));
	}

	@Test
	void register_shortPassword_returns400() throws Exception {
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Ravi", "email": "ravi@mail.com", "password": "short" }
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("password"));
	}

	@Test
	void register_duplicate_returns409() throws Exception {
		when(authService.register(any()))
			.thenThrow(new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists."));

		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Ravi", "email": "ravi@mail.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
	}

	@Test
	void register_stripsSpacesBeforeValidation() throws Exception {
		// " Ravi@Mail.com " would fail @Email if the spaces were still there.
		mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": " Ravi ", "email": " Ravi@Mail.com ", "password": " correct-horse-42 " }
				"""))
			.andExpect(status().isCreated());

		ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
		verify(authService).register(captor.capture());
		assertThat(captor.getValue().name()).isEqualTo("Ravi");
		assertThat(captor.getValue().email()).isEqualTo("Ravi@Mail.com");
		// The password is never changed: spaces can be part of it.
		assertThat(captor.getValue().password()).isEqualTo(" correct-horse-42 ");
	}

	// --------------------------------------------------------------------- login

	@Test
	void login_returns200WithToken() throws Exception {
		when(authService.login(any())).thenReturn(new TokenResponse("signed.jwt.value", "Bearer", 900, "raw-refresh", Instant.parse("2026-12-26T08:00:00Z")));

		mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "ravi@mail.com", "password": "correct-horse-42" }
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").value("signed.jwt.value"))
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andExpect(jsonPath("$.refreshToken").value("raw-refresh"));
	}

	@Test
	void login_blankFields_returns400() throws Exception {
		mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "", "password": "" }
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("email", "password")));
	}

	@Test
	void login_badCredentials_returns401() throws Exception {
		when(authService.login(any()))
			.thenThrow(new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect."));

		mockMvc.perform(post(LOGIN_URL).contentType(MediaType.APPLICATION_JSON).content("""
				{ "email": "ravi@mail.com", "password": "wrong-password" }
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andExpect(jsonPath("$.detail").value("Email or password is incorrect."));
	}

}
