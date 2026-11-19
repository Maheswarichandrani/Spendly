package com.sm.spendly;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Example integration test: boots the whole application context against a real
 * PostgreSQL container. If Flyway fails or Hibernate's schema validation fails,
 * the context does not start and this test fails.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpendlyApplicationTests {

	@Test
	void contextLoads() {
	}

}
