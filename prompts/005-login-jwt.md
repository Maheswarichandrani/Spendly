# 005: Login, access token (JWT), and the security filter chain

## Goal (in short)

A registered user can log in with email and password and gets back an **access token**: a JWT valid for 15 minutes. Every endpoint except register, login, and health is locked: a request without a valid token gets `401`. The first protected endpoint, `GET /api/v1/users/me`, returns the logged-in user.

No refresh tokens yet. When the access token expires the user logs in again. Refresh tokens, rotation, and logout come in **006**. Rate limiting comes in **007**.

**Real-life comparison:** an office building.
- **Login** is the reception desk. You show your ID card (email and password), and reception gives you a **visitor badge** (the JWT) that expires at 5 pm.
- The **security filter chain** is the row of guards at the corridor entrance. Every guard looks at your badge. No badge, or an expired one? You're stopped at the door (401) and never reach any office (controller).
- The badge is **signed** with the company stamp (RSA private key). Guards check the stamp with a copy of the stamp's pattern (public key). Nobody can forge a badge without the real stamp.

## Skills read

- `java-springboot`: `SKILL.md` (constructor injection, `@ConfigurationProperties` with validation, ProblemDetail errors, `readOnly` transactions for reads).
- `spring-data-jpa`: `SKILL.md` (derived query returning `Optional`, read-only transactions, never return entities).
- `security-review`: will run at the end of this step, on the finished diff (tokens and an endpoint that touches user data).

## Code inspected

