# 001 — How a Spring Boot app starts

Prompt: [`prompts/001-backend-foundation.md`](../../prompts/001-backend-foundation.md)

> **In short:** we wrote almost no Java, yet the app connects to the database, creates tables, and answers `/actuator/health`. Spring Boot did that for us by looking at **which libraries we added** and **which settings we wrote**.

---

## 1. The idea (what and why)

### What is a "bean"?

A **bean** is just a Java object that Spring creates and looks after for you.

Examples of beans in our app:
- the **database connection pool** (an object that keeps a few database connections open and ready),
- **Flyway** (the tool that creates and updates our tables),
- **Tomcat** (the web server that listens on port 8080).

All the beans live together in one place called the **application context**. Think of it as a big box of ready-made objects.

### What does Spring Boot add?

Plain Spring makes you write code to create every one of those beans yourself, which is a lot of boring setup.

Spring Boot does it for you with three tricks:

| Trick | Plain meaning | Example from our project |
|---|---|---|
| **Starters** | A "pack" of libraries you add with one line in `pom.xml` | `spring-boot-starter-data-jpa` brings Hibernate, the connection pool, and transaction support |
| **Auto-configuration** | Ready-made setup code that creates beans **only if** they make sense | "Postgres driver is present and a DB URL is set → create a connection pool" |
| **Properties** | Settings in a text file instead of code | `spring.datasource.url=...` tells the pool where the database is |

**Real-life comparison:** think of a furnished flat.
- Plain Spring is an empty flat: you buy and assemble every piece of furniture yourself.
- Spring Boot is a furnished flat: the furniture is already there, and the settings file is your list of changes ("move the bed to this room").
- If you bring your own bed (your own bean), Spring Boot removes its bed.

---

## 2. How it works inside

### Step by step: what happens when you run the app

When you run `SpendlyApplication.main()`:

1. **Spring reads all settings.** It collects them from the settings files, environment variables, and our `.env` file.
2. **Spring checks the active profile.** If it is `dev`, it also reads `application-dev.properties`. (A *profile* is a named set of extra settings. More on this below.)
3. **Spring scans our code.** It looks at classes under `com.sm.spendly`.
4. **Spring checks every auto-configuration.** Each one asks questions like "Is library X on the classpath?" and "Did the developer already make this bean?" Only the ones whose answers fit get used.
5. **Spring creates the beans in the right order:**
   - first the **connection pool** (HikariCP),
   - then **Flyway**, which runs our migrations (`V1__baseline.sql`),
   - then **Hibernate**, which checks that our Java entities match the tables.
6. **Tomcat starts on port 8080.** You see `Started SpendlyApplication`.

### How auto-configuration decides ("conditions")

Each auto-configuration has small yes/no checks on it, called **conditions**. The three most common:

| Condition | Plain meaning |
|---|---|
| `@ConditionalOnClass(X)` | "Only if library X was added to `pom.xml`" |
| `@ConditionalOnMissingBean(X)` | "Only if the developer did not make their own X" |
| `@ConditionalOnProperty(...)` | "Only if a certain setting is turned on" |

**Example from this step:** we removed `spring-boot-starter-security` from `pom.xml`.
- The security auto-configuration says "only if the security library is present".
- The library is gone, so the check fails and security doesn't switch on.
- That's why `/actuator/health` is open now. With security added, it would ask for a password.

### Why Flyway runs before Hibernate

- **Flyway** creates and updates the tables using our SQL files.
- **Hibernate** then checks: "Does every Java entity match a real table and column?" That's what `ddl-auto=validate` means.
- If Hibernate ran first, the tables wouldn't exist yet and the check would fail. So Spring makes Hibernate wait for Flyway.
- If something doesn't match, the app **refuses to start**. That's good: you find the problem at startup, not when a user hits it.

### Where settings come from (who wins)

The same setting can be written in several places. Spring uses this order, and **higher wins**:

1. Command line, e.g. `--server.port=9090`
2. Environment variables, e.g. `DB_URL`
3. Our `.env` file (imported by `spring.config.import`)
4. `application-dev.properties` (only when the `dev` profile is on)
5. `application.properties` (always read)
6. Spring Boot's built-in defaults

**Let's read our dev file line by line:**

```properties
spring.config.import=optional:file:.env[.properties]
```
- "Also read the file `.env` and treat it like a properties file."
- `optional:` means "don't crash if the file is missing".

```properties
spring.datasource.url=${DB_URL}
```
- `${DB_URL}` is a **placeholder**: "put the value of `DB_URL` here".
- Spring finds `DB_URL` in the `.env` file.
- So the real password never sits in a file we commit to git.

```properties
spring.datasource.hikari.connection-timeout=30000
```
- "Wait up to 30,000 ms (30 seconds) to get a database connection."
- Neon puts the database to sleep when nobody uses it, and waking up takes a few seconds.

```properties
spring.flyway.url=${DB_MIGRATION_URL}
```
- Flyway uses a **different** Neon address, the "direct" one. The reason is explained below.

### Profiles in simple words

