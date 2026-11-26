package com.sm.spendly.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.sm.spendly.common.exception.ConflictException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.NotFoundException;
import com.sm.spendly.user.dto.LoginCredentials;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.entity.ThemePreference;
import com.sm.spendly.user.entity.User;
import com.sm.spendly.user.repository.UserRepository;

/**
 * Plain unit test: no Spring, no database. The repository is a Mockito mock, so we
 * decide what it returns and check how the service uses it. Runs in milliseconds.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final String HASH = "$2a$12$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0";

	@Mock
	UserRepository userRepository;

	@InjectMocks
	UserService userService;

	@Test
	void createUser_savesAndReturnsResponse() {
		UUID id = UUID.randomUUID();
		when(userRepository.existsByEmail("ravi@mail.com")).thenReturn(false);
		// Pretend to be the database: give the saved entity an id, return it.
		when(userRepository.saveAndFlush(any(User.class))).thenAnswer(call -> {
			User user = call.getArgument(0);
			ReflectionTestUtils.setField(user, "id", id);
			return user;
		});

		UserResponse response = userService.createUser("ravi@mail.com", HASH, "Ravi Kumar");

		verify(userRepository).saveAndFlush(any(User.class));
		assertThat(response.id()).isEqualTo(id);
		assertThat(response.email()).isEqualTo("ravi@mail.com");
		assertThat(response.name()).isEqualTo("Ravi Kumar");
		assertThat(response.timezone()).isEqualTo("UTC");
		assertThat(response.weekStart()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(response.theme()).isEqualTo(ThemePreference.SYSTEM);
		assertThat(response.emailVerified()).isFalse();
		assertThat(response.currency()).isNull();
	}

	@Test
	void createUser_throwsEmailAlreadyRegistered_whenExists() {
		when(userRepository.existsByEmail("ravi@mail.com")).thenReturn(true);

		assertThatThrownBy(() -> userService.createUser("ravi@mail.com", HASH, "Ravi"))
			.isInstanceOf(ConflictException.class)
			.extracting(ex -> ((ConflictException) ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void createUser_throwsEmailAlreadyRegistered_onRaceAtFlush() {
		when(userRepository.existsByEmail("ravi@mail.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(uniqueViolation("users_email_key"));

		assertThatThrownBy(() -> userService.createUser("ravi@mail.com", HASH, "Ravi"))
			.isInstanceOf(ConflictException.class)
			.extracting(ex -> ((ConflictException) ex).getErrorCode())
			.isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);
	}

	@Test
	void createUser_rethrowsOtherConstraintViolations() {
		// A different constraint is a bug, not a duplicate email: don't hide it as a 409.
		DataIntegrityViolationException other = uniqueViolation("users_name_length");
		when(userRepository.existsByEmail("ravi@mail.com")).thenReturn(false);
		when(userRepository.saveAndFlush(any(User.class))).thenThrow(other);

		assertThatThrownBy(() -> userService.createUser("ravi@mail.com", HASH, "Ravi")).isSameAs(other);
	}

	@Test
	void createUser_rejectsNullHash() {
		assertThatThrownBy(() -> userService.createUser("ravi@mail.com", null, "Ravi"))
			.isInstanceOf(NullPointerException.class);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void getById_returnsUser() {
		UUID id = UUID.randomUUID();
		when(userRepository.findById(id)).thenReturn(Optional.of(userWithId("ravi@mail.com", id)));

		UserResponse response = userService.getById(id);

		assertThat(response.id()).isEqualTo(id);
		assertThat(response.email()).isEqualTo("ravi@mail.com");
	}

	@Test
	void getById_missing_throwsUserNotFound() {
		UUID id = UUID.randomUUID();
		when(userRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> userService.getById(id))
			.isInstanceOf(NotFoundException.class)
			.extracting(ex -> ((NotFoundException) ex).getErrorCode())
			.isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void findLoginCredentials_mapsIdAndHash() {
		UUID id = UUID.randomUUID();
		when(userRepository.findByEmail("ravi@mail.com")).thenReturn(Optional.of(userWithId("ravi@mail.com", id)));

		Optional<LoginCredentials> credentials = userService.findLoginCredentials("ravi@mail.com");

		assertThat(credentials).contains(new LoginCredentials(id, HASH));
	}

	@Test
	void findLoginCredentials_unknown_returnsEmpty() {
		when(userRepository.findByEmail("nobody@mail.com")).thenReturn(Optional.empty());

		assertThat(userService.findLoginCredentials("nobody@mail.com")).isEmpty();
	}

	private static User userWithId(String email, UUID id) {
		User user = new User(email, HASH, "Ravi");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	/** Same shape Spring produces: its exception wrapping Hibernate's, which names the constraint. */
	private static DataIntegrityViolationException uniqueViolation(String constraint) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("duplicate key", new SQLException(), constraint));
	}

}
