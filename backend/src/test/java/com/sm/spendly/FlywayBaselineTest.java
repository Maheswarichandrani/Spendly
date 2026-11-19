package com.sm.spendly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class FlywayBaselineTest {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	Environment environment;

	@Test
	void baselineMigrationApplied() {
		List<Map<String, Object>> rows = jdbcTemplate
			.queryForList("select version, success from flyway_schema_history order by installed_rank");

		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("version")).isEqualTo("1");
		assertThat(rows.get(0).get("success")).isEqualTo(true);
	}

	@Test
	void hibernateUsesUtc() {
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
	}

}
