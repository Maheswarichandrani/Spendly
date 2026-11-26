package com.sm.spendly.common.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The id of the user making the current request.
 *
 * <p>This is the <b>only</b> place a user id may come from. Never take it from a path
 * variable, a query parameter or the request body: the client could put anyone's id there.
 *
 * <p>How it works: after the JWT filter accepted the token, it stored a
 * {@link JwtAuthenticationToken} in the SecurityContext, a per-request holder (like the
 * MDC from step 003). The token's "sub" claim is the user id we put there at login.
 */
@Component
public class CurrentUser {

	public UUID id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
			return UUID.fromString(jwtAuthentication.getToken().getSubject());
		}
		// Only reachable if a public URL calls this by mistake: a programming bug.
		throw new IllegalStateException("No authenticated user in the security context");
	}

}
