package com.sm.spendly.auth.service;

import com.sm.spendly.auth.service.RefreshTokenService.RotationResult;
import com.sm.spendly.auth.service.RefreshTokenService.IssuedRefreshToken;
import com.sm.spendly.auth.dto.RefreshTokenRequest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.sm.spendly.auth.dto.LoginRequest;
import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.auth.dto.TokenResponse;
import com.sm.spendly.common.exception.ApiException;
import com.sm.spendly.common.exception.BadRequestException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.UnauthorizedException;
import com.sm.spendly.user.dto.LoginCredentials;
import com.sm.spendly.user.service.UserService;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

	private static final String DUMMY_HASH = "DUMMY-HASH";

	private static final String TIMING_INPUT = "timing-equalizer";

	@Mock
	UserService userService;

	@Mock
	PasswordEncoder passwordEncoder;

	@Mock
	TokenService tokenService;

	@Mock
	RefreshTokenService refreshTokenService;

	AuthService authService;

	@BeforeEach
	void setUp() {
		// The constructor hashes a random value once to make the dummy hash.
		when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
		authService = new AuthService(userService, passwordEncoder, tokenService, refreshTokenService);
	}

	// ------------------------------------------------------------------ register

	@Test
	void register_normalizesEmailAndName() {
		when(passwordEncoder.encode("correct-horse-42")).thenReturn("hashed");

		authService.register(new RegisterRequest("  Ravi Kumar ", " Ravi@Mail.com ", "correct-horse-42"));

		// Spaces stripped by the record, lowercase applied by the service.
		verify(userService).createUser("ravi@mail.com", "hashed", "Ravi Kumar");
	}

	@Test
	void register_passesHashNotRawPassword() {
		when(passwordEncoder.encode("correct-horse-42")).thenReturn("hashed");
		ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);

		authService.register(new RegisterRequest("Ravi", "ravi@mail.com", "correct-horse-42"));

		verify(userService).createUser(eq("ravi@mail.com"), hash.capture(), eq("Ravi"));
		assertThat(hash.getValue()).isEqualTo("hashed").isNotEqualTo("correct-horse-42");
	}

	@Test
	void register_rejectsPasswordOver72Bytes() {
		// 30 emoji: 60 Java chars (passes @Size max 72) but 120 bytes in UTF-8.
		String emojiPassword = "😀".repeat(30);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("Ravi", "ravi@mail.com", emojiPassword)))
			.isInstanceOf(BadRequestException.class)
			.extracting(ex -> ((BadRequestException) ex).getErrorCode())
			.isEqualTo(ErrorCode.PASSWORD_TOO_LONG);
		verify(passwordEncoder, never()).encode(emojiPassword);
		verify(userService, never()).createUser(anyString(), anyString(), anyString());
	}

	@Test
	void register_accepts72ByteAsciiPassword() {
		String password = "a".repeat(72);
		when(passwordEncoder.encode(password)).thenReturn("hashed");

		authService.register(new RegisterRequest("Ravi", "ravi@mail.com", password));

		verify(userService).createUser(any(), eq("hashed"), any());
	}

	// --------------------------------------------------------------------- login

	@Test
	void login_returnsTokenForCorrectPassword() {
		UUID id = UUID.randomUUID();
		when(userService.findLoginCredentials("ravi@mail.com")).thenReturn(Optional.of(new LoginCredentials(id, "hash")));
		when(passwordEncoder.matches("correct-horse-42", "hash")).thenReturn(true);
		when(tokenService.issueAccessToken(id)).thenReturn("signed.jwt.value");
		when(tokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
		Instant refreshExpiry = Instant.parse("2026-12-26T08:00:00Z");
		when(refreshTokenService.issueForLogin(id, "Pixel 8")).thenReturn(new IssuedRefreshToken("raw-refresh", refreshExpiry));

		TokenResponse response = authService.login(new LoginRequest("ravi@mail.com", "correct-horse-42", "Pixel 8"));

		assertThat(response.accessToken()).isEqualTo("signed.jwt.value");
		assertThat(response.tokenType()).isEqualTo("Bearer");
		assertThat(response.expiresIn()).isEqualTo(900);
		assertThat(response.refreshToken()).isEqualTo("raw-refresh");
		assertThat(response.refreshTokenExpiresAt()).isEqualTo(refreshExpiry);
	}

	@Test
	void login_normalizesEmail() {
		when(userService.findLoginCredentials("ravi@mail.com")).thenReturn(Optional.empty());

		assertInvalidCredentials(() -> authService.login(new LoginRequest(" Ravi@Mail.com ", "correct-horse-42")));

		verify(userService).findLoginCredentials("ravi@mail.com");
	}

	@Test
	void login_unknownEmail_runsDummyCheck_andFails() {
		when(userService.findLoginCredentials("nobody@mail.com")).thenReturn(Optional.empty());

		assertInvalidCredentials(() -> authService.login(new LoginRequest("nobody@mail.com", "correct-horse-42")));

		// The expensive check still ran, so this answer takes as long as a wrong password.
		verify(passwordEncoder).matches(TIMING_INPUT, DUMMY_HASH);
		verifyNoInteractions(tokenService, refreshTokenService);
	}

	@Test
	void login_wrongPassword_fails() {
		when(userService.findLoginCredentials("ravi@mail.com"))
			.thenReturn(Optional.of(new LoginCredentials(UUID.randomUUID(), "hash")));
		when(passwordEncoder.matches("wrong-password", "hash")).thenReturn(false);

		assertInvalidCredentials(() -> authService.login(new LoginRequest("ravi@mail.com", "wrong-password")));

		verifyNoInteractions(tokenService, refreshTokenService);
	}

	@Test
	void login_nullHash_runsDummyCheck_andFails() {
		// A future Google-only user has no password.
		when(userService.findLoginCredentials("google@mail.com"))
			.thenReturn(Optional.of(new LoginCredentials(UUID.randomUUID(), null)));

		assertInvalidCredentials(() -> authService.login(new LoginRequest("google@mail.com", "any-password")));

		verify(passwordEncoder).matches(TIMING_INPUT, DUMMY_HASH);
		verifyNoInteractions(tokenService, refreshTokenService);
	}

	@Test
	void login_passwordOver72Bytes_fails() {
		String emojiPassword = "😀".repeat(30);
		when(userService.findLoginCredentials("ravi@mail.com"))
			.thenReturn(Optional.of(new LoginCredentials(UUID.randomUUID(), "hash")));

		assertInvalidCredentials(() -> authService.login(new LoginRequest("ravi@mail.com", emojiPassword)));

		// Never handed to BCrypt (it can't take over 72 bytes); the dummy check runs instead.
		verify(passwordEncoder, never()).matches(emojiPassword, "hash");
		verify(passwordEncoder).matches(TIMING_INPUT, DUMMY_HASH);
		verifyNoInteractions(tokenService, refreshTokenService);
	}

	private static void assertInvalidCredentials(Runnable login) {
		assertThatThrownBy(login::run)
			.isInstanceOf(UnauthorizedException.class)
			.hasMessage("Email or password is incorrect.")
			.extracting(ex -> ((ApiException) ex).getErrorCode())
			.isEqualTo(ErrorCode.INVALID_CREDENTIALS);
	}

	// ------------------------------------------------------------------- refresh

	@Test
	void refresh_rotated_returnsNewPair() {
		UUID id = UUID.randomUUID();
		Instant expiry = Instant.parse("2026-12-26T08:00:00Z");
		when(refreshTokenService.rotate("old-raw"))
			.thenReturn(new RotationResult.Rotated(id, new IssuedRefreshToken("new-raw", expiry)));
		when(tokenService.issueAccessToken(id)).thenReturn("new.jwt");
		when(tokenService.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));

		TokenResponse response = authService.refresh(new RefreshTokenRequest("old-raw"));

		assertThat(response.accessToken()).isEqualTo("new.jwt");
		assertThat(response.refreshToken()).isEqualTo("new-raw");
	}

	@ParameterizedTest
	@EnumSource(RotationResult.Reason.class)
	void refresh_rejected_throwsInvalidRefreshToken_andIssuesNoAccessToken(RotationResult.Reason reason) {
		when(refreshTokenService.rotate("bad")).thenReturn(new RotationResult.Rejected(reason));

		assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("bad")))
			.isInstanceOf(UnauthorizedException.class)
			.extracting(ex -> ((UnauthorizedException) ex).getErrorCode())
			.isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
		verifyNoInteractions(tokenService);
	}

	@Test
	void logout_revokesFamilyOfGivenToken() {
		authService.logout(new RefreshTokenRequest("raw"));
		verify(refreshTokenService).revokeFamilyOf("raw");
	}

	@Test
	void logoutEverywhere_revokesAllForUser() {
		UUID id = UUID.randomUUID();
		authService.logoutEverywhere(id);
		verify(refreshTokenService).revokeAllForUser(id);
	}

}
