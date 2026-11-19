# 001 — How Spring Boot boots

Prompt: [`prompts/001-backend-foundation.md`](../../prompts/001-backend-foundation.md)

This step wrote almost no Java code, yet when you run the app it opens a connection pool to Postgres, migrates the schema, starts Hibernate, and serves `/actuator/health`. This note explains where all of that comes from.

---

## 1. The concept

A Spring application is a set of objects (**beans**) that Spring creates, wires together, and manages. These objects live in the **ApplicationContext**. `SpringApplication.run(...)` builds that context.

Plain Spring would make you declare every bean yourself: the `DataSource`, the connection pool, Flyway, the `EntityManagerFactory`, the transaction manager, Tomcat. **Spring Boot** adds three things on top:

1. **Starters.** These are dependency bundles. `spring-boot-starter-data-jpa` pulls in Hibernate, Spring Data JPA, HikariCP, and Spring's transaction support.
2. **Auto-configuration.** These are ready-made `@Configuration` classes that create those beans for you, but only when certain conditions hold.
3. **Externalized configuration.** You tune those beans with properties (`spring.datasource.url=...`) instead of code.

The problem this solves: you get a production-grade setup with zero boilerplate, and you can still override any piece.

## 2. How Spring does it under the hood

### Startup order

```
SpringApplication.run(SpendlyApplication.class)
 ├─ 1. Build the Environment: collect every property source (files, env vars, .env import)
 ├─ 2. Pick active profiles (e.g. "dev") → also load application-dev.properties
 ├─ 3. Create ApplicationContext
 ├─ 4. @SpringBootApplication = @Configuration + @ComponentScan + @EnableAutoConfiguration
 │     ├─ ComponentScan: your classes under com.sm.spendly (none yet besides the app class)
 │     └─ AutoConfiguration: hundreds of candidate config classes, each guarded by conditions
 ├─ 5. Evaluate conditions, register surviving bean definitions
 ├─ 6. Instantiate beans in dependency order:
 │     DataSource (Hikari) → Flyway (runs migrations!) → EntityManagerFactory (Hibernate validate)
 └─ 7. Start embedded Tomcat on :8080 → "Started SpendlyApplication"
```

### Conditions decide what exists

Each auto-configuration class is annotated with conditions, for example:

- `@ConditionalOnClass(DataSource.class)`: only if that class is on the classpath, meaning you added the starter.
- `@ConditionalOnMissingBean(DataSource.class)`: only if *you* didn't define your own. This is how you override Boot: declare the bean yourself and Boot steps back.
- `@ConditionalOnProperty(...)`: only if a property is set.

That is why removing `spring-boot-starter-security` from `pom.xml` in this step changed behaviour. Its auto-configuration (which locks every endpoint behind a generated password) simply no longer matches, because its classes are gone.

In Boot 4, the auto-configurations are split per technology. Flyway's lives in the `spring-boot-flyway` module (package `org.springframework.boot.flyway.autoconfigure`), which is why the starter is called `spring-boot-starter-flyway`. In Boot 3, it all lived in one big `spring-boot-autoconfigure` jar.

To see the decisions yourself, run with `--debug`. Boot prints a **condition evaluation report** listing what matched and what didn't, and why.

### Why Flyway runs before Hibernate

Flyway's auto-configuration registers a dependency so the `EntityManagerFactory` bean is created only **after** the `Flyway` bean has migrated. The sequence is:

1. Flyway brings the schema up to date (V1, V2, and so on).
2. Hibernate starts with `ddl-auto=validate` and checks that every `@Entity` matches a real table and column.
3. On a mismatch, startup fails. A failed startup is better than a runtime surprise.

### Where properties come from (highest priority wins)

1. Command-line args (`--server.port=9090`)
2. OS environment variables (`DB_URL`, `SPRING_DATASOURCE_URL`)
3. Imported files (`spring.config.import=optional:file:.env[.properties]`)
4. `application-dev.properties` (active profile)
5. `application.properties`
6. Defaults in the auto-configuration classes

`${DB_URL}` in a properties file is a **placeholder**. Spring looks up a property named `DB_URL` from the sources above. **Relaxed binding** means the env var `SPRING_DATASOURCE_URL` also sets `spring.datasource.url` directly.

`.env[.properties]` tells Spring to read `.env` *as* a properties file. `optional:` means the app doesn't crash when the file is missing (CI, production with real env vars).

### Profiles

`application.properties` is always loaded. With the `dev` profile active, `application-dev.properties` is loaded **on top** and wins on conflicts. That's how health shows details in dev (`show-details=always`) but not by default (`never`).

### `@ConfigurationProperties` (explained now, used from 004)

Instead of sprinkling `@Value("${x}")`, you bind a group of properties to a typed record:

```java
@ConfigurationProperties(prefix = "spendly.jwt")
@Validated
public record JwtProperties(@NotNull Duration accessTokenTtl, @NotBlank String issuer) {}
```

