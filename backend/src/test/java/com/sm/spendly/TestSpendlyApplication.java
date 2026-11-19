package com.sm.spendly;

import org.springframework.boot.SpringApplication;

/**
 * Runs the real app against a Testcontainers PostgreSQL, no Neon needed:
 * ./mvnw spring-boot:test-run
 */
public class TestSpendlyApplication {

	public static void main(String[] args) {
		SpringApplication.from(SpendlyApplication::main)
				.with(TestcontainersConfiguration.class)
				.run(args);
	}

}
