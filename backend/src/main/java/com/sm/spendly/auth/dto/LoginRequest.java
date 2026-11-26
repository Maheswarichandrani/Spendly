package com.sm.spendly.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of POST /api/v1/auth/login.
 *
 * <p>No {@code @Size} on the password: a min/max rule here would tell an attacker the
 * password policy. The 72-byte BCrypt limit is checked in AuthService instead, and it
 * fails with the same INVALID_CREDENTIALS answer as a wrong password.
 */
public record LoginRequest(
		@NotBlank(message = "Email is required.")
		@Email(message = "Email must be a valid email address.")
		@Size(max = 254, message = "Email must be at most 254 characters.")
		@Schema(example = "ravi@mail.com", description = "Case-insensitive; spaces around it are ignored.")
		String email,

		@NotBlank(message = "Password is required.")
		@Schema(example = "correct-horse-42", format = "password")
		String password,

		@Size(max = 100, message = "Device label must be at most 100 characters.")
		@Schema(description = "Optional name of this device, shown later in the device list.", example = "Pixel 8",
				nullable = true)
		String deviceLabel) {

	public LoginRequest {
		email = email == null ? null : email.strip();
		deviceLabel = deviceLabel == null || deviceLabel.isBlank() ? null : deviceLabel.strip();
	}

	/** Login without a device label (used by tests and older app builds). */
	public LoginRequest(String email, String password) {
		this(email, password, null);
	}

	@Override
	public String toString() {
		return "LoginRequest[email=***, password=***, deviceLabel=" + deviceLabel + "]";
	}

}
