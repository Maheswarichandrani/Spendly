package com.sm.spendly.auth.dto;

import java.time.Duration;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What login returns.
 *
 * @param expiresIn seconds until the access token expires
 */
public record TokenResponse(
		@Schema(description = "Signed JWT. Send as `Authorization: Bearer <accessToken>`.",
				example = "eyJraWQiOiJzcGVuZGx5LWRldi0xIiwiYWxnIjoiUlMyNTYifQ...")
		String accessToken,
		@Schema(example = "Bearer") String tokenType,
		@Schema(description = "Seconds until the access token expires.", example = "900") long expiresIn) {

	public static TokenResponse bearer(String accessToken, Duration ttl) {
		return new TokenResponse(accessToken, "Bearer", ttl.toSeconds());
	}

	/** Keep the token out of any log line. */
	@Override
	public String toString() {
		return "TokenResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
	}

}
