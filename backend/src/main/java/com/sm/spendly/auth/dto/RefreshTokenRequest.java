package com.sm.spendly.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /auth/refresh and POST /auth/logout. */
public record RefreshTokenRequest(
		@NotBlank(message = "Refresh token is required.")
		// Our tokens are 43 characters. The cap stops someone from making us hash megabytes.
		@Size(max = 200, message = "Refresh token is too long.")
		@Schema(description = "The refreshToken from the last login or refresh.",
				example = "q3Jw9mH0bS2xA7eK1vZt5Lr8Np4Yc6Ud0Wf3Gi9Oj2E")
		String refreshToken) {

	@Override
	public String toString() {
		return "RefreshTokenRequest[refreshToken=***]";
	}

}
