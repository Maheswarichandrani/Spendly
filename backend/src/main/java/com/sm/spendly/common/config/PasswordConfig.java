package com.sm.spendly.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Makes a password encoder available for injection.
 *
 * <p>BCryptPasswordEncoder is a library class: we can't put {@code @Component} on it.
 * A {@code @Bean} method inside a {@code @Configuration} class is how we tell Spring
 * "call this method once at startup and keep the result as a bean".
 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

	/**
	 * Cost 12 = 2^12 rounds, about 250 ms per hash on a laptop. Slow on purpose: fine
	 * for one login, very expensive for an attacker trying millions of guesses.
	 * Typed as the PasswordEncoder interface so callers don't depend on BCrypt.
	 */
	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

}
