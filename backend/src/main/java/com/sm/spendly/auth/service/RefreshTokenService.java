package com.sm.spendly.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sm.spendly.auth.entity.RefreshToken;
import com.sm.spendly.auth.repository.RefreshTokenRepository;
import com.sm.spendly.common.config.RefreshTokenProperties;

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * <p>A refresh token is a long random string (not a JWT). The app keeps it in secure
 * storage and trades it for a new access token when the old one expires. Each trade also
 * returns a <b>new</b> refresh token and kills the old one ("rotation"). So a stolen token
 * works at most once, and when the real app later uses its copy, we notice the reuse and
 * log that device out.
 *
 * <p><b>Why rotate() returns a result instead of throwing:</b> on reuse we must revoke the
 * whole family <i>and keep that change</i>. If this method threw an exception, the
 * {@code @Transactional} proxy would roll back, the revoke would be undone, and the thief
 * would stay logged in. So the method commits normally and AuthService throws afterwards,
 * outside the transaction.
 */
@Service
public class RefreshTokenService {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	/** 32 random bytes = 256 bits. Nobody can guess it, so a fast hash is enough. */
	private static final int TOKEN_BYTES = 32;

	// One SecureRandom for the app is fine: it's thread-safe and seeds itself from the OS.
	private static final SecureRandom RANDOM = new SecureRandom();

	private final RefreshTokenRepository repository;

	private final RefreshTokenProperties properties;

	public RefreshTokenService(RefreshTokenRepository repository, RefreshTokenProperties properties) {
		this.repository = repository;
		this.properties = properties;
	}

	/** A raw token to send to the app once, plus when it expires. Never stored or logged. */
	public record IssuedRefreshToken(String rawToken, Instant expiresAt) {

		@Override
		public String toString() {
			return "IssuedRefreshToken[rawToken=***, expiresAt=" + expiresAt + "]";
		}

	}

	/** What happened on a refresh. Exactly one of these comes back. */
	public sealed interface RotationResult {

		record Rotated(UUID userId, IssuedRefreshToken newToken) implements RotationResult {
		}

		record Rejected(Reason reason) implements RotationResult {
		}

		enum Reason { UNKNOWN, EXPIRED, REUSED }

	}

	/** Login: starts a new family (a new "device session"). */
	@Transactional
	public IssuedRefreshToken issueForLogin(UUID userId, String deviceLabel) {
		UUID familyId = UUID.randomUUID();
		IssuedRefreshToken issued = issue(userId, familyId, deviceLabel);
		log.info("Refresh token family started userId={} familyId={}", userId, familyId);
		return issued;
	}

	/**
	 * Trades a refresh token for a new one in the same family.
	 * One transaction: the old token is claimed and the new one inserted together, or neither.
	 */
	@Transactional
	public RotationResult rotate(String rawToken) {
		Instant now = Instant.now();
		Optional<RefreshToken> found = repository.findByTokenHash(hash(rawToken));
		if (found.isEmpty()) {
			log.info("Refresh rejected reason=unknown");
			return new RotationResult.Rejected(RotationResult.Reason.UNKNOWN);
		}
		RefreshToken current = found.get();

		// Checked before expiry: an old, revoked token being replayed is reuse, even if
		// it has also expired since.
		if (current.isRevoked() || repository.revokeIfActive(current.getId(), now) == 0) {
			// revokeIfActive == 0 means another request claimed this token a moment ago.
			// Either the app retried a request in parallel, or two parties hold the token.
			// We can't tell which, so we take the safe side and end that device's session.
			int revoked = repository.revokeFamily(current.getFamilyId(), now);
			log.warn("Refresh token reuse detected userId={} familyId={} revokedTokens={}",
					current.getUserId(), current.getFamilyId(), revoked);
			return new RotationResult.Rejected(RotationResult.Reason.REUSED);
		}

		if (current.isExpiredAt(now)) {
			// revokeIfActive above already marked it revoked, which is harmless: it was dead anyway.
			log.info("Refresh rejected reason=expired userId={}", current.getUserId());
			return new RotationResult.Rejected(RotationResult.Reason.EXPIRED);
		}

		IssuedRefreshToken next = issue(current.getUserId(), current.getFamilyId(), current.getDeviceLabel());
		log.info("Refresh token rotated userId={} familyId={}", current.getUserId(), current.getFamilyId());
		return new RotationResult.Rotated(current.getUserId(), next);
	}

	/**
	 * Logout on one device: kills the whole family of this token. Unknown or already-revoked
	 * tokens are ignored, so logout can safely be retried.
	 */
	@Transactional
	public void revokeFamilyOf(String rawToken) {
		repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
			int revoked = repository.revokeFamily(token.getFamilyId(), Instant.now());
			log.info("Logout userId={} familyId={} revokedTokens={}", token.getUserId(), token.getFamilyId(), revoked);
		});
	}

	/** Logout everywhere. Access tokens already issued stay valid until they expire (max 15 min). */
	@Transactional
	public int revokeAllForUser(UUID userId) {
		int revoked = repository.revokeAllForUser(userId, Instant.now());
		log.info("Logout everywhere userId={} revokedTokens={}", userId, revoked);
		return revoked;
	}

	private IssuedRefreshToken issue(UUID userId, UUID familyId, String deviceLabel) {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		// URL-safe base64 without '=' padding: 43 characters, safe in JSON and headers.
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		Instant expiresAt = Instant.now().plus(properties.ttl());
		repository.save(new RefreshToken(userId, familyId, hash(raw), deviceLabel, expiresAt));
		return new IssuedRefreshToken(raw, expiresAt);
	}

	/**
	 * SHA-256, not BCrypt. BCrypt is slow on purpose, to protect guessable passwords. A
	 * 256-bit random token can't be guessed, so slowness buys nothing. BCrypt's random salt
	 * would also make the hash different every time, so we couldn't look the row up by it.
	 */
	static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			// Every Java runtime must ship SHA-256.
			throw new IllegalStateException(ex);
		}
	}

}
