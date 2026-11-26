package com.sm.spendly.common.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sm.spendly.common.config.JwtProperties;
import com.sm.spendly.common.config.RefreshTokenProperties;

/**
 * The two JWT tools:
 * <ul>
 * <li>{@link JwtEncoder} builds and <b>signs</b> tokens with the private key (used at login).</li>
 * <li>{@link JwtDecoder} reads a token and <b>checks</b> it with the public key (used by the
 * security filter on every request).</li>
 * </ul>
 *
 * <p>{@code @EnableConfigurationProperties} turns {@link JwtProperties} into a bean, built
 * from the {@code spendly.jwt.*} properties and validated at startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ JwtProperties.class, RefreshTokenProperties.class })
public class JwtConfig {

	@Bean
	public JwtKeys jwtKeys(JwtProperties properties) {
		return JwtKeys.fromBase64PrivateKey(properties.privateKey());
	}

	@Bean
	public JwtEncoder jwtEncoder(JwtKeys keys, JwtProperties properties) {
		// A JWK ("JSON Web Key") is a key plus its metadata. The key id lets the encoder
		// pick this key when a token header asks for kid = spendly-dev-1.
		RSAKey jwk = new RSAKey.Builder(keys.publicKey())
			.privateKey(keys.privateKey())
			.keyID(properties.keyId())
			.build();
		return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
	}

	@Bean
	public JwtDecoder jwtDecoder(JwtKeys keys, JwtProperties properties) {
		// Pinned to RS256: a token claiming "alg": "none" or an HMAC algorithm is rejected
		// before any other check, which blocks the classic JWT algorithm-confusion attacks.
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(keys.publicKey())
			.signatureAlgorithm(SignatureAlgorithm.RS256)
			.build();
		// Default checks (expiry and not-before, with 60 s clock skew) plus "iss must be ours".
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
		return decoder;
	}

}
