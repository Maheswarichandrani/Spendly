package com.sm.spendly.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The value must be an IANA timezone name such as "Asia/Kolkata" or "UTC". Fixed offsets
 * like "+05:30" are rejected: they ignore daylight saving, so "this month" would be wrong
 * for users in places that have it. null is allowed.
 */
@Documented
@Constraint(validatedBy = TimezoneValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimezone {

	String message() default "Timezone must be an IANA name, like Asia/Kolkata or Europe/London.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
