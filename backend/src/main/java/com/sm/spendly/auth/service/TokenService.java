package com.sm.spendly.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.sm.spendly.common.config.JwtProperties;

/**
 * Builds and signs access tokens.
 *
 * <p>A JWT has three parts separated by dots: header.payload.signature. Header and
 * payload are only base64, so <b>anyone holding the token can read them</b>. The
 * signature only proves we wrote it. So the payload holds the user id and nothing
 * personal.
 */
@Service
public class TokenService {

	private static final Logger log = LoggerFactory.getLogger(TokenService.class);

	private final JwtEncoder jwtEncoder;

	private final JwtProperties properties;

	public TokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public String issueAccessToken(UUID userId) {
		Instant now = Instant.now();
		Instant expiresAt = now.plus(properties.accessTokenTtl());
		String tokenId = UUID.randomUUID().toString();

		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(properties.issuer())      // "iss": who made it
			.subject(userId.toString())       // "sub": who it is about
			.issuedAt(now)                    // "iat"
			.expiresAt(expiresAt)             // "exp": rejected after this
			.id(tokenId)                      // "jti": unique id of this token
			.build();

		String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		log.debug("Access token issued userId={} jti={} expiresAt={}", userId, tokenId, expiresAt);
		return token;
	}

	public Duration accessTokenTtl() {
		return properties.accessTokenTtl();
	}

}
