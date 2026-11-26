package com.sm.spendly.common.config;

import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * All {@code spendly.jwt.*} settings in one typed object.
 *
 * <p>Spring Boot reads the properties (from application*.properties and environment
 * variables) and builds this record at startup. {@code @Validated} checks the rules
 * right away: if the private key is missing, the app refuses to start with a message
 * naming {@code spendly.jwt.private-key}, instead of failing on the first login.
 *
 * <p>Registered by {@code @ConfigurationPropertiesScan} on SpendlyApplication.
 *
 * @param issuer         value of the "iss" claim; tokens with another issuer are rejected
 * @param accessTokenTtl how long an access token lives, e.g. "15m"
 * @param keyId          the "kid" header, so a future key rotation can tell keys apart
 * @param privateKey     RSA private key, PKCS#8, base64 on one line. Never logged.
 */
@Validated
@ConfigurationProperties(prefix = "spendly.jwt")
public record JwtProperties(
		@NotBlank String issuer,
		@NotNull Duration accessTokenTtl,
		@NotBlank String keyId,
		@NotBlank String privateKey) {

	/** Records print every field in toString. The key must never reach a log. */
	@Override
	public String toString() {
		return "JwtProperties[issuer=" + issuer + ", accessTokenTtl=" + accessTokenTtl + ", keyId=" + keyId
				+ ", privateKey=***]";
	}

}
