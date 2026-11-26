package com.sm.spendly.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of POST /api/v1/auth/register.
 *
 * <p>The compact constructor strips spaces around name and email. It runs when Jackson
 * builds the record, which is before {@code @Valid} checks it, so the rules below see
 * the real value: {@code " ravi@mail.com "} passes {@code @Email}. The password is
 * never touched: a space can be part of a password.
 */
public record RegisterRequest(
		@NotBlank(message = "Name is required.")
		@Size(max = 100, message = "Name must be at most 100 characters.")
		@Schema(example = "Ravi Kumar")
		String name,

		@NotBlank(message = "Email is required.")
		@Email(message = "Email must be a valid email address.")
		@Size(max = 254, message = "Email must be at most 254 characters.")
		@Schema(example = "ravi@mail.com", description = "Stored lowercase. Must be unique.")
		String email,

		// 72 characters here; AuthService also checks 72 *bytes* (BCrypt's real limit).
		@NotBlank(message = "Password is required.")
		@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.")
		@Schema(example = "correct-horse-42", format = "password",
				description = "8 to 72 characters and at most 72 bytes (emoji count as 4).")
		String password) {

	public RegisterRequest {
		name = name == null ? null : name.strip();
		email = email == null ? null : email.strip();
	}

	/** Records print every field in toString. Keep the password out of any log. */
	@Override
	public String toString() {
		return "RegisterRequest[name=***, email=***, password=***]";
	}

}
