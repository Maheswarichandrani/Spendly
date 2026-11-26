package com.sm.spendly.user.dto;

import java.util.UUID;

/**
 * Internal only: what AuthService needs to check a login. Lets the user feature hand out
 * the hash without exposing the User entity. Never returned by any controller.
 *
 * @param passwordHash null for a user who has no password (future Google-only user)
 */
public record LoginCredentials(UUID id, String passwordHash) {

	@Override
	public String toString() {
		return "LoginCredentials[id=" + id + ", passwordHash=***]";
	}

}
