package com.sm.spendly.user.dto;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.UUID;

import com.sm.spendly.user.entity.ThemePreference;

/** What the API shows about a user. Has no password field on purpose. */
public record UserResponse(
		UUID id,
		String name,
		String email,
		boolean emailVerified,
		String currency,
		String timezone,
		DayOfWeek weekStart,
		ThemePreference theme,
		Instant createdAt) {
}
