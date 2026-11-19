package com.sm.spendly.common.exception;

/**
 * Base class for errors we raise on purpose. The message is the client-facing
 * {@code detail}, so it must be written for the user and never contain internals.
 *
 * <p>Unchecked on purpose: it rolls back {@code @Transactional} methods by default and
 * does not force {@code throws} clauses through every layer.
 */
public abstract class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	protected ApiException(ErrorCode errorCode, String detail) {
		super(detail);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
