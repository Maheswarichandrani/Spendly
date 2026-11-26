package com.sm.spendly.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The validators are plain classes, so we can call isValid directly. No Spring needed. */
class ValidatorsTest {

	private final CurrencyCodeValidator currency = new CurrencyCodeValidator();

	private final TimezoneValidator timezone = new TimezoneValidator();

	@ParameterizedTest
	@ValueSource(strings = { "INR", "USD", "EUR", "JPY" })
	@NullSource
	void currency_valid(String value) {
		assertThat(currency.isValid(value, null)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "inr", "RUPEE", "XYZ", "IN" })
	void currency_invalid(String value) {
		assertThat(currency.isValid(value, null)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Asia/Kolkata", "Europe/London", "America/New_York", "UTC" })
	@NullSource
	void timezone_valid(String value) {
		assertThat(timezone.isValid(value, null)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "+05:30", "IST", "Asia/Mumbai", "india" })
	void timezone_invalid(String value) {
		assertThat(timezone.isValid(value, null)).isFalse();
	}

}
