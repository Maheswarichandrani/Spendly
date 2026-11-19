# 001 — Backend foundation

## Goal

Turn the Initializr skeleton into a Spendly backend that starts against Neon, runs Flyway, reports `/actuator/health` UP, and has a Testcontainers test base. This step has no feature code. It teaches **how a Spring Boot app boots and gets its configuration**.

## Skills read

- `java-springboot`: `SKILL.md`, `references/configuration.md` (record `@ConfigurationProperties`, profiles, `spring.config.import` for `.env`, Testcontainers at dev time), and `references/data.md` (Flyway on Boot 4, `ddl-auto=validate`).
- `spring-data-jpa`: auditing and `BaseEntity`. Deferred to 003 (see Decisions).

## Code inspected

- `backend/pom.xml`: Boot **4.1.2**, Java 17, Spring AI 2.0.1 BOM. Starters: webmvc, data-jpa, flyway (+ `flyway-database-postgresql`), security, oauth2-resource-server, validation, actuator, spring-ai-openai, devtools, lombok. Also **h2 + h2console**, which AGENTS.md bans.
- `backend/src/main/java/com/sm/expense_tracker/ExpenseTrackerApplication.java`: plain `@SpringBootApplication`.
- `backend/src/main/resources/application.properties`: only `spring.application.name=expense-tracker`.
- `backend/src/test/.../ExpenseTrackerApplicationTests.java`: empty `contextLoads`.
- Tooling: Java 17.0.12 and Docker 29.6.1 are installed.

## Decisions and assumptions

1. **Name.** The app is **Spendly** (working name). Package `com.sm.spendly`, class `SpendlyApplication`, artifactId `spendly`. The name appears only in the package, `pom.xml`, and `spring.application.name`, so a later rename stays cheap. AGENTS.md is updated from Expenso to Spendly.
2. **Add dependencies only when first used.** Each dependency arrives in the slice that teaches it:
   - Remove `h2` and `spring-boot-h2console`: banned.
   - Remove `spring-boot-starter-security`, `spring-boot-starter-security-oauth2-resource-server`, and their `-test` starters. With Security on the classpath, every endpoint (including health) sits behind a generated password, and that is a concept for 004. They come back in 004.
   - Remove `spring-ai-starter-model-openai` and the `spring-ai-bom`. They fail startup without a key and are not needed until the insights slice.
   - Keep webmvc, data-jpa, flyway, postgresql, validation, actuator, devtools, and lombok.
   - Add `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`, and `org.testcontainers:testcontainers-postgresql` (test scope, versions from the Boot BOM). Exact artifact names are confirmed against the Boot 4.1 docs during implementation.
3. **Profiles.**
   - `application.properties` holds settings that are safe everywhere and contain no secrets: JPA `validate`, open-in-view off, UTC, actuator exposure, and error hiding.
   - `application-dev.properties` holds the Neon wiring. It reads env vars, which come from `backend/.env` through `spring.config.import=optional:file:.env[.properties]`.
   - Tests use no profile. Testcontainers `@ServiceConnection` supplies the datasource.
   - Keeping Neon's separate Flyway URL in the dev profile is deliberate. If `spring.flyway.url` were in the base file, Flyway would ignore the Testcontainers database.
4. **Neon URLs.**
   - The app uses the pooled host (`-pooler`) with `?sslmode=require&prepareThreshold=0`, because PgBouncer in transaction mode breaks server-side prepared statements.
   - Flyway uses the direct host through `spring.flyway.url`, `spring.flyway.user`, and `spring.flyway.password`.
5. **Neon cold start.** Hikari `connection-timeout=30000` (ms; Hikari takes a raw long, not a Duration) and `maximum-pool-size=5`. Neon's free tier has a small connection budget, and one instance does not need more.
6. **First migration.** `V1__baseline.sql` contains only comments and creates no tables. It proves the pipeline and creates `flyway_schema_history`. The real tables start in 003 (`users`). Creating an empty "placeholder" table would be noise.
7. **`BaseEntity` and JPA auditing move to 003.** They can only be tested with a real entity, and 003 is the first entity.
8. **`@ConfigurationProperties`.** No app-specific config exists yet, so the concept is explained in the learning note but no properties record is written. The first real one comes with JWT settings in 004, and it is not created early.

## Files

