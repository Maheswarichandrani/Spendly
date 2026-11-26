package com.sm.spendly.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.service.UserService;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService userService;

	private final CurrentUser currentUser;

	public UserController(UserService userService, CurrentUser currentUser) {
		this.userService = userService;
		this.currentUser = currentUser;
	}

	/** No id in the URL: the id only ever comes from the verified token. */
	@GetMapping("/me")
	public UserResponse me() {
		return userService.getById(currentUser.id());
	}

}
