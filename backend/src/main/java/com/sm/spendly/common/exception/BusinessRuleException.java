package com.sm.spendly.common.exception;

/** The input is well-formed, but a business rule forbids it (HTTP 422). */
public class BusinessRuleException extends ApiException {

	public BusinessRuleException(String detail) {
		super(ErrorCode.BUSINESS_RULE_VIOLATION, detail);
	}

	public BusinessRuleException(ErrorCode errorCode, String detail) {
		super(errorCode, detail);
	}

}