| File | Who | Change |
|---|---|---|
| `backend/pom.xml` | CLAUDE WRITES | artifactId/name `spendly`; remove h2, h2console, security, oauth2, Spring AI, and their test starters; add Testcontainers |
| `backend/src/main/java/com/sm/spendly/SpendlyApplication.java` | CLAUDE WRITES | moved and renamed from `com.sm.expense_tracker` |
| old `com/sm/expense_tracker/` dirs (main + test) | CLAUDE WRITES | deleted after the move |
| `backend/src/main/resources/application.properties` | CLAUDE WRITES | base settings |
| `backend/src/main/resources/application-dev.properties` | CLAUDE WRITES | Neon datasource, Flyway direct URL, Hikari |
| `backend/src/main/resources/db/migration/V1__baseline.sql` | CLAUDE WRITES | comment-only baseline |
| `backend/.env.example` | CLAUDE WRITES | canonical env var list with format notes |
| `backend/src/test/java/com/sm/spendly/TestcontainersConfiguration.java` | CLAUDE WRITES | `@TestConfiguration` with a `@ServiceConnection` `PostgreSQLContainer` (`postgres:18-alpine`, matching Neon's PG 18) |
| `backend/src/test/java/com/sm/spendly/SpendlyApplicationTests.java` | CLAUDE WRITES | example test: context loads against a real Postgres |
| `backend/src/test/java/com/sm/spendly/FlywayBaselineTest.java` | **USER WRITES** | see Tests |
| `backend/src/test/java/com/sm/spendly/TestSpendlyApplication.java` | CLAUDE WRITES | runs the app locally against Testcontainers without Neon (`./mvnw spring-boot:test-run`) |
| `AGENTS.md` | CLAUDE WRITES | Expenso → Spendly, `com.sm.expenso` → `com.sm.spendly` |
| `docs/learn/001-how-spring-boot-boots.md` | CLAUDE WRITES | learning note |

## Requirements

### `application.properties`

```properties
spring.application.name=spendly
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=never
server.error.include-stacktrace=never
server.error.include-message=never
```

### `application-dev.properties`

```properties
spring.config.import=optional:file:.env[.properties]
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.maximum-pool-size=5
spring.flyway.url=${DB_MIGRATION_URL}
spring.flyway.user=${DB_USERNAME}
spring.flyway.password=${DB_PASSWORD}
management.endpoint.health.show-details=always
```

### `.env.example`

```properties
# Neon pooled host (-pooler), converted to JDBC form
DB_URL=jdbc:postgresql://<pooler-host>/<db>?sslmode=require&prepareThreshold=0
# Neon direct host (no -pooler), used by Flyway only
DB_MIGRATION_URL=jdbc:postgresql://<direct-host>/<db>?sslmode=require
DB_USERNAME=
DB_PASSWORD=
```

Neon shows `postgresql://user:pass@host/db?...`. Move the user and password into their own variables and prefix the URL with `jdbc:`.

## API contract

None. The only endpoint is `GET /actuator/health`:
- Base profile: `200 {"status":"UP"}`.
- Dev profile: also shows details, including `db` and `diskSpace`.

## Database changes

`V1__baseline.sql` is comment-only. After it runs, `flyway_schema_history` has one row, version `1`, `success = true`.

## Security considerations

- `.env` is gitignored (already in root `.gitignore`). Only `.env.example` is committed.
- Health shows details only in the dev profile, so production never exposes DB details.
- Stack traces and messages are hidden in error responses.
- Spring Security is **not** active in this step, and that is acceptable because there are no data endpoints yet. 004 adds it before any user data exists.

## Spring concepts this step teaches

1. **Starters and auto-configuration.** How `spring-boot-starter-data-jpa` on the classpath leads to a `DataSource`, a Hikari pool, an `EntityManagerFactory`, and Flyway running before JPA. Covers `@Conditional*` and the Boot 4 modular auto-config packages.
2. **Externalized configuration.** Property source order (env vars beat files), `${VAR}` placeholders, relaxed binding, and `spring.config.import`.
3. **Profiles.** Base versus `-dev` overlay, and why tests use no profile.
4. **Flyway lifecycle.** Migrations run at startup before Hibernate `validate`. Never edit an applied migration.
5. **Testcontainers `@ServiceConnection`.** How a container replaces the datasource properties in tests.

## Tests

| Test | Who | Expected |
|---|---|---|
| `SpendlyApplicationTests.contextLoads` | Claude (example) | Context starts against a Testcontainers Postgres and Flyway runs |
| `FlywayBaselineTest.baselineMigrationApplied` | **User** | Inject `JdbcTemplate`, query `flyway_schema_history`: exactly one row, `version = '1'`, `success = true` |
| `FlywayBaselineTest.hibernateUsesUtc` | **User** | Inject `Environment`: `spring.jpa.properties.hibernate.jdbc.time_zone` equals `UTC` |

Hints for the user's tests:
- Copy the annotations from `SpendlyApplicationTests`: `@SpringBootTest` and `@Import(TestcontainersConfiguration.class)`.
- Use `@Autowired` on the test fields. Tests are the one place field injection is fine.
- Use `queryForList` or `queryForObject`, and AssertJ `assertThat`.

There is no ownership test (no user data yet).

## Logging

- Leave Boot defaults: INFO for root.
- At startup, look for the active profile, the Hikari pool start, and the Flyway line `Successfully applied 1 migration`.
- Never log the DB password. Boot does not print it, and nothing custom is added.

## Transactions

None in this step. Flyway runs each migration in its own transaction, which Postgres supports for DDL. `@Transactional` arrives in 003.

## Acceptance criteria

- [ ] No `com.sm.expense_tracker` remains. The app is `com.sm.spendly.SpendlyApplication`.
- [ ] `pom.xml` has no h2, security, or Spring AI dependencies. Testcontainers is present.
- [ ] `./mvnw -q verify` passes, including the user's `FlywayBaselineTest`.
- [ ] Dev profile against Neon: Flyway applies V1 through the direct URL, and `/actuator/health` returns UP with the `db` component UP.
- [ ] `./mvnw spring-boot:test-run` starts the app against a throwaway container.
- [ ] The learning note exists and covers all five concepts.

## Checks

From `backend/`:
1. `./mvnw -q verify`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. Needs your `backend/.env`.
3. `curl -s localhost:8080/actuator/health`

## Manual test steps

1. Copy `backend/.env.example` to `backend/.env` and fill in the Neon pooled URL, direct URL, username, and password.
2. Start Docker Desktop.
3. From `backend/`, run `./mvnw -q verify`. Expect BUILD SUCCESS.
4. Run `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`. In the log, expect `The following 1 profile is active: "dev"` and `Successfully applied 1 migration`.
5. Run `curl -s localhost:8080/actuator/health`. Expect `"status":"UP"` with `db` UP.
6. In Neon's SQL editor, run `select version, success from flyway_schema_history;`. Expect `1 | t`.
7. Stop the app and restart it. Expect `Schema "public" is up to date. No migration necessary.`