- `main` holds 001–004 (PR #3 merged).
- `pom.xml`: Boot 4.1.2, which manages **Spring Security 7.1.2**. Only `spring-security-crypto` is present (004).
- Checked on Maven Central for 4.1.2:
  - `spring-boot-starter-security-oauth2-resource-server` is the Boot 4 name.
  - The old `spring-boot-starter-oauth2-resource-server` still exists but its description says *deprecated*.
  - The new starter brings `spring-boot-starter-security`, `spring-security-oauth2-resource-server` and `spring-security-oauth2-jose` (Nimbus: signs and checks JWTs).
  - Test starter: `spring-boot-starter-security-oauth2-resource-server-test`, which brings `spring-security-test`.
- `RequestIdFilter` is `@Order(HIGHEST_PRECEDENCE)`. Spring Security's filter runs at order `-100`, so the request id is set **before** security. A 401 still has a request id in its logs.
- `GlobalExceptionHandler` only sees exceptions thrown **inside** the DispatcherServlet. Security rejects requests in a filter, **before** the DispatcherServlet, so 401 bodies need their own writer (Decision 7).
- `GlobalExceptionHandlerTest` and `AuthControllerTest` are `@WebMvcTest`s. Once security is on the classpath, `@WebMvcTest` locks every endpoint, so these tests must be adjusted (see Tests).
- `UserRepository` has only `existsByEmail`. `UserService` has only `createUser`.
- `.env.example` has DB, Groq and S3 variables. No JWT variables yet.

## Decisions

1. **We issue our own JWT and check it with Spring's resource server** (AGENTS section 7).
   - Issuing: Spring Security's `NimbusJwtEncoder`.
   - Checking: Spring's built-in `BearerTokenAuthenticationFilter` with a `NimbusJwtDecoder`. We don't write our own JWT filter, because a hand-written one is where most JWT security bugs come from.
2. **RS256 with one RSA key pair, from one environment variable.**
   - `JWT_PRIVATE_KEY`: the RSA private key (PKCS#8), base64 on **one line** (no `-----BEGIN` lines), so it fits in `.env` and in any hosting dashboard.
   - The **public key is derived from the private key** at startup. That means one secret instead of two, and they can never get out of sync.
   - `JWT_KEY_ID` goes into the token header as `kid`. When we rotate keys one day, the server can tell old tokens from new ones.
   - The prompt includes the exact `openssl` commands to generate the key (Manual test steps).
3. **Token contents (claims):**

   | Claim | Value | Why |
   |---|---|---|
   | `iss` | `spendly` | the decoder rejects tokens from anyone else |
   | `sub` | user id (UUID) | who this is; never the email (personal data) |
   | `iat` | now | issued at |
   | `exp` | now + 15 min | short life limits the damage of a stolen token |
   | `jti` | random UUID | unique token id, useful for audit later |

   The header has `alg: RS256`, `kid`. No email, name or role in the token: a JWT is readable by anyone who has it (it's signed, not encrypted).
4. **Settings live in `@ConfigurationProperties`** (`JwtProperties`, prefix `spendly.jwt`): `issuer`, `accessTokenTtl` (a `Duration`, default 15m), `keyId`, `privateKey`. It's validated at startup (`@Validated`, `@NotBlank`), so a missing key **stops the app from starting** with a clear message instead of failing on the first login.
   - Bound from env in `application-dev.properties`, like the DB settings.
   - Tests get a **test-only** key from `src/test/resources/config/application.properties`. Spring Boot loads `classpath:/config/application.properties` **in addition to** the main file, so tests get extra settings without replacing the real ones. The test key is clearly labelled and is never valid anywhere else.
5. **Login rules** (from the 004 "Carried to 005" list):
   - The email is stripped and lowercased the same way as at registration.
   - **One answer for every failure:** an unknown email, a wrong password, and a user with no password (future Google-only user) all give `401 INVALID_CREDENTIALS` with the same text.
   - **Timing-safe:** for an unknown email (or a null hash) we still run `passwordEncoder.matches(password, DUMMY_HASH)`. `DUMMY_HASH` is a real BCrypt cost-12 hash computed once at startup. Both paths take about 250 ms, so response time can't reveal whether an email is registered.
   - **Over-72-byte passwords:** BCrypt can't check them, so they get `INVALID_CREDENTIALS` too, after the dummy check, so the timing stays the same.
   - **Unverified users can log in** (004 Decision 15).
6. **`UserService.findLoginCredentials(email)`** returns `Optional<LoginCredentials>`, a small internal record holding `id` and `passwordHash`. `AuthService` never sees the `User` entity (AGENTS layer rule). `LoginCredentials` is not an API DTO and is never serialized.
7. **401 bodies are Problem Details too.**
   - `ProblemDetailsAuthenticationEntryPoint` (`common/security`) writes the same JSON shape as `GlobalExceptionHandler`: `code: UNAUTHENTICATED`, `requestId`, `instance`.
   - It also writes the `WWW-Authenticate: Bearer` header (the HTTP standard for 401).
   - The error text is always the same: "Authentication is required." We don't say whether the token was missing, expired, or forged, because the app handles all three the same way (send the user to login) and an attacker learns nothing.
   - To avoid copying the ProblemDetail-building code, the shared part of `GlobalExceptionHandler.problem(...)` moves to a small public `ProblemDetails` factory in `common/exception`, used by both.
8. **Security filter chain settings** (`SecurityConfig`, `common/security`):
   - **Stateless:** `SessionCreationPolicy.STATELESS`. No server session and no `JSESSIONID` cookie; the token is the only proof.
   - **CSRF off.** CSRF attacks need a browser that sends **cookies** automatically. We use no cookies: the app sends the token in a header itself, which a malicious website can't do. Explained in the note.
   - **No form login, no HTTP Basic**, so Spring doesn't add a login page or the generated "user" password.
   - **CORS stays closed** (we don't call `.cors()`). The mobile app doesn't need it.
   - **Public:** `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /actuator/health`. **Everything else requires authentication**, including unknown URLs. Without a token an unknown URL gives 401, not 404, so nobody can map our routes.
9. **The current user.** `CurrentUser` (`common/security`, a `@Component`) has one method, `UUID id()`. It reads the authenticated JWT from the `SecurityContext` and returns `UUID.fromString(jwt.getSubject())`. Controllers call it and pass the id to services. This is the **only** place the user id comes from (AGENTS section 5: never from a request parameter).
10. **`GET /api/v1/users/me`**: `UserController` → `UserService.getById(id)` (`@Transactional(readOnly = true)`) → `UserResponse`. If the user no longer exists but the token is still valid (deleted account), the answer is `404 USER_NOT_FOUND`.
11. **Response of login:** `{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": 900 }`. `expiresIn` is in seconds (OAuth2 convention), so the app can schedule a re-login. In 006 a `refreshToken` field is **added** to this same shape, so this change won't break the app.
12. **Git:** branch `feature/login-jwt` from the latest `main`.
13. **Learning note 004 gets a "Remember these" recap** at the end (the agreed note style; it was missing). Small doc fix on this branch.

## Files

Java paths are under `backend/src/` and `com/sm/spendly/`.

| File | Who | Change |
|---|---|---|
| `backend/pom.xml` | CLAUDE WRITES | add `spring-boot-starter-security-oauth2-resource-server`, test `spring-boot-starter-security-oauth2-resource-server-test`; remove `spring-security-crypto` (now included) |
| `backend/.env.example` | CLAUDE WRITES | `JWT_PRIVATE_KEY`, `JWT_KEY_ID` with how to generate |
| `main/resources/application.properties` | CLAUDE WRITES | `spendly.jwt.issuer`, `spendly.jwt.access-token-ttl` |
| `main/resources/application-dev.properties` | CLAUDE WRITES | bind `spendly.jwt.private-key`, `key-id` from env |
| `main/java/.../common/config/JwtProperties.java` | CLAUDE WRITES | `@ConfigurationProperties` record + validation |
| `main/java/.../common/security/JwtKeys.java` | CLAUDE WRITES | parse private key, derive public key |
| `main/java/.../common/security/JwtConfig.java` | CLAUDE WRITES | `JwtEncoder` + `JwtDecoder` beans (RS256, issuer check) |
| `main/java/.../common/security/SecurityConfig.java` | CLAUDE WRITES | the `SecurityFilterChain` bean |
| `main/java/.../common/security/ProblemDetailsAuthenticationEntryPoint.java` | CLAUDE WRITES | 401 as Problem Details |
| `main/java/.../common/security/CurrentUser.java` | CLAUDE WRITES | user id from the SecurityContext |
| `main/java/.../common/exception/ProblemDetails.java` | CLAUDE WRITES | shared ProblemDetail factory |
| `main/java/.../common/exception/GlobalExceptionHandler.java` | CLAUDE WRITES | use `ProblemDetails` |
| `main/java/.../common/exception/ErrorCode.java` | CLAUDE WRITES | `INVALID_CREDENTIALS` (401), `UNAUTHENTICATED` (401), `USER_NOT_FOUND` (404) |
| `main/java/.../common/config/PasswordConfig.java` | CLAUDE WRITES | unchanged unless needed |
| `main/java/.../auth/service/TokenService.java` | CLAUDE WRITES | builds and signs the access token |
| `main/java/.../auth/service/AuthService.java` | CLAUDE WRITES | add `login` (timing-safe) |
| `main/java/.../auth/controller/AuthController.java` | CLAUDE WRITES | add `POST /login` |
| `main/java/.../auth/dto/LoginRequest.java` | **USER WRITES** | request record (same pattern as `RegisterRequest`) |
| `main/java/.../auth/dto/TokenResponse.java` | **USER WRITES** | response record |
| `main/java/.../user/dto/LoginCredentials.java` | CLAUDE WRITES | internal record: id + hash |
| `main/java/.../user/repository/UserRepository.java` | **USER WRITES** | add `findByEmail` |
| `main/java/.../user/service/UserService.java` | **USER WRITES** | add `findLoginCredentials`, `getById` |
| `main/java/.../user/controller/UserController.java` | **USER WRITES** | `GET /api/v1/users/me` |
| `test/resources/config/application.properties` | CLAUDE WRITES | test-only JWT key |
| all tests below | CLAUDE WRITES | |
| `docs/learn/005-security-filter-chain-and-jwt.md` | CLAUDE WRITES | learning note |
| `docs/learn/004-entities-repositories-transactions.md` | CLAUDE WRITES | add "Remember these" |

### Specs for the USER WRITES files

You write these by hand; I review your diff afterwards. Each reuses a pattern from 004.

**`LoginRequest`** (copy the shape of `RegisterRequest`):
- Fields: `email`, `password`.
- `email`: `@NotBlank`, `@Email`, `@Size(max = 254)`, each with a friendly `message`.
- `password`: `@NotBlank` only. *Hint:* no `@Size` here. A min/max rule on login would tell an attacker about the password policy, and the 72-byte check happens in `AuthService`.
- Compact constructor: strip `email` (null-safe). Never touch `password`.
- Override `toString()` to hide both values.

**`TokenResponse`**:
- `String accessToken`, `String tokenType`, `long expiresIn`.
- *Hint:* a static factory `TokenResponse.bearer(String token, Duration ttl)` that fills `"Bearer"` and `ttl.toSeconds()` keeps `AuthService` tidy.

**`UserRepository.findByEmail`**:
- Returns `Optional<User>`. *Hint:* a derived query, like `existsByEmail`. `Optional` because the user may not exist.

**`UserService`**, two new methods:
- `findLoginCredentials(String email)`: `@Transactional(readOnly = true)`, returns `Optional<LoginCredentials>`. *Hint:* `userRepository.findByEmail(email).map(user -> new LoginCredentials(user.getId(), user.getPasswordHash()))`.
- `getById(UUID id)`: `@Transactional(readOnly = true)`, returns `UserResponse`. *Hint:* `findById(id)`, then `orElseThrow` a `NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found.")`, then `UserMapper.toResponse`.

**`UserController`**:
- `@RestController`, `@RequestMapping("/api/v1/users")`.
- Constructor-inject `UserService` and `CurrentUser`.
- `@GetMapping("/me")` returns `userService.getById(currentUser.id())`.
- *Hint:* no `@PathVariable` id. The id **only** comes from the token.

## What the API looks like

### `POST /api/v1/auth/login` (public)

Request:

```json
{ "email": "Ravi@Mail.com", "password": "correct-horse-42" }
```

**200 OK:**

```json
{
  "accessToken": "eyJraWQiOiJzcGVuZGx5LWRldi0xIiwiYWxnIjoiUlMyNTYifQ...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

**Errors:**

| Case | Status | code |
|---|---|---|
| blank email/password, bad email format | 400 | `VALIDATION_FAILED` |
| unknown email, wrong password, no password set, password over 72 bytes | 401 | `INVALID_CREDENTIALS` ("Email or password is incorrect.") |

### `GET /api/v1/users/me` (needs `Authorization: Bearer <token>`)

**200 OK:** the same `UserResponse` as register returns.

| Case | Status | code |
|---|---|---|
| no token, expired, bad signature, wrong issuer, malformed | 401 | `UNAUTHENTICATED` + header `WWW-Authenticate: Bearer` |
| valid token, user deleted | 404 | `USER_NOT_FOUND` |

### Any other path without a token

`401 UNAUTHENTICATED` (even unknown paths).

## Database changes

None. Login reads the existing `users` table through the `users_email_key` unique index (it covers `where email = ?`).

## Security

- **Private key** only from the `JWT_PRIVATE_KEY` env var. It is never logged and never committed. `.env` is already gitignored. The test key lives only under `src/test`, is labelled TEST ONLY, and only signs tokens for the test container.
- **RS256** with a 2048-bit RSA key. The decoder is pinned to RS256, so it rejects `alg: none` and HMAC tricks.
- **Validation of every token:** signature, `exp` (60 seconds of clock skew allowed, Spring's default), `iss = spendly`.
- **15-minute lifetime.** There's no way to revoke an access token early. That's the trade-off of stateless tokens, and why they're short. Revocation per device comes with refresh tokens in 006.
- **Login doesn't reveal which emails exist**: same code, same text, same timing for all failures.
- **The token holds no personal data**: only the user id.
- **Tokens are never logged.** Spring's `BearerTokenAuthenticationFilter` doesn't log the token value at our log levels; our code logs only the user id.
- **CSRF off, stateless, no CORS**: see Decision 8.
- **Rate limiting** of login is still missing until 007. Brute-force is slowed only by BCrypt's 250 ms per attempt. Accepted for now and noted.
- `security-review` runs on the finished diff before I report done.

## Spring concepts this step teaches (simple words)

1. **The security filter chain.** How one Spring filter (`FilterChainProxy`) runs a whole row of security filters before any controller, and how `SecurityFilterChain` + `authorizeHttpRequests` decide "public or locked" per URL.
2. **Authentication and the SecurityContext.** After the JWT filter checks the token, it puts an `Authentication` (who you are) into the `SecurityContext`, a per-request holder much like the MDC from 003. `CurrentUser` reads it from there.
3. **JWT, signing, and the resource server.** What a JWT is (header.payload.signature), why signing with a private key and checking with a public key is safe, and how `JwtEncoder` and `JwtDecoder` do it.
4. **`@ConfigurationProperties`.** Binding `spendly.jwt.*` settings into a typed, validated record, so the app refuses to start without a key.
5. **`@Transactional(readOnly = true)`**, briefly: a hint to Hibernate that this method only reads, so it skips checking for changes at commit.

## Tests

CLAUDE WRITES all tests (your choice, 2026-11-20). You read them with the note.

**`JwtKeysTest`** (plain unit):

| Test | Expected |
|---|---|
| `parsesPrivateKeyAndDerivesMatchingPublicKey` | data signed with the private key verifies with the derived public key |
| `rejectsGarbageKey` | a non-base64 / non-RSA value throws `IllegalStateException` with a message that does **not** contain the key |

**`TokenServiceTest`** (real encoder + decoder built from the test key, no Spring):

| Test | Expected |
|---|---|
| `issuedToken_hasExpectedClaims` | `sub` = user id, `iss` = `spendly`, `exp - iat` = 15 min, `jti` present, header `kid` and `alg=RS256` |
| `issuedToken_containsNoEmail` | the decoded claims contain only `sub`, `iss`, `iat`, `exp`, `jti` |

**`AuthServiceTest`** (Mockito, extended):

| Test | Expected |
|---|---|
| `login_returnsTokenForCorrectPassword` | `TokenService.issue` called with the user id; the response has the token and `expiresIn = 900` |
| `login_normalizesEmail` | `UserService.findLoginCredentials` receives `ravi@mail.com` for `" Ravi@Mail.com "` |
| `login_unknownEmail_runsDummyCheck_andFails` | `INVALID_CREDENTIALS`; `passwordEncoder.matches` **was called** (against the dummy hash) |
| `login_wrongPassword_fails` | `INVALID_CREDENTIALS`; no token issued |
| `login_nullHash_runsDummyCheck_andFails` | Google-only user: `INVALID_CREDENTIALS`, dummy check ran |
| `login_passwordOver72Bytes_fails` | `INVALID_CREDENTIALS`; no token issued |

**`AuthControllerTest`** (`@WebMvcTest`, now imports `SecurityConfig`, `JwtConfig`, the entry point):

| Test | Expected |
|---|---|
| existing register tests | still pass (register is public) |
| `login_returns200WithToken` | 200; body has `accessToken`, `tokenType=Bearer`, `expiresIn=900` |
| `login_blankFields_returns400` | 400 `VALIDATION_FAILED` |
| `login_badCredentials_returns401` | service throws, giving 401 `INVALID_CREDENTIALS` |

**`SecurityIntegrationTest`** (`@SpringBootTest` + MockMvc + Testcontainers, everything real):

| Test | Expected |
|---|---|
| `registerThenLoginThenMe_returnsOwnProfile` | the full flow works; `/me` returns the registered email |
| `me_withoutToken_returns401ProblemDetails` | 401, `code=UNAUTHENTICATED`, `WWW-Authenticate: Bearer`, `requestId` present |
| `me_withExpiredToken_returns401` | a token signed with the test key but `exp` 2 minutes in the past → 401 |
| `me_withTokenSignedByOtherKey_returns401` | a token signed with a freshly generated, different RSA key → 401 |
| `me_withWrongIssuer_returns401` | correct key, `iss = "evil"` → 401 |
| `me_withAlgNoneToken_returns401` | an unsigned token (`alg: none`) → 401 |
| `login_wrongPassword_and_unknownEmail_lookIdentical` | both give 401 with the same `code`, `title` and `detail` |
| `unknownPath_withoutToken_returns401` | `GET /api/v1/nope` → 401, not 404 |
| `health_isPublic` | `GET /actuator/health` without a token → 200 |
| `noSessionCookie` | the login response has no `Set-Cookie` header |

**Ownership test (user A vs user B)**:

| Test | Expected |
|---|---|
| `me_returnsOnlyTheTokenOwner` | users A and B registered; A's token on `/me` returns A, B's returns B. A request can't choose whose profile it sees (there is no id parameter) |

**`UserServiceTest`** (extended): `getById_returnsUser`, `getById_missing_throwsUserNotFound`, `findLoginCredentials_mapsIdAndHash`, `findLoginCredentials_unknown_returnsEmpty`.

**`GlobalExceptionHandlerTest`**: it tests error *shapes*, not security. It gets `@WithMockUser`-style authentication (from `spring-security-test`) so the probe endpoints are reachable, and all 12 tests stay unchanged otherwise.

## Logging

| What | Level | Content |
|---|---|---|
| Login success | INFO | `Login succeeded userId={id}` |
| Login failure | INFO | `Login failed` (no email, no reason that reveals whether the email exists) |
| Login failure reason | DEBUG | `Login failed reason=unknown_email|bad_password|no_password|too_long` (no email) |
| Token issued | DEBUG | `Access token issued userId={id} jti={jti} expiresAt={exp}` |
| 401 at the entry point | DEBUG | `Unauthenticated request path={path}` |
| Bad key at startup | the app fails to start | message names the property `spendly.jwt.private-key`, never its value |

Never logged: the password, any token, the private key, the email.

## Transactions

- `UserService.findLoginCredentials` and `getById`: `@Transactional(readOnly = true)`. Each is one short read.
- `AuthService.login`: **no** transaction, on purpose, as in 004. The BCrypt check (about 250 ms) runs **after** `findLoginCredentials` has returned and released its connection. Signing the token also needs no database.
- `TokenService`: no transaction (no database).
- No self-invocation risk: `AuthService` calls `UserService`, which is another bean, so it goes through the proxy.

## Acceptance criteria

- [ ] `POST /api/v1/auth/login` returns 200 with a 15-minute RS256 JWT for correct credentials.
- [ ] Every login failure returns the identical 401 `INVALID_CREDENTIALS`, and an unknown email still runs a BCrypt check.
- [ ] `GET /api/v1/users/me` with a valid token returns the caller's profile; without one it returns 401 Problem Details with `UNAUTHENTICATED`.
- [ ] Expired, wrongly signed, wrong-issuer and `alg: none` tokens are all rejected.
- [ ] Register, login and health are public; everything else (including unknown paths) needs a token.
- [ ] No session cookie is ever set.
- [ ] The app refuses to start without `JWT_PRIVATE_KEY`, with a clear message.
- [ ] Logs show user ids only, never tokens, emails or passwords.
- [ ] `./mvnw -q verify` passes, including all earlier tests.
- [ ] `security-review` has run, and its findings are fixed or reported.
- [ ] Learning note 005 is written in the simple style; note 004 has its recap.

## Checks to run

From `backend/` (Docker running):
1. `./mvnw -q verify`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then the manual steps below.

## Manual test steps

1. **Generate your dev key once** (Git Bash, from `backend/`):
   ```
   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-dev.pem
   echo "JWT_PRIVATE_KEY=$(grep -v -- '-----' jwt-dev.pem | tr -d '\r\n')" >> .env
   echo "JWT_KEY_ID=spendly-dev-1" >> .env
   rm jwt-dev.pem
   ```
2. Start the app with the dev profile. It starts normally. Remove `JWT_PRIVATE_KEY` from `.env` for a moment: the app now refuses to start and names the missing property. Put it back.
3. Log in as the user from 004:
   ```
   curl -s -X POST localhost:8080/api/v1/auth/login -H "Content-Type: application/json" -d "{\"email\":\"ravi@mail.com\",\"password\":\"correct-horse-42\"}"
   ```
   You get 200 with `accessToken`, `tokenType: Bearer`, `expiresIn: 900`.
4. Paste the token into https://jwt.io (it's a dev token with no secrets inside). You see `alg: RS256`, `kid: spendly-dev-1`, and claims `sub`, `iss`, `iat`, `exp`, `jti`, with no email.
5. `curl -s localhost:8080/api/v1/users/me -H "Authorization: Bearer <token>"` gives 200 with your profile.
6. The same call without the header gives 401 `UNAUTHENTICATED`.
7. A wrong password, and then an unknown email: both give exactly the same 401 body (apart from `requestId`).
8. `curl -s localhost:8080/actuator/health` gives 200 without a token.
9. Commit to `feature/login-jwt` and push. I give you the PR link, and the PR description goes on your clipboard.

## Changes made during implementation

- **No `ProblemDetails` factory.** The entry point hands the security exception to Spring MVC's `HandlerExceptionResolver`, so `GlobalExceptionHandler` (new `@ExceptionHandler(AuthenticationException.class)`) writes the 401. That gives one place for every error body and no copied code. `ProblemDetailsAuthenticationEntryPoint` is a plain class created in `SecurityConfig`.
- **New `UnauthorizedException`** (401, like `ConflictException` for 409) for `INVALID_CREDENTIALS`.
- **`JwtProperties` is registered by `@EnableConfigurationProperties` on `JwtConfig`**, not by scanning, so it exists exactly where the JWT beans do (including `@WebMvcTest`s that import `JwtConfig`).
- **`JwtConfig` bean methods are `public`**, so `TokenServiceTest` can build a real encoder and decoder without Spring.
- **`JwtKeys` also rejects keys under 2048 bits.**
- **`AuthService` builds `TokenResponse` with its constructor** (`new TokenResponse(token, "Bearer", seconds)`), so it works whether or not you add the optional `bearer(...)` factory.
- **Extra tests:** `JwtKeysTest.rejectsKeyShorterThan2048Bits`, `toStringHidesKeys`; `TokenServiceTest.eachTokenHasItsOwnId`; `SecurityIntegrationTest.me_forDeletedUser_returns404`, `me_withGarbageToken_returns401`.
- **The test key** is in `src/test/resources/config/application.properties` (TEST ONLY).
