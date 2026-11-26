package com.sm.spendly.account.dto;

import java.math.BigDecimal;
import java.util.Locale;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of PATCH /api/v1/accounts/{id}. Missing or null fields stay unchanged (note 008).
 * The type can't be changed.
 */
@Schema(description = "Send only the fields you want to change.")
public record UpdateAccountRequest(
		@Size(min = 1, max = 50, message = "Name must be 1 to 50 characters.")
		@Schema(example = "HDFC Savings", nullable = true)
		String name,

		@Pattern(regexp = "^[a-z0-9-]{1,50}$", message = "Icon must be a Phosphor icon name like bank or credit-card.")
		@Schema(example = "bank", nullable = true)
		String icon,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must look like #1A2B3C.")
		@Schema(example = "#2563EB", nullable = true)
		String color,

		@Digits(integer = 15, fraction = 4, message = "Opening balance must have at most 15 digits and 4 decimals.")
		@Schema(type = "string", format = "decimal", example = "25000.00", nullable = true,
				description = "Changing it moves the current balance by the same difference.")
		BigDecimal openingBalance,

		@Schema(nullable = true) Boolean includeInTotal,

		@DecimalMin(value = "0", message = "Credit limit cannot be negative.")
		@Digits(integer = 15, fraction = 4, message = "Credit limit must have at most 15 digits and 4 decimals.")
		@Schema(type = "string", format = "decimal", example = "100000.00", nullable = true)
		BigDecimal creditLimit,

		@Min(value = 1, message = "Statement day must be 1 to 31.")
		@Max(value = 31, message = "Statement day must be 1 to 31.")
		@Schema(nullable = true) Integer statementDay,

		@Min(value = 1, message = "Due day must be 1 to 31.")
		@Max(value = 31, message = "Due day must be 1 to 31.")
		@Schema(nullable = true) Integer dueDay) {

	public UpdateAccountRequest {
		name = name == null ? null : name.strip();
		color = color == null ? null : color.toUpperCase(Locale.ROOT);
	}

}
