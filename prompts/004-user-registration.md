# 004: Users table and registration

## Goal (in short)

Create the first real database table, `users`, and the first real endpoint, `POST /api/v1/auth/register`. A person sends their name, email, and password. We store the user with the password **hashed** (scrambled one way, so it can never be read back) and return the new user, without the password.

There's no login yet. Login, JWT, and the Spring Security filter chain come in **005**. This step is about one big idea: **how Java objects get saved to the database** (JPA entity, repository, transaction).

**Real-life comparison:** opening a bank account. You fill in a form (request DTO), the clerk checks it (validation), files a record card in the cabinet (entity in the `users` table), and seals your PIN in an envelope only a machine can check (BCrypt hash). You get back a receipt without the PIN on it (response DTO).

## Skills read

- `java-springboot`: `SKILL.md` (constructor injection, services own the transaction, DTOs never entities) and `references/data.md`:
  - `@Transactional` on service methods only.
  - Keep transactions short, with no slow work inside.
  - `ddl-auto=validate`.
  - Never edit an applied migration.
- `spring-data-jpa`: `SKILL.md`:
  - `JpaRepository` and derived queries.
  - `Optional` for single results.
  - Explicit `@Table`/`@Column`.
  - Entities are never exposed in the API.
  - The first-level cache warning.
- `postgresql-table-design`: `SKILL.md`:
  - `TEXT` + `CHECK` instead of `varchar(n)`.
  - `TIMESTAMPTZ`.
  - `NOT NULL` everywhere it's needed.
  - Case-insensitive email.
  - A UUID is fine when ids must be opaque.

## Code inspected

- `main` now holds 001–003. Both PRs are merged.
- `V1__baseline.sql` creates no tables. Its comment already says "real tables start in V2 (users)".
- `FlywayBaselineTest` asserts **exactly 1** migration. It must change to expect 2.
- `pom.xml`: Boot 4.1.2. JPA, Flyway, validation, and webmvc are present. There's no Spring Security of any kind (removed in 001 on purpose) and no MapStruct.
- `ErrorCode` / `ConflictException` (002): `ConflictException(ErrorCode, detail)` already exists, so a new code just plugs in.
- `GlobalExceptionHandler` maps `DataIntegrityViolationException` to a generic 409 `CONFLICT` safety net.
- Tests use `TestcontainersConfiguration` (PostgreSQL 18) and `@WebMvcTest`.

## Decisions

1. **Two features, two packages.**
   - `user` owns the `users` table: the entity, repository, and `UserService`.
   - `auth` owns the endpoint: `AuthController` and `AuthService`.
   - `AuthService` calls `UserService` and never `UserRepository` (AGENTS rule: features talk service to service). This is the shape 005 login needs.
2. **Hash the password outside the transaction.**
   - BCrypt is slow on purpose (about 250 ms). If we hashed inside the database transaction, a DB connection would sit idle for that time.
   - So `AuthService.register` (no transaction) hashes first, then calls `UserService.createUser` (`@Transactional`), which only checks and saves.
3. **BCrypt, without full Spring Security.**
   - We add only the small `spring-security-crypto` library (Boot manages the version) and a `PasswordEncoder` bean: `new BCryptPasswordEncoder(12)`.
   - Strength 12 means 2¹² rounds, about 250 ms on a laptop: slow for an attacker guessing, still fine for one login.
   - The full security starter would lock every endpoint behind a generated password. That's 005's topic.
4. **Password rules: 8 to 72 characters**, checked with `@Size`.
   - BCrypt only reads the first **72 bytes**. Some characters (emoji, Telugu letters) are 3–4 bytes each.
   - So `AuthService` also rejects a password over 72 bytes in UTF-8. It responds with a 400 and the new code `PASSWORD_TOO_LONG`, instead of silently ignoring the end of the password.
