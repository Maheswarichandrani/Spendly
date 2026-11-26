package com.sm.spendly.account.mapper;

import java.math.BigDecimal;

import com.sm.spendly.account.dto.AccountResponse;
import com.sm.spendly.account.entity.Account;
import com.sm.spendly.common.money.Money;

/** Entity to DTO, plus the two numbers the app shouldn't compute itself. */
public final class AccountMapper {

	private AccountMapper() {
	}

	public static AccountResponse toResponse(Account account) {
		BigDecimal balance = account.getCurrentBalance();
		boolean liability = account.getType().isLiability();

		// Credit card: balance -5000 means 5000 owed. Positive (overpaid) means nothing owed.
		BigDecimal amountOwed = liability ? balance.negate().max(BigDecimal.ZERO) : null;
		BigDecimal availableCredit = liability && account.getCreditLimit() != null
				? account.getCreditLimit().add(balance)
				: null;

		return new AccountResponse(
				account.getId(),
				account.getName(),
				account.getType(),
				account.getIcon(),
				account.getColor(),
				Money.forDisplay(account.getOpeningBalance()),
				Money.forDisplay(balance),
				liability,
				account.isIncludeInTotal(),
				account.isArchived(),
				account.getSortOrder(),
				Money.forDisplay(account.getCreditLimit()),
				Money.forDisplay(availableCredit),
				Money.forDisplay(amountOwed),
				account.getStatementDay(),
				account.getDueDay(),
				account.getCreatedAt());
	}

}
