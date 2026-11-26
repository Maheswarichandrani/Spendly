package com.sm.spendly.account.dto;

import java.math.BigDecimal;
import java.util.Locale;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.account.entity.AccountType;

/** Body of POST /api/v1/accounts. Only name and type are required. */
public record CreateAccountRequest(
		@NotBlank(message = "Name is required.")
		@Size(max = 50, message = "Name must be at most 50 characters.")
		@Schema(example = "HDFC Savings")
		String name,

		@NotNull(message = "Type is required.")
		@Schema(description = "Cannot be changed later. CREDIT_CARD is a liability.")
		AccountType type,

		@Pattern(regexp = "^[a-z0-9-]{1,50}$", message = "Icon must be a Phosphor icon name like bank or credit-card.")
		@Schema(description = "Phosphor icon name. Default depends on type.", example = "bank", nullable = true)
		String icon,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must look like #1A2B3C.")
		@Schema(description = "Hex color. Default #2563EB.", example = "#2563EB", nullable = true)
		String color,

		// @Digits: at most 15 digits before the point and 4 after, like NUMERIC(19,4).
		@Digits(integer = 15, fraction = 4, message = "Opening balance must have at most 15 digits and 4 decimals.")
		@Schema(type = "string", format = "decimal", example = "25000.00", nullable = true,
				description = "Default 0. For a credit card, send the amount owed as a NEGATIVE number (-5000.00).")
		BigDecimal openingBalance,

		@Schema(description = "Count this account in net worth. Default true.", nullable = true)
		Boolean includeInTotal,

		@DecimalMin(value = "0", message = "Credit limit cannot be negative.")
		@Digits(integer = 15, fraction = 4, message = "Credit limit must have at most 15 digits and 4 decimals.")
		@Schema(type = "string", format = "decimal", example = "100000.00", nullable = true,
				description = "Credit cards only.")
		BigDecimal creditLimit,

		@Min(value = 1, message = "Statement day must be 1 to 31.")
		@Max(value = 31, message = "Statement day must be 1 to 31.")
		@Schema(description = "Credit cards only. Day of month the statement is generated.", example = "5", nullable = true)
		Integer statementDay,

		@Min(value = 1, message = "Due day must be 1 to 31.")
		@Max(value = 31, message = "Due day must be 1 to 31.")
		@Schema(description = "Credit cards only. Day of month the bill is due.", example = "25", nullable = true)
		Integer dueDay) {

	public CreateAccountRequest {
		name = name == null ? null : name.strip();
		color = color == null ? null : color.toUpperCase(Locale.ROOT);
	}

}
