package com.sm.spendly.auth.dto;

import java.time.Duration;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** What login and refresh return: a new access token and a new refresh token. */
public record TokenResponse(
		@Schema(description = "Signed JWT. Send as `Authorization: Bearer <accessToken>`.",
				example = "eyJraWQiOiJzcGVuZGx5LWRldi0xIiwiYWxnIjoiUlMyNTYifQ...")
		String accessToken,
		@Schema(example = "Bearer") String tokenType,
		@Schema(description = "Seconds until the access token expires.", example = "900") long expiresIn,
		@Schema(description = "Keep in secure storage. Trade it at /auth/refresh. **Single use**: each refresh "
				+ "returns a new one; using an old one again logs this device out.",
				example = "q3Jw9mH0bS2xA7eK1vZt5Lr8Np4Yc6Ud0Wf3Gi9Oj2E")
		String refreshToken,
		@Schema(description = "When the refresh token expires if not used.", example = "2026-12-26T08:15:00Z")
		Instant refreshTokenExpiresAt) {

	public static TokenResponse bearer(String accessToken, Duration accessTtl, String refreshToken,
			Instant refreshTokenExpiresAt) {
		return new TokenResponse(accessToken, "Bearer", accessTtl.toSeconds(), refreshToken, refreshTokenExpiresAt);
	}

	/** Keep both tokens out of any log line. */
	@Override
	public String toString() {
		return "TokenResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn
				+ ", refreshToken=***, refreshTokenExpiresAt=" + refreshTokenExpiresAt + "]";
	}

}
