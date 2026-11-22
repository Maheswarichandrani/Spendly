package com.sm.spendly.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.common.exception.BadRequestException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.service.UserService;

/**
 * Sign-up today; login, refresh and logout in step 005.
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

	private final UserService userService;

	private final PasswordEncoder passwordEncoder;

	public AuthService(UserService userService, PasswordEncoder passwordEncoder) {
		this.userService = userService;
		this.passwordEncoder = passwordEncoder;
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

}
