package com.sm.spendly.common.money;

import java.math.BigDecimal;

/**
 * Small helpers for money. Money is always BigDecimal: never double or float, which
 * can't hold 0.1 exactly (0.1 + 0.2 == 0.30000000000000004).
 */
public final class Money {

	/** Same as the database: NUMERIC(19,4) = 15 digits before the point, 4 after. */
	public static final int INTEGER_DIGITS = 15;

	public static final int FRACTION_DIGITS = 4;

	private Money() {
	}

	/**
	 * The form we send to the app: at least 2 decimals, no useless trailing zeros.
	 * The database gives back 1250.5000; the app gets "1250.50". 12.3456 stays 12.3456.
	 */
	public static BigDecimal forDisplay(BigDecimal value) {
		if (value == null) {
			return null;
		}
		BigDecimal stripped = value.stripTrailingZeros();
		return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
	}

	/** Comparisons must use compareTo: new BigDecimal("2.0").equals(new BigDecimal("2.00")) is false. */
	public static boolean isZero(BigDecimal value) {
		return value.compareTo(BigDecimal.ZERO) == 0;
	}

}
