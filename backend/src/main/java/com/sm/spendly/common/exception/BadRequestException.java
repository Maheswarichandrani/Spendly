package com.sm.spendly.common.exception;

/**
 * The input passed annotation validation but breaks a rule only code can check
 * (for example a password longer than 72 bytes). HTTP 400.
 */
public class BadRequestException extends ApiException {

	public BadRequestException(String detail) {
		super(ErrorCode.BAD_REQUEST, detail);
	}

	public BadRequestException(ErrorCode errorCode, String detail) {
		super(errorCode, detail);
	}

}
