package com.sm.spendly.user.service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sm.spendly.common.exception.BusinessRuleException;
import com.sm.spendly.common.exception.ConflictException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.NotFoundException;
import com.sm.spendly.common.persistence.ConstraintNames;
import com.sm.spendly.user.dto.LoginCredentials;
import com.sm.spendly.user.dto.UpdateProfileRequest;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.entity.User;
import com.sm.spendly.user.mapper.UserMapper;
import com.sm.spendly.user.repository.UserRepository;

/**
 * Owns the users table. Other features (auth today, profile later) go through this
 * service and never touch UserRepository directly.
 */
@Service
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_key";

	private static final String EMAIL_TAKEN_DETAIL = "An account with this email already exists.";

	private final UserRepository userRepository;

	// Constructor injection: the dependency is final and visible, and a unit test can
	// pass a mock with plain "new UserService(mock)".
	public UserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/**
	 * Saves a new user. All-or-nothing: if anything throws, nothing is saved.
	 *
	 * <p>The caller must pass an already normalized (lowercase) email and an already
	 * computed hash. Hashing is slow, so it happens before the transaction opens.
	 */
	@Transactional
	public UserResponse createUser(String email, String passwordHash, String name) {
		// Phase 1 always has a password. A null here is a programming bug, not user input.
		Objects.requireNonNull(passwordHash, "passwordHash must not be null in email registration");

		// Fast, clear answer for the normal case.
		if (userRepository.existsByEmail(email)) {
			log.info("Registration rejected: email already registered");
			throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, EMAIL_TAKEN_DETAIL);
		}

		try {
			// saveAndFlush, not save: save() only queues the INSERT in the persistence
			// context, and the SQL would run at commit, after this try block. Flushing
			// now makes a unique violation happen here, where we can catch it.
			User saved = userRepository.saveAndFlush(new User(email, passwordHash, name));
			log.info("User registered userId={}", saved.getId());
			return UserMapper.toResponse(saved);
		}
		catch (DataIntegrityViolationException ex) {
			// Two requests with the same email passed existsByEmail at the same moment.
			// The database's unique constraint stopped the second one.
			if (EMAIL_UNIQUE_CONSTRAINT.equals(ConstraintNames.of(ex))) {
				log.warn("Registration race on unique email, returned 409");
				throw new ConflictException(ErrorCode.EMAIL_ALREADY_REGISTERED, EMAIL_TAKEN_DETAIL);
			}
			// Any other constraint is a bug: let the global handler deal with it.
			throw ex;
		}
	}

	/** Login lookup. Hands out only id + hash, never the entity. */
	@Transactional(readOnly = true)
	public Optional<LoginCredentials> findLoginCredentials(String email) {
		return userRepository.findByEmail(email)
			.map(user -> new LoginCredentials(user.getId(), user.getPasswordHash()));
	}

	/** The id comes from the token, so a miss means the user was deleted after login. */
	@Transactional(readOnly = true)
	public UserResponse getById(UUID id) {
		return UserMapper.toResponse(load(id));
	}

	/** Same as {@link #findLoginCredentials(String)}, by id: for "confirm your password" checks. */
	@Transactional(readOnly = true)
	public LoginCredentials getLoginCredentials(UUID id) {
		User user = load(id);
		return new LoginCredentials(user.getId(), user.getPasswordHash());
	}

	/**
	 * PATCH: change only the fields that were sent (non-null).
	 *
	 * <p>Notice there is no {@code userRepository.save(user)}. {@code load} returns a
	 * <b>managed</b> entity: it lives in this transaction's persistence context, which kept
	 * a snapshot of it. When the method returns, the transaction commits, Hibernate compares
	 * the entity with the snapshot ("dirty checking") and writes one UPDATE with the changed
	 * columns. If nothing changed, it writes nothing.
	 */
	@Transactional
	public UserResponse updateProfile(UUID id, UpdateProfileRequest request) {
		User user = load(id);
		if (request.name() != null) {
			user.rename(request.name());
		}
		if (request.currency() != null) {
			user.changeCurrency(request.currency());
		}
		if (request.timezone() != null) {
			user.changeTimezone(request.timezone());
		}
		if (request.weekStart() != null) {
			user.changeWeekStart(request.weekStart());
		}
		if (request.theme() != null) {
			user.changeTheme(request.theme());
		}
		log.info("Profile updated userId={}", id);
		// The UPDATE has not run yet (it runs at commit), but the Java object already holds
		// the new values, so the response shows them.
		return UserMapper.toResponse(user);
	}

	/** Onboarding needs a currency: every amount on every screen is shown in it. */
	@Transactional
	public UserResponse completeOnboarding(UUID id) {
		User user = load(id);
		if (user.getCurrency() == null) {
			throw new BusinessRuleException(ErrorCode.CURRENCY_REQUIRED,
					"Choose a currency before finishing onboarding.");
		}
		user.completeOnboarding(Instant.now());
		log.info("Onboarding completed userId={}", id);
		return UserMapper.toResponse(user);
	}

	/** The caller has already checked the old password and hashed the new one. */
	@Transactional
	public void changePasswordHash(UUID id, String newPasswordHash) {
		Objects.requireNonNull(newPasswordHash, "newPasswordHash");
		load(id).changePasswordHash(newPasswordHash);
		log.info("Password changed userId={}", id);
	}

	/**
	 * Deletes the user row. The database deletes everything that belongs to the user with
	 * it (ON DELETE CASCADE on every user-owned table, starting with refresh_tokens).
	 */
	@Transactional
	public void deleteUser(UUID id) {
		userRepository.delete(load(id));
		log.info("User deleted userId={}", id);
	}

	/** Every lookup by id goes through here, so "not found" is handled the same everywhere. */
	private User load(UUID id) {
		return userRepository.findById(id)
			.orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found."));
	}

}
