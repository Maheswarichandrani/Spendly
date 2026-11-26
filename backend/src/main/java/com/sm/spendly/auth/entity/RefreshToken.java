package com.sm.spendly.auth.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.sm.spendly.common.persistence.BaseEntity;

import lombok.Getter;

/**
 * One row of {@code refresh_tokens}. Holds the token's hash, never the token.
 *
 * <p>{@code userId} is a plain UUID, not a {@code @ManyToOne User}. The auth feature never
 * needs the User entity here, and a plain id keeps the user feature's entity out of auth
 * (features talk through services, see AGENTS.md section 5). The foreign key in the
 * database still guarantees the user exists.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken extends BaseEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "family_id", nullable = false, updatable = false)
	private UUID familyId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "device_label", updatable = false)
	private String deviceLabel;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	// Changed only by the bulk UPDATE queries in RefreshTokenRepository.
	@Column(name = "revoked_at")
	private Instant revokedAt;

	protected RefreshToken() {
	}

	public RefreshToken(UUID userId, UUID familyId, String tokenHash, String deviceLabel, Instant expiresAt) {
		this.userId = userId;
		this.familyId = familyId;
		this.tokenHash = tokenHash;
		this.deviceLabel = deviceLabel;
		this.expiresAt = expiresAt;
	}

	public boolean isRevoked() {
		return revokedAt != null;
	}

	public boolean isExpiredAt(Instant now) {
		return !now.isBefore(expiresAt);
	}

}