- `application.properties` is **always** used. It holds settings that are safe everywhere.
- `application-dev.properties` is used **only** when you start with the `dev` profile:
  `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- If both files set the same thing, the **dev file wins**.

Example: health details are `never` in the base file and `always` in the dev file. So on your laptop you see details, and in production you won't.

### Neon's two addresses: pooled and direct

Neon gives you two addresses for the same database:

| Address | Has `-pooler` in host? | Who uses it | Why |
|---|---|---|---|
| Pooled | yes | our app | A middleman (PgBouncer) shares a few real connections among many users, which is cheap and fast |
| Direct | no | Flyway only | Flyway needs a real, private connection to safely change tables |

- `prepareThreshold=0` on the pooled address turns off a Postgres driver feature (prepared statements) that the middleman can't handle.
- Without it you get random errors like `prepared statement "S_1" already exists`.

### Tests with Testcontainers

**Problem:** tests shouldn't touch our real Neon database.

**Solution:** Testcontainers starts a **temporary Postgres inside Docker** just for the tests, then deletes it.

Our code:

```java
@Bean
@ServiceConnection
PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer("postgres:18-alpine");
}
```

- `@Bean`: "Spring, please create and manage this object."
- `PostgreSQLContainer("postgres:18-alpine")`: "start Postgres version 18 in Docker" (the same version Neon runs).
- `@ServiceConnection`: "Spring, connect the app to **this** database instead of the one in the settings."

That's why tests need no `.env` and no Neon password.

---

## 3. Following one request: `GET /actuator/health`

You type `curl localhost:8080/actuator/health`. Here's the journey:

1. **Tomcat** (the web server) receives the request on port 8080.
2. It hands the request to **DispatcherServlet**. That's Spring's front desk: every request goes through it, and it decides who should answer.
3. The front desk sees `/actuator/health` and sends it to Actuator's **HealthEndpoint**. This is allowed because of `management.endpoints.web.exposure.include=health,info`.
4. HealthEndpoint asks each **health checker**:
   - **Database checker:** borrows a connection from the pool and asks Neon "are you alive?"
   - **Disk checker:** "is there enough free disk space?"
5. If all say UP, the answer is `{"status":"UP"}` with HTTP 200. If any say DOWN, it's HTTP 503.
6. **Jackson** (the JSON library) turns the result into JSON text and sends it back.

---

## 4. Why we chose this (and not something else)

- **Flyway instead of `ddl-auto=update`.**
  - `update` lets Hibernate guess table changes automatically.
  - It can't rename or delete columns, and it can do different things on different machines.
  - Flyway uses SQL files we write and review, so every machine gets exactly the same tables.
- **A real Postgres in Docker instead of H2.**
  - H2 is a small in-memory database that only *pretends* to be Postgres.
  - Tests could pass on H2 and still fail on Neon.
- **Neon settings only in the dev file.**
  - Tests and production connect differently, so the base file stays neutral.
- **Removed Security and Spring AI for now.**
  - Both switch on automatically and change behaviour: security locks every URL, and Spring AI demands an API key.
  - We add each one back in the step that explains it.
- **`open-in-view=false`.**
  - The default (`true`) keeps a database connection open during the whole web request.
  - That hides slow "one query per row" problems. With `false`, we must load data on purpose.

---

## 5. Mistakes to avoid

1. **Editing `V1__baseline.sql` after it has run.**
   - Flyway remembers a fingerprint (checksum) of every file it ran.
   - Change the file and the app won't start: `Migration checksum mismatch`.
   - Always add a **new** file (`V2__...`) instead.
2. **Using the pooled (`-pooler`) address for Flyway.** It causes locking errors. Use the direct one.
3. **Forgetting `prepareThreshold=0`** on the pooled address. It causes random "prepared statement already exists" errors.
4. **Thinking the database is down when it's just waking up.** The first connection after idle can take 5–10 seconds on Neon.
5. **Writing `30s` for Hikari timeouts.**
   - Hikari wants a plain number of milliseconds: `30000`.
   - `30s` fails with `failed to convert java.lang.String to long`.
   - This really happened in this step.
6. **Committing `.env`.** It holds passwords. Only `.env.example` (with empty values) goes to git.
7. **Docker not running.** The tests fail with `Could not find a valid Docker environment`.

---

## 6. Try it yourself

1. **See Spring's decisions.**
   - Run `./mvnw spring-boot:test-run -Dspring-boot.run.arguments=--debug`.
   - In the log, search for `DataSourceAutoConfiguration`. It's in the "Positive matches" list (it was used).
   - Search for `Security`. You'll find nothing, or only "Negative matches". Why? Because we removed the library.
2. **Break Flyway on purpose.**
   - Add a comment line to `V1__baseline.sql` and run the tests. Read the checksum error.
   - Undo the change.
3. **See who wins between settings.**
   - In PowerShell, run `$env:SERVER_PORT=9090; ./mvnw spring-boot:test-run`.
   - The app starts on 9090, not 8080. Why? Environment variables (rank 2) beat built-in defaults (rank 6).
   - Afterwards run `Remove-Item Env:SERVER_PORT`.

---

## Remember these

- **Bean** = an object Spring creates and manages.
- **Starter** = a pack of libraries. **Auto-configuration** = ready-made setup that only switches on when it fits.
- Settings: **environment variables beat files**, and the **dev file beats the base file**.
- **Flyway creates tables first**, then Hibernate checks them.
- **Never edit a migration that already ran.** Add a new one.
- **Testcontainers** = a throwaway real Postgres for tests.
