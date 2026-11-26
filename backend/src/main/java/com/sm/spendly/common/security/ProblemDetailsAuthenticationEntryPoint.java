package com.sm.spendly.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * What happens when a request reaches a locked URL without a valid token.
 *
 * <p>Security filters run <b>before</b> the DispatcherServlet, so our
 * {@code @RestControllerAdvice} never sees their exceptions on its own. This entry point
 * hands the exception to Spring MVC's exception resolver, which calls
 * {@code GlobalExceptionHandler}. Result: a 401 has exactly the same Problem Details shape
 * (code, requestId, instance) as every other error, built in one place.
 */
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final HandlerExceptionResolver resolver;

	public ProblemDetailsAuthenticationEntryPoint(HandlerExceptionResolver resolver) {
		this.resolver = resolver;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) {
		// handler = null: there is no controller for this request (we never got that far).
		resolver.resolveException(request, response, null, authException);
	}

}