5. **Email is stored lowercase and trimmed.**
   - `Ravi@Mail.com ` becomes `ravi@mail.com`.
   - In the database, `UNIQUE (email)` plus `CHECK (email = lower(email))` means two accounts differing only in case are impossible, even if some future code forgets to lowercase.
   - Checked with `@Email`, max 254 characters (the email standard's limit).
6. **Duplicate email gives 409 `EMAIL_ALREADY_REGISTERED`.** It's checked in two layers:
   - The service asks `existsByEmail` first, which gives a clear, fast answer.
   - If two requests race past that check at the same moment, the database unique constraint catches the second one. We call `saveAndFlush` so the insert happens **inside** our try block. We catch `DataIntegrityViolationException` and throw the same 409.
   - *Trade-off:* a 409 tells a stranger "this email has an account" (called *account enumeration*). Most apps accept this for registration. It's limited by rate limiting in **007** and can be revisited with email verification.
7. **Register takes only name, email, and password.** Preferences get defaults and are set during onboarding (a later step), not on the register form:
   - `currency` is empty (null) until onboarding.
   - `timezone` = `UTC`.
   - `week_start` = `MONDAY`.
   - `theme` = `SYSTEM`.
8. **UUID ids, made by Hibernate as UUIDv7.**
   - `@UuidGenerator(style = VERSION_7)` creates the id in Java when the row is saved.
   - v7 ids start with the time, so new rows land at the end of the index. Random v4 ids would scatter across it (the skill's "index fragmentation" warning).
   - Making them in Java (not with SQL `uuidv7()`) means we don't depend on Neon's Postgres version.
9. **`BaseEntity`** (`common/persistence`) is a `@MappedSuperclass` holding `id`, `createdAt`, and `updatedAt` for every future table.
   - Timestamps are set by Hibernate's `@CreationTimestamp` / `@UpdateTimestamp` as `Instant` (UTC).
   - `equals` compares ids; `hashCode` is per class, so it stays stable when an id is assigned at save (AGENTS section 12).
   - No Lombok `@Data`. Only `@Getter` on entities.
10. **Enums are stored as text.**
    - `@Enumerated(EnumType.STRING)` for `ThemePreference` (`SYSTEM`, `LIGHT`, `DARK`).
    - `week_start` uses Java's own `java.time.DayOfWeek`. The DB `CHECK` allows only `MONDAY`, `SATURDAY`, `SUNDAY`.
    - Never `ORDINAL`: reordering an enum would silently change saved data.
11. **Hand-written mapper for now.** `UserMapper.toResponse(User)` is one plain method. MapStruct (from AGENTS) arrives in the accounts step, when there are enough mappings to justify a code generator. Adding it here would be one more new tool in an already full step.
12. **No category seeding yet.** AGENTS says default categories are seeded at registration, but the category feature doesn't exist. The categories step will add a call inside `UserService.createUser`, in the same transaction.
13. **Git:** new branch `feature/user-registration` from the latest `main`.
14. **`password_hash` is nullable, ready for Google sign-in later.**
    - Phase 1 is email + password only, and registration **always** stores a hash. `UserService.createUser` requires a non-null hash (`Objects.requireNonNull`).
    - Later, a Google-only user will have no password, so the column allows NULL from day one. That saves a migration and stops code from assuming every user has a password.
    - A DB `CHECK` makes sure that when a hash exists, it looks like a BCrypt hash (`$2a$`, `$2b$` or `$2y$`, 60 characters). A plain-text password can never be saved by mistake.
    - 005 login rule (recorded now): if `password_hash` is NULL, login answers `INVALID_CREDENTIALS`, exactly like a wrong password.
15. **Unverified users: allowed in, nudged to verify.**
    - Registration saves `email_verified = false`. Sending the verification email comes in the email-verification step.
    - Policy, enforced in later steps: unverified users **can** log in and use the app, and the app shows a "verify your email" banner. This keeps signup fast.
    - Verification is required only where email ownership matters: changing the email address and data exports.
    - Finishing a password reset also marks the email verified, because the reset link proves the user owns the inbox.

## Files

Java paths are under `backend/src/` and `com/sm/spendly/`.

| File | Who | Change |
|---|---|---|
| `backend/pom.xml` | CLAUDE WRITES | add `org.springframework.security:spring-security-crypto` (no version) |
| `main/resources/db/migration/V2__create_users.sql` | CLAUDE WRITES | `users` table |
| `main/java/.../common/persistence/BaseEntity.java` | CLAUDE WRITES | id + timestamps + equality |
| `main/java/.../common/config/PasswordConfig.java` | CLAUDE WRITES | `PasswordEncoder` bean |
| `main/java/.../common/exception/ErrorCode.java` | CLAUDE WRITES | add `EMAIL_ALREADY_REGISTERED` (409), `PASSWORD_TOO_LONG` (400) |
| `main/java/.../common/exception/BadRequestException.java` | CLAUDE WRITES | 400 for rule-checked input (used by `PASSWORD_TOO_LONG`) |
| `main/java/.../user/entity/User.java` | CLAUDE WRITES | entity |
| `main/java/.../user/entity/ThemePreference.java` | CLAUDE WRITES | enum |
| `main/java/.../user/repository/UserRepository.java` | CLAUDE WRITES | `existsByEmail` |
| `main/java/.../user/service/UserService.java` | CLAUDE WRITES | `createUser` (`@Transactional`) |
| `main/java/.../user/dto/UserResponse.java` | CLAUDE WRITES | response record |
| `main/java/.../user/mapper/UserMapper.java` | CLAUDE WRITES | entity → DTO |
| `main/java/.../auth/controller/AuthController.java` | CLAUDE WRITES | `POST /api/v1/auth/register` |
| `main/java/.../auth/service/AuthService.java` | CLAUDE WRITES | normalize, check, hash, call `UserService` |
| `main/java/.../auth/dto/RegisterRequest.java` | CLAUDE WRITES | request record + validation |
| `test/java/.../FlywayBaselineTest.java` | CLAUDE WRITES | expect 2 migrations |
| `test/java/.../user/repository/UserRepositoryTest.java` | CLAUDE WRITES | `@DataJpaTest` + Testcontainers |
| `test/java/.../user/service/UserServiceTest.java` | CLAUDE WRITES | Mockito unit tests |
| `test/java/.../auth/service/AuthServiceTest.java` | CLAUDE WRITES | Mockito unit tests |
| `test/java/.../auth/controller/AuthControllerTest.java` | CLAUDE WRITES | `@WebMvcTest` |
| `test/java/.../auth/RegistrationIntegrationTest.java` | CLAUDE WRITES | full stack, real Postgres |
| `docs/learn/004-entities-repositories-transactions.md` | CLAUDE WRITES | learning note, simple style |

Everything is CLAUDE WRITES: every concept here is new (first entity, repository, transaction). Repeated patterns go to you from the categories step on.

## What the API looks like

### `POST /api/v1/auth/register`

Request:

```json
{
  "name": "Ravi Kumar",
  "email": "Ravi@Mail.com",
  "password": "correct-horse-42"
}
```

| Field | Rules |
|---|---|
| `name` | required, not blank, max 100 characters, trimmed |
| `email` | required, valid email, max 254 characters, trimmed and lowercased |
| `password` | required, 8–72 characters **and** max 72 bytes; never trimmed |

**201 Created:**

```json
{
  "id": "019373a2-5c1e-7d3a-9b2f-1c0e8d7a6b54",
  "name": "Ravi Kumar",
  "email": "ravi@mail.com",
  "emailVerified": false,
  "currency": null,
  "timezone": "UTC",
  "weekStart": "MONDAY",
  "theme": "SYSTEM",
  "createdAt": "2026-11-22T10:15:02.123Z"
}
```

The response never contains `password` or `passwordHash`.

**Errors** (all Problem Details from 002, with `requestId` from 003):

| Case | Status | code |
|---|---|---|
| missing or blank field, bad email, password under 8 or over 72 characters | 400 | `VALIDATION_FAILED` (with `errors[]`) |
| password over 72 bytes | 400 | `PASSWORD_TOO_LONG` |
| malformed JSON | 400 | `MALFORMED_REQUEST` |
| email already used (any letter case) | 409 | `EMAIL_ALREADY_REGISTERED` |

## Database changes

`V2__create_users.sql`:

```sql
CREATE TABLE users (
    id              UUID        PRIMARY KEY,
    email           TEXT        NOT NULL,
    password_hash   TEXT,                   -- NULL only for future Google-only users (Decision 14)
    name            TEXT        NOT NULL,
    email_verified  BOOLEAN     NOT NULL DEFAULT false,
    currency        TEXT,
    timezone        TEXT        NOT NULL DEFAULT 'UTC',
    week_start      TEXT        NOT NULL DEFAULT 'MONDAY',
    theme           TEXT        NOT NULL DEFAULT 'SYSTEM',
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT users_email_key        UNIQUE (email),
    CONSTRAINT users_email_lowercase  CHECK (email = lower(email)),
    CONSTRAINT users_email_length     CHECK (length(email) <= 254),
    CONSTRAINT users_password_bcrypt  CHECK (password_hash ~ '^\$2[aby]\$\d{2}\$.{53}$'),
    CONSTRAINT users_name_length      CHECK (length(name) BETWEEN 1 AND 100),
    CONSTRAINT users_currency_format  CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT users_week_start_valid CHECK (week_start IN ('MONDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT users_theme_valid      CHECK (theme IN ('SYSTEM', 'LIGHT', 'DARK'))
);
```

**Indexes:**
- `users_email_key` (created automatically by `UNIQUE`) serves the one query we run, "find or check a user by email" (register now, login in 005).
- No other index is needed yet. The rule "every user-owned table starts its index with `user_id`" applies to child tables, not `users` itself.

`currency` is nullable on purpose until onboarding. A `CHECK` on NULL passes, so the format rule applies only once a currency is set. `password_hash` works the same way: NULL passes, and any real value must look like a BCrypt hash.

## Security

- Passwords:
  - BCrypt cost 12.
  - The raw password lives only in the request object and the hashing call.
  - Never logged, never stored, never returned. `@Size` messages don't echo the value (002 rule).
- `passwordHash` is not in `UserResponse`. The entity is never serialized.
- All inputs are bounded (name 100, email 254, password 72 bytes). The DB `CHECK`s back up the Java rules.
- Logs: user id only. **Email is never logged** (it's personal data).
- Account enumeration through the 409 is accepted for now (Decision 6). Rate limiting arrives in 007.
- The endpoint is public. Security isn't on the classpath until 005, and register stays public after that.
- `security-review` runs at the end of the auth slice (005/006), when tokens exist.
- The DB rejects any `password_hash` that isn't BCrypt-shaped (Decision 14). That backstops a bug that would store a raw password.

### Carried to 005 (login), so it isn't forgotten

- A wrong email, a wrong password, and a user with no password all return the same `INVALID_CREDENTIALS`.
- Timing-safe login: for an unknown email, still run `passwordEncoder.matches` against a fixed dummy BCrypt hash, so "no such user" takes as long as "wrong password".
- Rate limit by IP and by email together (007).

## Spring concepts this step teaches (simple words)

1. **JPA entity:** a Java class that maps to a table (`@Entity`, `@Table`, `@Column`, `@Id`), plus `@MappedSuperclass` for shared columns, `@Enumerated(STRING)`, and why entity `equals` is tricky.
2. **Spring Data repository:** you write only an **interface**. Spring creates the class at startup (a proxy), and `existsByEmail` becomes SQL just from its **name** (a derived query).
3. **Persistence context and flush:** Hibernate keeps a notepad of loaded and saved objects and writes SQL later, at **flush**. That's why we need `saveAndFlush` to catch the unique error in time.
4. **`@Transactional`:** all-or-nothing around a service method, through a proxy. Covers rollback on `RuntimeException`, why it goes on the service, and why slow BCrypt stays outside it.
5. **`@Configuration` + `@Bean`:** making a library object (`BCryptPasswordEncoder`) a Spring bean so it can be injected.

## Tests

CLAUDE WRITES all tests (your choice, 2026-11-20).

**`UserRepositoryTest`** (`@DataJpaTest` + Testcontainers, real Postgres, each test rolled back):

| Test | Expected |
|---|---|
| `save_assignsUuidV7IdAndTimestamps` | the id is not null and is version 7; `createdAt` and `updatedAt` are set |
| `save_appliesDefaults` | `timezone=UTC`, `weekStart=MONDAY`, `theme=SYSTEM`, `emailVerified=false`, `currency=null` |
| `existsByEmail_trueOnlyForSavedEmail` | true for the saved email, false for another |
| `duplicateEmail_violatesUniqueConstraint` | the second `saveAndFlush` throws `DataIntegrityViolationException` |
| `uppercaseEmail_violatesCheckConstraint` | saving `Ravi@Mail.com` directly fails (proves the DB guard) |
| `nullPasswordHash_isAllowed` | saving a user with `passwordHash = null` succeeds (ready for Google sign-in) |
| `plainTextPasswordHash_violatesCheckConstraint` | saving `passwordHash = "correct-horse-42"` fails `users_password_bcrypt` |

**`UserServiceTest`** (Mockito, no Spring):

| Test | Expected |
|---|---|
| `createUser_savesAndReturnsResponse` | `saveAndFlush` called once; the response has id, email, name, and defaults |
| `createUser_throwsEmailAlreadyRegistered_whenExists` | `ConflictException` with code `EMAIL_ALREADY_REGISTERED`; `saveAndFlush` never called |
| `createUser_throwsEmailAlreadyRegistered_onRaceAtFlush` | `saveAndFlush` throws `DataIntegrityViolationException`; the service throws the same 409 code |
| `createUser_rejectsNullHash` | a null hash throws `NullPointerException`; `saveAndFlush` is never called (phase 1 always has a password) |

**`AuthServiceTest`** (Mockito):

| Test | Expected |
|---|---|
| `register_normalizesEmailAndName` | `UserService` receives `ravi@mail.com` and `Ravi Kumar` (trimmed) |
| `register_passesHashNotRawPassword` | `UserService` receives the encoder's output, never the raw password |
| `register_rejectsPasswordOver72Bytes` | 30 emoji (8 chars ≤ size ≤ 72, but 120 bytes) gives `BadRequestException` `PASSWORD_TOO_LONG`; the encoder is never called |

**`AuthControllerTest`** (`@WebMvcTest(AuthController.class)`, `AuthService` mocked with `@MockitoBean`):

| Test | Expected |
|---|---|
| `register_returns201WithUser` | 201; body has `id`, `email`; no `password` or `passwordHash` field |
| `register_blankFields_returns400WithFieldErrors` | 400 `VALIDATION_FAILED`; `errors` lists `email`, `name`, `password` |
| `register_shortPassword_returns400` | 400 `VALIDATION_FAILED`, field `password` |
| `register_duplicate_returns409` | the service throws the conflict, giving 409 `EMAIL_ALREADY_REGISTERED` |

**`RegistrationIntegrationTest`** (`@SpringBootTest` + MockMvc + Testcontainers, everything real):

| Test | Expected |
|---|---|
| `register_storesBcryptHash` | 201; the DB row's `password_hash` starts with `$2a$12$` and matches via `PasswordEncoder.matches` |
| `register_sameEmailDifferentCase_returns409` | `a@x.com` then `A@X.com` gives the second one 409 `EMAIL_ALREADY_REGISTERED` |

**`FlywayBaselineTest`:** now expects 2 migrations (`1`, `2`), all successful.

**Ownership test (user A vs user B):** not possible yet. There's no authenticated read endpoint until 005. Noted so it isn't forgotten.

## Logging

| What | Level | Content |
|---|---|---|
| User created | INFO | `User registered userId={id}` |
| Duplicate email attempt | INFO | `Registration rejected: email already registered` (no email) |
| Race caught at flush | WARN | `Registration race on unique email, returned 409` (no email) |
| Password too long | DEBUG | `Registration rejected: password exceeds 72 bytes` (no password) |

Never logged: the password, the hash, the email, the name.

## Transactions

- `UserService.createUser`: `@Transactional`, read-write. It covers `existsByEmail` and `saveAndFlush`.
  - A `ConflictException` (unchecked) thrown inside rolls the transaction back.
  - `GlobalExceptionHandler` turns it into a 409 **after** the transaction has ended.
- `AuthService.register`: **no** transaction, on purpose (Decision 2). It runs normalize → length check → BCrypt → `userService.createUser(...)`. The call to `createUser` goes through the Spring proxy because it's a call to **another bean**, so the transaction applies.
- **Trap in this slice:** if `createUser` were called from another method inside `UserService` itself (self-invocation), the proxy would be skipped and there would be no transaction. The learning note shows this.
- No `readOnly` methods yet (there are no reads).
- After a flush failure, the Hibernate session must not be used again. We throw straight away, so this is safe.

## Acceptance criteria

- [ ] `V2__create_users.sql` applies on Testcontainers and on Neon dev. `ddl-auto=validate` passes.
- [ ] `POST /api/v1/auth/register` returns 201 with the response shape above, and no password fields.
- [ ] The stored password is a BCrypt cost-12 hash.
- [ ] `password_hash` is nullable, and the DB rejects any non-BCrypt value.
- [ ] Duplicate email in any letter case returns 409 `EMAIL_ALREADY_REGISTERED`.
- [ ] Validation errors return 400 with field errors. A password over 72 bytes returns `PASSWORD_TOO_LONG`.
- [ ] Logs show the user id and never the email or password.
- [ ] `./mvnw -q verify` passes, including all earlier tests.
- [ ] The learning note is written in the simple style.

## Checks to run

From `backend/`:
1. `./mvnw -q verify`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then the manual steps below.

## Manual test steps

1. Start the app with the dev profile. The log shows Flyway `Migrating schema "public" to version "2 - create users"`.
2. Register:
   ```
   curl -i -X POST localhost:8080/api/v1/auth/register -H "Content-Type: application/json" -d "{\"name\":\"Ravi Kumar\",\"email\":\"Ravi@Mail.com\",\"password\":\"correct-horse-42\"}"
   ```
   You get 201. The email comes back as `ravi@mail.com`, and there's no password in the body. The log shows `User registered userId=...` with the request id.
3. Run the same command again with `RAVI@mail.com`. You get 409 `EMAIL_ALREADY_REGISTERED`.
4. Send `{"name":"","email":"nope","password":"short"}`. You get 400 `VALIDATION_FAILED` with 3 field errors.
5. In the Neon SQL editor, run `select email, password_hash, timezone from users;`. You see one row; the hash starts with `$2a$12$`.
6. Commit to `feature/user-registration` and push. I give you the PR link.

## Changes made during implementation

- **Spaces are stripped in `RegisterRequest`'s compact constructor**, not in `AuthService`. `@Email` rejects `" ravi@mail.com "`, and validation runs before the service, so trimming in the service would have been too late. `AuthService` still lowercases. New test: `AuthControllerTest.register_stripsSpacesBeforeValidation`.
- **`logServerErrorDetail=false` on the JDBC driver** (`application.properties`). The first test run showed Hibernate logging Postgres's error `Detail:` line at WARN, including the failing row's email and password hash. With this setting the detail is gone from logs, and constraint names still come through.
- **`ConstraintNames` helper** (`common/persistence`). It's shared by `UserService` (to check that a failure is `users_email_key`) and `GlobalExceptionHandler` (moved out of its private method).
- **`UserService` re-throws other constraint violations** instead of turning every `DataIntegrityViolationException` into `EMAIL_ALREADY_REGISTERED`.
- **`RegisterRequest.toString` is masked**, so a stray log of the request can never print the password.
- **Extra tests:** `UserServiceTest.createUser_rethrowsOtherConstraintViolations`, `AuthServiceTest.register_accepts72ByteAsciiPassword`, `RegistrationIntegrationTest.register_emojiPasswordOver72Bytes_returns400PasswordTooLong`.