Spring converts `"15m"` into a `Duration` and validates at startup, so a bad config fails at boot, not at the first request. Boot's own auto-config uses exactly this mechanism. `spring.datasource.*` is bound to `DataSourceProperties`.

### Testcontainers `@ServiceConnection`

In tests, `TestcontainersConfiguration` declares a `PostgreSQLContainer` bean marked `@ServiceConnection`. Boot then:

1. starts the container (Docker),
2. creates a `JdbcConnectionDetails` bean from the container's host, port, user, and password,
3. builds the `DataSource` **and Flyway's connection** from that bean, which beats any `spring.datasource.*` property.

That's why tests need no profile and no Neon credentials. It is also why Neon's separate Flyway URL lives only in `application-dev.properties`: if `spring.flyway.url` were in the base file, tests would try to migrate Neon.

## 3. The request walk-through

There is no feature endpoint yet, so trace `GET /actuator/health` in the dev profile:

1. **Tomcat** (embedded, started by `spring-boot-starter-webmvc`) accepts the TCP connection on :8080.
2. **`DispatcherServlet`**, Spring MVC's single front controller, gets the request.
3. Its handler mapping finds Actuator's **`HealthEndpoint`**. It is exposed because `management.endpoints.web.exposure.include=health,info`.
4. `HealthEndpoint` asks every **`HealthIndicator`** bean:
   - **`DataSourceHealthIndicator`** borrows a connection from **HikariCP** and runs a validation query against Neon's pooled host.
   - **`DiskSpaceHealthIndicator`** checks free disk.
5. It aggregates the results: everything UP gives `{"status":"UP"}`, and any DOWN gives HTTP 503.
6. **Jackson 3** (`tools.jackson`) serializes the response to JSON.

No security filter runs yet. That arrives in 004.

## 4. Why this design

| Choice | Rejected alternative | Why |
|---|---|---|
| Flyway + `ddl-auto=validate` | `ddl-auto=update` | `update` guesses DDL, never drops or renames, and differs between environments. Migrations are reviewable and repeatable. |
| Testcontainers Postgres | H2 in-memory | H2 doesn't speak real Postgres (types, `TIMESTAMPTZ`, functions). Tests would pass while Neon fails. |
| Neon wiring only in the `dev` profile | Everything in `application.properties` | Tests and future prod supply connections differently, so the base file stays environment-neutral. |
| Pooled URL for the app, direct URL for Flyway | One URL | PgBouncer (transaction mode) can't hold prepared statements or session-level locks that Flyway uses. |
| Removed Security / Spring AI until needed | Keep all Initializr deps | Each one auto-configures behaviour (locked endpoints, mandatory API key) before we understand it. |
| `open-in-view=false` | Boot's default `true` | With `true`, a DB connection stays open for the whole HTTP request and lazy loads happen in the view, which hides N+1 queries. |

## 5. Common mistakes

- **Editing `V1__baseline.sql` after it ran.** Flyway stores a checksum, so the next startup fails with `Migration checksum mismatch`. Always add `V2__...` instead.
- **Using the `-pooler` host for Flyway**, which causes lock or prepared-statement errors. **Forgetting `prepareThreshold=0`** on the pooled URL causes random `prepared statement "S_1" already exists`.
- **Mistaking a Neon cold start for an outage.** The first connection after idle can take seconds, which is why `connection-timeout=30000`.
- **Putting `@EnableJpaAuditing` (or similar) on `SpendlyApplication`**, which breaks slice tests like `@DataJpaTest`. Use a dedicated `@Configuration` (comes in 003).
- **Assuming every property takes `30s`-style durations.** That works only when the bound field is a `Duration` (like our own `@ConfigurationProperties` records). `spring.datasource.hikari.*` binds straight onto HikariCP's setters, which take a raw `long` in milliseconds, so `connection-timeout=30s` fails with `failed to convert java.lang.String to long`. This happened in this step; the fix was `30000`.
- **Committing `.env`.** Only `.env.example` is committed.
- **Docker not running**, so Testcontainers tests fail with `Could not find a valid Docker environment`.

## 6. Try it

1. **Watch auto-config decide:**
   - Run `./mvnw spring-boot:test-run -Dspring-boot.run.arguments=--debug`.
   - In the condition report, find `DataSourceAutoConfiguration` under *Positive matches* and any `Security...` class under *Negative matches*. Why is security negative?
2. **Break validation on purpose:**
   - Add `V2__oops.sql` containing `CREATE TABLE demo(id int);` and run the tests. They pass, because no entity cares.
   - Now edit `V1__baseline.sql` (add a comment) and run again. Read the checksum error.
   - Undo both, and delete the `target/` folder if needed.
3. **Property precedence:**
   - In PowerShell, run `$env:SERVER_PORT=9090; ./mvnw spring-boot:test-run`. Which port does Tomcat use, and which rule from section 2 explains it?
   - Run `Remove-Item Env:SERVER_PORT` afterwards.
4. **Write the tests from the prompt** (`FlywayBaselineTest`). Querying `flyway_schema_history` with `JdbcTemplate` shows you exactly what Flyway recorded.
