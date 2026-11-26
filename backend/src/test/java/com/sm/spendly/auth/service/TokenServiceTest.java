package com.sm.spendly.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.sm.spendly.common.config.JwtProperties;
import com.sm.spendly.common.security.JwtConfig;
import com.sm.spendly.common.security.JwtKeys;
import com.sm.spendly.common.security.TestKeys;

/**
 * Real encoder and decoder, no Spring and no mocks: we issue a token and read it back
 * exactly the way the security filter will.
 */
class TokenServiceTest {

	private TokenService tokenService;

	private JwtDecoder decoder;

	@BeforeEach
	void setUp() {
		JwtProperties properties = TestKeys.newProperties();
		JwtConfig config = new JwtConfig();
		JwtKeys keys = config.jwtKeys(properties);
		tokenService = new TokenService(config.jwtEncoder(keys, properties), properties);
		decoder = config.jwtDecoder(keys, properties);
	}

	@Test
	void issuedToken_hasExpectedClaims() {
		UUID userId = UUID.randomUUID();

		Jwt jwt = decoder.decode(tokenService.issueAccessToken(userId));

		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("spendly");
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
		assertThat(jwt.getId()).isNotBlank();
		assertThat(jwt.getHeaders()).containsEntry("kid", "test-key").containsEntry("alg", "RS256");
	}

	@Test
	void issuedToken_containsNoEmail() {
		Jwt jwt = decoder.decode(tokenService.issueAccessToken(UUID.randomUUID()));

		// Anyone holding a token can read its claims, so only these may be inside.
		assertThat(jwt.getClaims()).containsOnlyKeys("sub", "iss", "iat", "exp", "jti");
	}

	@Test
	void eachTokenHasItsOwnId() {
		UUID userId = UUID.randomUUID();

		String first = decoder.decode(tokenService.issueAccessToken(userId)).getId();
		String second = decoder.decode(tokenService.issueAccessToken(userId)).getId();

		assertThat(first).isNotEqualTo(second);
	}

}
