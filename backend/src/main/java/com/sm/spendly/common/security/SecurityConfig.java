package com.sm.spendly.common.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The security filter chain: the row of guards every request passes before any controller.
 *
 * <p>Read the builder top to bottom as a list of rules.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

	/** The API docs URLs springdoc serves (JSON spec, UI page, UI assets). */
	private static final String[] DOCS_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**" };

	/**
	 * Same switch springdoc reads. Off by default (application.properties), on in dev.
	 * When off, the docs URLs are not made public, so they fall under the 401 rule below.
	 */
	private final boolean apiDocsEnabled;

	public SecurityConfig(@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
		this.apiDocsEnabled = apiDocsEnabled;
	}

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, AuthenticationEntryPoint entryPoint) throws Exception {
		if (apiDocsEnabled) {
			// Must come before anyRequest() below: rules are checked top to bottom.
			http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, DOCS_PATHS).permitAll());
		}
		http
			// No server session and no JSESSIONID cookie: every request proves itself
			// with its own token.
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// CSRF attacks trick a browser into sending its cookies to our server. We use
			// no cookies (the app puts the token in a header itself), so there is nothing
			// to steal that way. With cookies, this would have to stay on.
			.csrf(csrf -> csrf.disable())
			// No login page, no browser password popup, no /logout page: this is an API.
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			.logout(logout -> logout.disable())
			// CORS is not configured, so it stays closed. The mobile app doesn't need it.

			.authorizeHttpRequests(auth -> auth
				// Public: you can't have a token before you register or log in.
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						// The refresh token itself is the proof here, so no access token is needed.
						"/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
				.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
				// Everything else, including unknown URLs, needs a valid token. So without
				// a token, nobody can find out which URLs exist (401, never 404).
				.anyRequest().authenticated())

			// Adds BearerTokenAuthenticationFilter: reads "Authorization: Bearer <jwt>",
			// checks it with our JwtDecoder bean, and on success stores the user in the
			// SecurityContext for the rest of the request.
			.oauth2ResourceServer(resourceServer -> resourceServer
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(entryPoint))
			// Same 401 answer when there's no token at all.
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint));

		return http.build();
	}

	/**
	 * Spring MVC registers its exception resolver under this bean name. We pass it in so
	 * 401s are written by GlobalExceptionHandler, like every other error.
	 */
	@Bean
	AuthenticationEntryPoint problemDetailsEntryPoint(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
		return new ProblemDetailsAuthenticationEntryPoint(resolver);
	}

}
