package com.sm.spendly.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.account.entity.AccountType;

/** One account as the app sees it. Money fields are decimal strings in JSON. */
public record AccountResponse(
		UUID id,
		@Schema(example = "HDFC Savings") String name,
		AccountType type,
		@Schema(example = "bank") String icon,
		@Schema(example = "#2563EB") String color,
		@Schema(type = "string", format = "decimal", example = "25000.00") BigDecimal openingBalance,
		@Schema(type = "string", format = "decimal", example = "23150.50",
				description = "Negative on a credit card means money owed.")
		BigDecimal currentBalance,
		@Schema(description = "true for CREDIT_CARD.") boolean liability,
		boolean includeInTotal,
		boolean archived,
		@Schema(description = "Display position, 0 = top.") int sortOrder,
		@Schema(type = "string", format = "decimal", example = "100000.00", nullable = true) BigDecimal creditLimit,
		@Schema(type = "string", format = "decimal", example = "95000.00", nullable = true,
				description = "Credit cards with a limit: limit + balance.")
		BigDecimal availableCredit,
		@Schema(type = "string", format = "decimal", example = "5000.00", nullable = true,
				description = "Credit cards: how much is owed (0.00 if nothing). null for other types.")
		BigDecimal amountOwed,
		@Schema(nullable = true) Integer statementDay,
		@Schema(nullable = true) Integer dueDay,
		Instant createdAt) {
}
