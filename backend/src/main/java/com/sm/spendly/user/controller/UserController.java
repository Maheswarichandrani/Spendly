package com.sm.spendly.user.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.web.ApiProblem;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "The logged-in user's profile.")
public class UserController {

	private final UserService userService;

	private final CurrentUser currentUser;

	public UserController(UserService userService, CurrentUser currentUser) {
		this.userService = userService;
		this.currentUser = currentUser;
	}

	/** No id in the URL: the id only ever comes from the verified token. */
	@GetMapping("/me")
	@Operation(summary = "Get my profile")
	@ApiResponse(responseCode = "200", description = "The logged-in user")
	@ApiResponse(responseCode = "404", description = "`USER_NOT_FOUND`: the account was deleted after the token was issued",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public UserResponse me() {
		return userService.getById(currentUser.id());
	}

}
