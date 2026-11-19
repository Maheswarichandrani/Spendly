package com.sm.spendly.common.exception;

/** The request clashes with current state, e.g. a duplicate unique value. */
public class ConflictException extends ApiException {

	public ConflictException(String detail) {
		super(ErrorCode.CONFLICT, detail);
	}

	public ConflictException(ErrorCode errorCode, String detail) {
		super(errorCode, detail);
	}

}
