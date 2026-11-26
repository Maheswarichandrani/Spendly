package com.sm.spendly.auth.controller;

import jakarta.validation.Valid;

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
import com.sm.spendly.user.dto.UserResponse;

/** HTTP only: validate the body, call one service method, return a DTO. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public UserResponse register(@Valid @RequestBody RegisterRequest request) {
		return authService.register(request);
	}

	/** 200 with an access token, or 401 INVALID_CREDENTIALS. Public (see SecurityConfig). */
	@PostMapping("/login")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

}
