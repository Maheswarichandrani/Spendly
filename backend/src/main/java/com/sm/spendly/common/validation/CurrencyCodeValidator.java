package com.sm.spendly.common.validation;

import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** The check behind {@link ValidCurrency}. Bean Validation creates it and calls isValid. */
public class CurrencyCodeValidator implements ConstraintValidator<ValidCurrency, String> {

	/** Every currency the Java runtime knows (about 300). Built once, shared by all checks. */
	private static final Set<String> CODES = Currency.getAvailableCurrencies().stream()
		.map(Currency::getCurrencyCode)
		.collect(Collectors.toUnmodifiableSet());

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		// null = "field not sent". Whether it is required is @NotNull's job, not ours.
		return value == null || CODES.contains(value);
	}

}
