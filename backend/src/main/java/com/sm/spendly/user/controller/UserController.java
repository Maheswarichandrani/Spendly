package com.sm.spendly.user.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.web.ApiProblem;
import com.sm.spendly.user.dto.UpdateProfileRequest;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.service.UserService;

/** Every endpoint works on "me": the id only ever comes from the verified token. */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users", description = "The logged-in user's profile, preferences and onboarding.")
public class UserController {

	private final UserService userService;

	private final CurrentUser currentUser;

	public UserController(UserService userService, CurrentUser currentUser) {
		this.userService = userService;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(summary = "Get my profile")
	@ApiResponse(responseCode = "200", description = "The logged-in user")
	@ApiResponse(responseCode = "404", description = "`USER_NOT_FOUND`: the account was deleted after the token was issued",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public UserResponse me() {
		return userService.getById(currentUser.id());
	}

	@PatchMapping
	@Operation(summary = "Update my profile and preferences",
			description = "Send only the fields to change. Used by onboarding (currency, timezone, week start) and "
					+ "by settings. Changing currency changes formatting only; amounts are never converted.")
	@ApiResponse(responseCode = "200", description = "The updated profile")
	@ApiResponse(responseCode = "400",
			description = "`VALIDATION_FAILED` (bad currency, timezone, name) or `MALFORMED_REQUEST` (unknown weekStart/theme value)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public UserResponse update(@Valid @RequestBody UpdateProfileRequest request) {
		return userService.updateProfile(currentUser.id(), request);
	}

	@PostMapping("/onboarding/complete")
	@Operation(summary = "Finish onboarding",
			description = "Call after the onboarding screens. Needs a currency to be set first. Safe to call twice.")
	@ApiResponse(responseCode = "200", description = "Profile with onboardingCompleted = true")
	@ApiResponse(responseCode = "422", description = "`CURRENCY_REQUIRED`: PATCH a currency first",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public UserResponse completeOnboarding() {
		return userService.completeOnboarding(currentUser.id());
	}

}
