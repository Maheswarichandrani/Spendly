package com.sm.spendly.auth.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.auth.dto.ChangePasswordRequest;
import com.sm.spendly.auth.dto.DeleteAccountRequest;
import com.sm.spendly.auth.dto.LoginRequest;
import com.sm.spendly.auth.dto.RefreshTokenRequest;
import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.auth.dto.TokenResponse;
import com.sm.spendly.auth.service.AuthService;
import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.web.ApiProblem;
import com.sm.spendly.user.dto.UserResponse;

/** HTTP only: validate the body, call one service method, return a DTO. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register, log in, refresh, log out, change password, delete account.")
public class AuthController {

	private final AuthService authService;

	private final CurrentUser currentUser;

	public AuthController(AuthService authService, CurrentUser currentUser) {
		this.authService = authService;
		this.currentUser = currentUser;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@SecurityRequirements
	@Operation(summary = "Create an account",
			description = "Email is stored lowercase and must be unique. Does not log in: call /login next.")
	@ApiResponse(responseCode = "201", description = "Account created")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` (see `errors[]`) or `PASSWORD_TOO_LONG` (over 72 bytes)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`EMAIL_ALREADY_REGISTERED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	/** 200 with an access token, or 401 INVALID_CREDENTIALS. Public (see SecurityConfig). */
	@PostMapping("/login")
	@SecurityRequirements
	@Operation(summary = "Log in",
			description = "Returns a short-lived access token. Send it as `Authorization: Bearer <accessToken>`.")
	@ApiResponse(responseCode = "200", description = "Logged in")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "401",
			description = "`INVALID_CREDENTIALS`. Same answer for an unknown email or a wrong password, on purpose.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	@SecurityRequirements
	@Operation(summary = "Get new tokens",
			description = """
					Trade the refresh token for a new access token **and a new refresh token**. Replace both in \
					storage. The old refresh token stops working.

					Run **one refresh at a time**: if two requests use the same refresh token, both fail and the \
					device is logged out (this is how a stolen token is detected).""")
	@ApiResponse(responseCode = "200", description = "New token pair")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "401",
			description = "`INVALID_REFRESH_TOKEN`: unknown, expired, revoked or reused. Clear tokens and show login.",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
		return authService.refresh(request);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@SecurityRequirements
	@Operation(summary = "Log out this device",
			description = "Revokes this refresh token's session. Needs no access token, so it works even after the "
					+ "access token expired. Always 204, even for an unknown token, so it is safe to retry.")
	@ApiResponse(responseCode = "204", description = "Logged out")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public void logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request);
	}

	@PostMapping("/logout-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Log out everywhere",
			description = "Revokes every refresh token of the logged-in user, on all devices. Access tokens already "
					+ "issued keep working until they expire (at most `expiresIn` seconds).")
	@ApiResponse(responseCode = "204", description = "All sessions ended")
	public void logoutAll() {
		authService.logoutEverywhere(currentUser.id());
	}

	@PostMapping("/change-password")
	@Operation(summary = "Change password",
			description = "Checks the current password, saves the new one, **logs out every device**, and returns a "
					+ "fresh token pair for this device. Replace both stored tokens with the ones returned.")
	@ApiResponse(responseCode = "200", description = "Password changed; new token pair for this device")
	@ApiResponse(responseCode = "400",
			description = "`VALIDATION_FAILED`, `PASSWORD_TOO_LONG`, or `CURRENT_PASSWORD_INCORRECT` (shown as 400, "
					+ "not 401, so the app does not try to refresh)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
		return authService.changePassword(currentUser.id(), request);
	}

	@PostMapping("/delete-account")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete my account",
			description = "Permanently deletes the account and all its data after checking the password. "
					+ "Cannot be undone. Clear stored tokens and go to the welcome screen.")
	@ApiResponse(responseCode = "204", description = "Account deleted")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` or `CURRENT_PASSWORD_INCORRECT`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public void deleteAccount(@Valid @RequestBody DeleteAccountRequest request) {
		authService.deleteAccount(currentUser.id(), request);
	}

}
