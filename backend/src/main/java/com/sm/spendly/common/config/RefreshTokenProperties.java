package com.sm.spendly.common.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code spendly.refresh-token.*} settings. Registered by
 * {@code @EnableConfigurationProperties} on JwtConfig.
 *
 * @param ttl how long a refresh token stays usable if nobody uses it, e.g. "30d"
 */
@Validated
@ConfigurationProperties(prefix = "spendly.refresh-token")
public record RefreshTokenProperties(@NotNull Duration ttl) {
}
