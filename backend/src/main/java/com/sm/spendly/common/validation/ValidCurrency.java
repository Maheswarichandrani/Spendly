package com.sm.spendly.common.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * Our own validation annotation, used like {@code @NotBlank}: the value must be a real
 * ISO 4217 currency code such as "INR" or "USD". null is allowed (it means "not sent");
 * add {@code @NotNull} where the value is required.
 *
 * <p>The three members (message, groups, payload) are required by Bean Validation for
 * every constraint annotation. {@code @Constraint} names the class that does the check.
 */
@Documented
@Constraint(validatedBy = CurrencyCodeValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {

	String message() default "Currency must be a valid 3-letter ISO 4217 code, like INR or USD.";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

}
