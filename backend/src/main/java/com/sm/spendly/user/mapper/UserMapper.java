package com.sm.spendly.user.mapper;

import com.sm.spendly.user.dto.UserResponse;
import com.sm.spendly.user.entity.User;

/**
 * Entity to DTO in one place. Hand-written for now; MapStruct arrives in the categories
 * step, when there are enough mappings to justify a code generator.
 */
public final class UserMapper {

	private UserMapper() {
	}

	public static UserResponse toResponse(User user) {
		return new UserResponse(
				user.getId(),
				user.getName(),
				user.getEmail(),
				user.isEmailVerified(),
				user.getCurrency(),
				user.getTimezone(),
				user.getWeekStart(),
				user.getTheme(),
				user.isOnboardingCompleted(),
				user.getCreatedAt());
	}

}
