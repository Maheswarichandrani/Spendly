package com.sm.spendly.account.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/** Net worth over active accounts that are included in totals. */
public record AccountSummaryResponse(
		@Schema(type = "string", format = "decimal", example = "125000.00",
				description = "Sum of all non-credit-card balances.")
		BigDecimal totalAssets,
		@Schema(type = "string", format = "decimal", example = "15000.00",
				description = "Total owed on credit cards, as a positive number.")
		BigDecimal totalLiabilities,
		@Schema(type = "string", format = "decimal", example = "110000.00", description = "Assets minus liabilities.")
		BigDecimal netWorth,
		@Schema(example = "4") long accountCount,
		@Schema(description = "The user's currency, for formatting. null before onboarding.", example = "INR",
				nullable = true)
		String currency) {
}
