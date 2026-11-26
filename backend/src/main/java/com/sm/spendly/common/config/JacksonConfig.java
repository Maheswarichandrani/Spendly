package com.sm.spendly.common.config;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.core.StreamWriteFeature;

/**
 * Money goes over the wire as a JSON <b>string</b>: {@code "balance": "1250.50"}, not
 * {@code 1250.50}. JavaScript and many JSON libraries read every number as a 64-bit
 * float, which silently turns 0.1 + 0.2 into 0.30000000000000004 and loses digits on big
 * values. A string keeps every digit exactly; the app parses it with a decimal type.
 *
 * <p>Reading still accepts both "1250.50" and 1250.50.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

	@Bean
	JsonMapperBuilderCustomizer moneyAsString() {
		return builder -> builder
			// Every BigDecimal, everywhere, written as a string.
			.withConfigOverride(BigDecimal.class,
					override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
			// "1000" not "1E+3".
			.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
	}

}
