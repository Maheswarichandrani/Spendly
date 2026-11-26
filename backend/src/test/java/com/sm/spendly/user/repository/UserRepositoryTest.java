package com.sm.spendly.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.sm.spendly.user.entity.WeekStart;
import com.sm.spendly.TestcontainersConfiguration;
import com.sm.spendly.common.persistence.ConstraintNames;
import com.sm.spendly.user.entity.ThemePreference;
import com.sm.spendly.user.entity.User;

/**
 * JPA slice: starts only JPA, Flyway and the DataSource (no web layer), against the real
 * Postgres container. Every test runs in a transaction that is rolled back at the end,
 * so tests don't see each other's rows.
 *
 * <p>replace = NONE: keep our Testcontainers database instead of an in-memory one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class UserRepositoryTest {

	// Cost 4 (the minimum) keeps tests fast; the stored format is the same.
	private static final String HASH = new BCryptPasswordEncoder(4).encode("correct-horse-42");

	@Autowired
	UserRepository userRepository;

	@Autowired
	EntityManager entityManager;

	@Test
	void save_assignsUuidV7IdAndTimestamps() {
		User saved = userRepository.saveAndFlush(new User("ravi@mail.com", HASH, "Ravi"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getId().version()).isEqualTo(7);
		assertThat(saved.getCreatedAt()).isNotNull();
		assertThat(saved.getUpdatedAt()).isNotNull();
	}

	@Test
	void save_appliesDefaults() {
		User saved = userRepository.saveAndFlush(new User("ravi@mail.com", HASH, "Ravi"));
		// Forget the cached object, so findById really reads the row from Postgres.
		entityManager.clear();

		User loaded = userRepository.findById(saved.getId()).orElseThrow();

		assertThat(loaded.getTimezone()).isEqualTo("UTC");
		assertThat(loaded.getWeekStart()).isEqualTo(WeekStart.MONDAY);
		assertThat(loaded.getTheme()).isEqualTo(ThemePreference.SYSTEM);
		assertThat(loaded.isEmailVerified()).isFalse();
		assertThat(loaded.getCurrency()).isNull();
	}

	@Test
	void existsByEmail_trueOnlyForSavedEmail() {
		userRepository.saveAndFlush(new User("ravi@mail.com", HASH, "Ravi"));

		assertThat(userRepository.existsByEmail("ravi@mail.com")).isTrue();
		assertThat(userRepository.existsByEmail("someone@mail.com")).isFalse();
	}

	@Test
	void duplicateEmail_violatesUniqueConstraint() {
		userRepository.saveAndFlush(new User("ravi@mail.com", HASH, "Ravi"));

		assertThatThrownBy(() -> userRepository.saveAndFlush(new User("ravi@mail.com", HASH, "Other")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(ConstraintNames.of((DataIntegrityViolationException) ex))
				.isEqualTo("users_email_key"));
	}

	@Test
	void uppercaseEmail_violatesCheckConstraint() {
		assertThatThrownBy(() -> userRepository.saveAndFlush(new User("Ravi@Mail.com", HASH, "Ravi")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(ConstraintNames.of((DataIntegrityViolationException) ex))
				.isEqualTo("users_email_lowercase"));
	}

	@Test
	void nullPasswordHash_isAllowed() {
		// Ready for Google-only users later.
		User saved = userRepository.saveAndFlush(new User("google@mail.com", null, "Google User"));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getPasswordHash()).isNull();
	}

	@Test
	void plainTextPasswordHash_violatesCheckConstraint() {
		assertThatThrownBy(
				() -> userRepository.saveAndFlush(new User("ravi@mail.com", "correct-horse-42", "Ravi")))
			.isInstanceOf(DataIntegrityViolationException.class)
			.satisfies(ex -> assertThat(ConstraintNames.of((DataIntegrityViolationException) ex))
				.isEqualTo("users_password_bcrypt"));
	}

}
