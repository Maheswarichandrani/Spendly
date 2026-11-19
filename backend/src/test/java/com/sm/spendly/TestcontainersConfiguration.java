package com.sm.spendly;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Starts a throwaway PostgreSQL in Docker for tests.
 *
 * @ServiceConnection tells Spring Boot to build the DataSource (and Flyway's connection)
 * from this container instead of spring.datasource.* properties. That is why tests need
 * no profile and no Neon credentials.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Same major version as Neon, so SQL behaves the same in tests and in dev.
		return new PostgreSQLContainer("postgres:18-alpine");
	}

}
