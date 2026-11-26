package com.sm.spendly.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sm.spendly.auth.dto.LoginRequest;
import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.auth.dto.TokenResponse;
import com.sm.spendly.common.exception.BadRequestException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.UnauthorizedException;
import com.sm.spendly.user.dto.LoginCredentials;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.service.UserService;

/**
 * Sign-up and login. Refresh tokens and logout come in step 006.
 *
 * <p>No {@code @Transactional} here on purpose. BCrypt takes about 250 ms. Inside a
 * transaction that would keep a database connection busy doing nothing. So we hash
 * first, then call {@link UserService#createUser}, which opens its own short transaction.
 */
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	/** BCrypt reads only the first 72 bytes and silently ignores the rest. */
	static final int BCRYPT_MAX_BYTES = 72;

	/** The one answer for every login failure. Never say which part was wrong. */
	private static final String INVALID_CREDENTIALS_DETAIL = "Email or password is incorrect.";

	private final UserService userService;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokenService;

	/**
	 * A real BCrypt hash of a random value nobody knows, made once at startup with the
	 * same encoder (same cost) as real passwords. When the email is unknown we still
	 * check against it, so a wrong email takes as long as a wrong password (Decision 5).
	 */
	private final String dummyHash;

	public AuthService(UserService userService, PasswordEncoder passwordEncoder, TokenService tokenService) {
		this.userService = userService;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public UserResponse register(RegisterRequest request) {
		// Locale.ROOT: lowercasing with the server's locale can surprise
		// (Turkish turns "I" into a dotless "ı").
		String email = request.email().toLowerCase(Locale.ROOT);

		// @Size counted characters. Emoji and many Indian scripts take 3-4 bytes each,
		// so 30 emoji pass @Size but are 120 bytes. Reject instead of silently cutting.
		if (request.password().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
			log.debug("Registration rejected: password exceeds {} bytes", BCRYPT_MAX_BYTES);
			throw new BadRequestException(ErrorCode.PASSWORD_TOO_LONG,
					"Password is too long. Use at most 72 bytes; emoji and some letters count as several bytes.");
		}

		String passwordHash = passwordEncoder.encode(request.password());

		// A call to another bean goes through its Spring proxy, so @Transactional on
		// createUser applies. (Calling a @Transactional method of *this* class would not.)
		return userService.createUser(email, passwordHash, request.name());
	}

	/**
	 * Checks email and password and returns an access token.
	 *
	 * <p>Every failure path does exactly one BCrypt check and throws the same exception,
	 * so neither the answer nor the response time reveals whether the email exists.
	 */
	public TokenResponse login(LoginRequest request) {
		String email = request.email().toLowerCase(Locale.ROOT);
		String password = request.password();

		// A short read transaction inside UserService; it is closed again before the slow
		// BCrypt check below, so no DB connection waits on hashing.
		Optional<LoginCredentials> credentials = userService.findLoginCredentials(email);
		String storedHash = credentials.map(LoginCredentials::passwordHash).orElse(null);
		boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;

		if (storedHash == null || tooLong) {
			// Same cost as a real check. The input is a fixed safe value: BCrypt can't take
			// over 72 bytes, and the result is ignored anyway.
			passwordEncoder.matches("timing-equalizer", dummyHash);
			String reason = credentials.isEmpty() ? "unknown_email" : tooLong ? "too_long" : "no_password";
			throw loginFailed(reason);
		}

		if (!passwordEncoder.matches(password, storedHash)) {
			throw loginFailed("bad_password");
		}

		UUID userId = credentials.get().id();
		String accessToken = tokenService.issueAccessToken(userId);
		log.info("Login succeeded userId={}", userId);
		return TokenResponse.bearer(accessToken, tokenService.accessTokenTtl());
	}

	private static UnauthorizedException loginFailed(String reason) {
		// INFO line is identical for every reason; the reason (never the email) is DEBUG only.
		log.info("Login failed");
		log.debug("Login failed reason={}", reason);
		return new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS_DETAIL);
	}

}
