package com.sm.spendly.common.exception;

/**
 * The caller could not prove who they are (HTTP 401), for example a wrong password
 * at login. The detail must never say *which* part was wrong.
 */
public class UnauthorizedException extends ApiException {

	public UnauthorizedException(ErrorCode errorCode, String detail) {
		super(errorCode, detail);
	}

}
