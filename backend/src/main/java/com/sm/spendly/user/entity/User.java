package com.sm.spendly.user.entity;

import java.time.DayOfWeek;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sm.spendly.common.persistence.BaseEntity;

import lombok.Getter;

/**
 * One row of the {@code users} table. Never leaves the service layer: controllers get
 * a {@link com.sm.spendly.user.dto.UserResponse} instead.
 *
 * <p>Only {@code @Getter} from Lombok. No {@code @Data}: its generated equals/hashCode
 * would use every field and break entity identity (see BaseEntity).
 */
@Entity
@Table(name = "users")
@Getter
public class User extends BaseEntity {

	@Column(name = "email", nullable = false)
	private String email;

	// Null only for future Google-only users. Never returned by the API.
	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "email_verified", nullable = false)
	private boolean emailVerified;

	// ISO 4217 code like "INR". Null until onboarding.
	@Column(name = "currency")
	private String currency;

	// IANA zone like "Asia/Kolkata". Used to compute "today" and "this month".
	@Column(name = "timezone", nullable = false)
	private String timezone;

	// STRING, never ORDINAL: ORDINAL stores the position (0, 1, ...), which silently
	// changes meaning if the enum's order ever changes.
	@Enumerated(EnumType.STRING)
	@Column(name = "week_start", nullable = false)
	private DayOfWeek weekStart;

	@Enumerated(EnumType.STRING)
	@Column(name = "theme", nullable = false)
	private ThemePreference theme;

	/** For Hibernate only: it creates the object empty, then fills fields from the row. */
	protected User() {
	}

	/**
	 * A new user with default preferences. Hibernate writes every column on insert,
	 * so the defaults must be set here (the DB DEFAULTs are only a backup).
	 */
	public User(String email, String passwordHash, String name) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.emailVerified = false;
		this.currency = null;
		this.timezone = "UTC";
		this.weekStart = DayOfWeek.MONDAY;
		this.theme = ThemePreference.SYSTEM;
	}

}
