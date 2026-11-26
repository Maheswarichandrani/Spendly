package com.sm.spendly.user.dto;

import java.util.Locale;

import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.common.validation.ValidCurrency;
import com.sm.spendly.common.validation.ValidTimezone;
import com.sm.spendly.user.entity.ThemePreference;
import com.sm.spendly.user.entity.WeekStart;

/**
 * Body of PATCH /api/v1/users/me. PATCH means "change only what I send":
 * a field that is missing or null stays as it is. That is why nothing here is @NotNull.
 *
 * <p>Email and password are not here on purpose: they have their own flows.
 */
@Schema(description = "Send only the fields you want to change. Missing or null fields are left unchanged.")
public record UpdateProfileRequest(
		@Size(min = 1, max = 100, message = "Name must be 1 to 100 characters.")
		@Schema(example = "Ravi Kumar", nullable = true)
		String name,

		@ValidCurrency
		@Schema(description = "ISO 4217 code. Changes formatting only; amounts are never converted.",
				example = "INR", nullable = true)
		String currency,

		@ValidTimezone
		@Schema(description = "IANA timezone. Decides what 'today' and 'this month' mean.", example = "Asia/Kolkata",
				nullable = true)
		String timezone,

		@Schema(nullable = true) WeekStart weekStart,

		@Schema(nullable = true) ThemePreference theme) {

	/**
	 * Runs before validation. " Ravi " becomes "Ravi" and "  " becomes "" (which then fails
	 * min = 1, so a blank name is rejected instead of silently ignored). "inr" becomes "INR".
	 */
	public UpdateProfileRequest {
		name = name == null ? null : name.strip();
		currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
		timezone = timezone == null ? null : timezone.strip();
	}

}
