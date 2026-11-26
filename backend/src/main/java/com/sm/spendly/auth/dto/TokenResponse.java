package com.sm.spendly.auth.dto;

import java.time.Duration;

/**
 * What login returns.
 *
 * @param expiresIn seconds until the access token expires
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

	public static TokenResponse bearer(String accessToken, Duration ttl) {
		return new TokenResponse(accessToken, "Bearer", ttl.toSeconds());
	}

	/** Keep the token out of any log line. */
	@Override
	public String toString() {
		return "TokenResponse[accessToken=***, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
	}

}
