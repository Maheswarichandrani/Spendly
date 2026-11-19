package com.sm.spendly.common.exception;

/**
 * The resource does not exist <em>for the current user</em>. Also used when the id
 * belongs to someone else, so ids cannot be probed (404, never 403).
 */
public class NotFoundException extends ApiException {

	public NotFoundException(String detail) {
		super(ErrorCode.NOT_FOUND, detail);
	}

	public NotFoundException(ErrorCode errorCode, String detail) {
		super(errorCode, detail);
	}

}
