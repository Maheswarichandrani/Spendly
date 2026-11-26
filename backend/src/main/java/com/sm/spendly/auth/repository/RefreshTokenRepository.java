package com.sm.spendly.auth.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sm.spendly.auth.entity.RefreshToken;

/**
 * The three "revoke" methods are bulk UPDATEs written in JPQL (queries on entities, not
 * tables). Two things to know about them:
 * <ul>
 * <li>{@code @Modifying} tells Spring Data it's an UPDATE, not a SELECT. It returns the
 * number of rows changed.</li>
 * <li>They go straight to the database and skip the persistence context, so
 * {@code @UpdateTimestamp} doesn't run. That's why each one sets {@code updatedAt} itself.
 * Any RefreshToken object already loaded in this transaction keeps its old values.</li>
 * </ul>
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	/**
	 * Revokes one token, but only if nobody revoked it first. This is the "claim": the
	 * database runs the WHERE check and the SET as one atomic step, so when two refresh
	 * requests race with the same token, exactly one gets 1 back and the other gets 0.
	 */
	@Modifying
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now
			where t.id = :id and t.revokedAt is null""")
	int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

	@Modifying
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now
			where t.familyId = :familyId and t.revokedAt is null""")
	int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

	@Modifying
	@Query("""
			update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now
			where t.userId = :userId and t.revokedAt is null""")
	int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

}
