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

import com.sm.spendly.auth.dto.LoginRequest;
import com.sm.spendly.auth.dto.RegisterRequest;
import com.sm.spendly.auth.dto.TokenResponse;
import com.sm.spendly.auth.service.AuthService;
import com.sm.spendly.common.web.ApiProblem;
import com.sm.spendly.user.dto.UserResponse;

/** HTTP only: validate the body, call one service method, return a DTO. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register and log in. These endpoints need no token.")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
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

}
