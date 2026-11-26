package com.sm.spendly.auth.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /api/v1/auth/delete-account. The password confirms it is really the owner. */
public record DeleteAccountRequest(
		@NotBlank(message = "Password is required.")
		@Schema(format = "password", example = "correct-horse-42")
		String password) {

	@Override
	public String toString() {
		return "DeleteAccountRequest[password=***]";
	}

}
