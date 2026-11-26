package com.sm.spendly.account.entity;

/**
 * Kinds of account. Stored by name. Only CREDIT_CARD is a liability (money owed);
 * everything else is an asset (money you have).
 */
public enum AccountType {

	CASH("money"),
	BANK("bank"),
	SAVINGS("piggy-bank"),
	DEBIT_CARD("credit-card"),
	CREDIT_CARD("credit-card"),
	WALLET("wallet"),
	INVESTMENT("chart-line-up"),
	OTHER("circles-three");

	private final String defaultIcon;

	AccountType(String defaultIcon) {
		this.defaultIcon = defaultIcon;
	}

	/** Phosphor icon name used when the app doesn't send one. */
	public String defaultIcon() {
		return defaultIcon;
	}

	public boolean isLiability() {
		return this == CREDIT_CARD;
	}

}
