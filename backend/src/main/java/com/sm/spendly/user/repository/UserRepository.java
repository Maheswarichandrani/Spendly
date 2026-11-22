package com.sm.spendly.user.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sm.spendly.user.entity.User;

/**
 * Only an interface: Spring Data creates the implementation at startup.
 * {@code JpaRepository<User, UUID>} gives save, findById, delete, ... for free.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

	/**
	 * Derived query: Spring reads the method name and generates
	 * {@code select count(*) > 0 from users where email = ?}.
	 * The caller must pass a lowercased email (the table stores only lowercase).
	 */
	boolean existsByEmail(String email);

}
