package com.sm.spendly.account.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of PUT /api/v1/accounts/order: account ids in the new display order. */
public record ReorderAccountsRequest(
		@NotEmpty(message = "Account ids are required.")
		// Bounded: a user has at most 100 active accounts, so 200 leaves room and stops abuse.
		@Size(max = 200, message = "At most 200 account ids.")
		@Schema(description = "Ids in the new order, first = top of the list.")
		List<@NotNull(message = "Account id must not be null.") UUID> accountIds) {
}
