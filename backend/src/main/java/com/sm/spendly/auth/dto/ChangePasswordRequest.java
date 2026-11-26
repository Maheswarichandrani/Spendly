package com.sm.spendly.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of POST /api/v1/auth/change-password. Same new-password rules as register. */
public record ChangePasswordRequest(
		// No @Size: like login, a length rule here would leak nothing useful but adds noise.
		@NotBlank(message = "Current password is required.")
		@Schema(format = "password", example = "correct-horse-42")
		String currentPassword,

		@NotBlank(message = "New password is required.")
		@Size(min = 8, max = 72, message = "Password must be 8 to 72 characters.")
		@Schema(format = "password", example = "battery-staple-77",
				description = "8 to 72 characters and at most 72 bytes (emoji count as 4).")
		String newPassword,

		@Size(max = 100, message = "Device label must be at most 100 characters.")
		@Schema(description = "Label for the new session on this device.", example = "Pixel 8", nullable = true)
		String deviceLabel) {

	public ChangePasswordRequest {
		deviceLabel = deviceLabel == null || deviceLabel.isBlank() ? null : deviceLabel.strip();
	}

	@Override
	public String toString() {
		return "ChangePasswordRequest[currentPassword=***, newPassword=***, deviceLabel=" + deviceLabel + "]";
	}

}
