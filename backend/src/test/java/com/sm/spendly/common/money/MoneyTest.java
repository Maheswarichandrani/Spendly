package com.sm.spendly.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

	@ParameterizedTest
	@CsvSource({
			"1250.5000, 1250.50",   // database scale 4 -> 2 decimals
			"0.0000,    0.00",
			"12.3456,   12.3456",   // real extra digits are kept
			"1000,      1000.00",
			"-5000.0000, -5000.00",
			"1E+3,      1000.00" })
	void forDisplay(String stored, String shown) {
		assertThat(Money.forDisplay(new BigDecimal(stored)).toPlainString()).isEqualTo(shown);
	}

	@ParameterizedTest
	@CsvSource({ "0", "0.00", "0.0000" })
	void isZero_ignoresScale(String value) {
		assertThat(Money.isZero(new BigDecimal(value))).isTrue();
	}

}
