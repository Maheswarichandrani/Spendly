package com.sm.spendly.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. The mobile app switches on {@link #name()},
 * so a code is never renamed or removed once shipped; add a new one instead.
 */
public enum ErrorCode {

	// Generic request problems
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
	INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid parameter"),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "Bad request"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Not acceptable"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
	PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),

	// Business outcomes
	CONFLICT(HttpStatus.CONFLICT, "Conflict"),
	BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violated"),

	// Registration
	EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
	PASSWORD_TOO_LONG(HttpStatus.BAD_REQUEST, "Password too long"),

	// Authentication
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Unauthenticated"),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),

	// Users
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),

	// Server side
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");

	private final HttpStatus status;
	private final String title;

	ErrorCode(HttpStatus status, String title) {
		this.status = status;
		this.title = title;
	}

	public HttpStatus status() {
		return status;
	}

	public String title() {
		return title;
	}

}
