package com.sm.spendly.common.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/** Finds which database constraint a write broke, without parsing SQL error text. */
public final class ConstraintNames {

	private ConstraintNames() {
	}

	/**
	 * Walks the cause chain to Hibernate's ConstraintViolationException, which carries
	 * the constraint name Postgres reported (for example {@code users_email_key}).
	 *
	 * @return the constraint name, or {@code "unknown"}
	 */
	public static String of(DataIntegrityViolationException ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
				return cve.getConstraintName();
			}
			cause = cause.getCause();
		}
		return "unknown";
	}

}
