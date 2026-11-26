package com.sm.spendly.user.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.user.entity.ThemePreference;
import com.sm.spendly.user.entity.WeekStart;

/** What the API shows about a user. Has no password field on purpose. */
public record UserResponse(
		@Schema(example = "019376a2-5c1e-7d3a-9f2b-4c8e1a0b2d3f") UUID id,
		@Schema(example = "Ravi Kumar") String name,
		@Schema(example = "ravi@mail.com") String email,
		boolean emailVerified,
		@Schema(description = "ISO 4217 code. null until chosen in onboarding.", example = "INR", nullable = true)
		String currency,
		@Schema(description = "IANA timezone. 'Today' and 'this month' are computed in this zone.", example = "Asia/Kolkata")
		String timezone,
		WeekStart weekStart,
		ThemePreference theme,
		@Schema(description = "false = show the onboarding screens after login.")
		boolean onboardingCompleted,
		@Schema(example = "2026-11-26T08:15:00Z") Instant createdAt) {
}
