package com.sm.spendly.common.exception;

/**
 * One invalid input. Deliberately has no "rejectedValue": the value could be a password.
 * {@code field} is null for errors that apply to the whole object.
 */
public record ValidationError(String field, String message) {
}
