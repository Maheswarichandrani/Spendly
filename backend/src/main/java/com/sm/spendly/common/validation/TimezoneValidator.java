package com.sm.spendly.common.validation;

import java.time.ZoneId;
import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** The check behind {@link ValidTimezone}. */
public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

	/** Region ids only ("Asia/Kolkata", "UTC", ...); ZoneId.of would also accept "+05:30". */
	private static final Set<String> ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		return value == null || ZONES.contains(value);
	}

}
