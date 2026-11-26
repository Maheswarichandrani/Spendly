# 007: Refresh tokens, rotation and logout, explained simply

**In short:** login now gives the app **two** tokens. The short one (access token, 15
minutes) opens doors. The long one (refresh token, 30 days) is only used to get a **new**
pair when the short one runs out. Every refresh token works **once**. If someone uses an
old one again, we know it was copied, and we log that phone out.

Read this top to bottom. Each part builds on the one before.

---

## Part 1. The big picture

### The problem
In step 005, login returned only an access token that lives 15 minutes.
- Make it live longer (say 30 days)? Then a stolen token works for 30 days, and we can't
  cancel it, because a JWT is checked by its signature alone. The server keeps no list of them.
- Keep it at 15 minutes? Then the user must type their password every 15 minutes.

Neither is OK.

### The solution: two tokens
| | Access token | Refresh token |
|---|---|---|
| What it is | A signed JWT (step 005) | A long random string, 43 characters |
| Lives | 15 minutes | 30 days (renewed on every use) |
| Sent on | **every** API call (`Authorization: Bearer ...`) | **only** to `/auth/refresh` and `/auth/logout` |
| Server keeps a copy? | No, it checks the signature | **Yes**, a row in `refresh_tokens` (as a hash) |
| Can we cancel it? | No, it just expires | **Yes**: set `revoked_at` on its row |

### An analogy: a cinema with paper tickets
- The **access token** is your seat wristband for one show. It's cheap and expires quickly.
- The **refresh token** is a paper ticket you hand in at the desk to get a new wristband
  **and a new paper ticket**. The desk **tears** the old ticket.
- If someone hands in a ticket that's **already torn**, a copy exists: either someone
  photocopied it, or you're the copier. The desk can't tell who is honest, so it
  **cancels the whole booking**, and that person must buy again (log in again).
- The **booking** is what we call a **family**: every ticket that came from one login on one device.

### Three new words
1. **Rotation:** every refresh gives a new refresh token and kills the old one.
2. **Family:** all the refresh tokens that came from one login (one device). It's stored
   as `family_id`.
3. **Reuse:** an already-used (revoked) token comes back. That's the sign of theft, and we
   revoke the whole family.

---

## Part 2. What the app sees (the API)

| Endpoint | Needs access token? | Body | Answer |
|---|---|---|---|
| `POST /api/v1/auth/login` | no | `{ "email", "password", "deviceLabel"? }` | 200 + both tokens |
| `POST /api/v1/auth/refresh` | no | `{ "refreshToken" }` | 200 + a **new** pair, or 401 `INVALID_REFRESH_TOKEN` |
| `POST /api/v1/auth/logout` | no | `{ "refreshToken" }` | always 204 |
| `POST /api/v1/auth/logout-all` | **yes** | none | 204 |

Login and refresh both return the same shape:
```json
{
  "accessToken": "eyJraWQiOi...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshToken": "q3Jw9mH0bS2xA7eK1vZt5Lr8Np4Yc6Ud0Wf3Gi9Oj2E",
  "refreshTokenExpiresAt": "2026-12-26T08:15:00Z"
}
```

The app's life cycle:
1. Log in, then save both tokens in secure storage.
2. Call the API with the access token.
3. A 401 `UNAUTHENTICATED` means the access token expired. Call `/auth/refresh` with the
   refresh token, save **both** new tokens, and repeat the failed call.
4. If `/auth/refresh` returns 401 `INVALID_REFRESH_TOKEN`, delete the tokens and show the login screen.

---

## Part 3. The files we touched (the map)

| # | File | What it does in one sentence |
|---|---|---|
| 1 | `db/migration/V3__create_refresh_tokens.sql` | Creates the `refresh_tokens` table. |
| 2 | `auth/entity/RefreshToken.java` | The Java object for one row of that table. |
| 3 | `auth/repository/RefreshTokenRepository.java` | Finds a token by hash, and three "revoke" updates. |
| 4 | `auth/service/RefreshTokenService.java` | **The heart:** issue, rotate, detect reuse, revoke. |
| 5 | `auth/service/AuthService.java` | Login now also issues a refresh token; new refresh/logout methods. |
| 6 | `auth/dto/RefreshTokenRequest.java`, `TokenResponse.java`, `LoginRequest.java` | Request and response shapes. |
| 7 | `auth/controller/AuthController.java` | The three new endpoints. |
| 8 | `common/security/SecurityConfig.java` | Makes refresh and logout reachable without an access token. |
| 9 | `common/config/RefreshTokenProperties.java` + `application.properties` | The "30 days" setting. |
| 10 | `common/exception/ErrorCode.java` | The new code `INVALID_REFRESH_TOKEN`. |

---

## Part 4. The code, line by line

### 4.1 The table: `V3__create_refresh_tokens.sql`

```sql
CREATE TABLE refresh_tokens (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id     UUID        NOT NULL,
    token_hash    TEXT        NOT NULL,
    device_label  TEXT,
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    ...
```
Column by column:
- `id`: the row's own id (UUIDv7 from Java, like `users`).
- `user_id ... REFERENCES users (id)`: a **foreign key**, meaning "this must be the id of a
  real user". The database refuses anything else.
- `ON DELETE CASCADE`: if the user is deleted, delete their tokens too, automatically.
- `family_id`: which login (device) this token belongs to. Rotations keep the same value.
- `token_hash`: **not the token itself**. A scrambled fingerprint of it (section 4.4).
- `device_label`: optional text from the app, like "Pixel 8". `NULL` is allowed.
- `expires_at`: after this moment, the token is dead.
- `revoked_at`: `NULL` means still usable. A time means it was killed then (by rotation,
  logout or reuse). We never delete rows, so the history stays readable.

```sql
    CONSTRAINT refresh_tokens_token_hash_key       UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_hash_format          CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_tokens_device_label_length  CHECK (length(device_label) <= 100)
);
```
- `UNIQUE (token_hash)`: no two rows with the same hash. Postgres builds an **index** for
  this, and that index makes "find the row for this hash" fast. Every refresh does exactly that.
- `CHECK (token_hash ~ '^[0-9a-f]{64}$')`: `~` means "matches this pattern": exactly 64
  characters of 0–9 and a–f (a SHA-256 hash written as hex). If a bug ever tried to save the
  **raw** token, the database would refuse it. A safety net.
- `CHECK (length(device_label) <= 100)`: the same limit as the `@Size(max = 100)` in Java,
  enforced a second time by the database.

```sql
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);
```
- An **index** is like the index at the back of a book: it lets Postgres jump to matching
  rows instead of reading the whole table.
- `user_id`: for "log out everywhere" (all rows of one user) and for the cascade delete.
- `family_id`: for "revoke this whole family".

---

### 4.2 The entity: `RefreshToken.java`

```java
@Entity
@Table(name = "refresh_tokens")
@Getter
public class RefreshToken extends BaseEntity {
```
- `@Entity`: "this class is a table row" (step 004).
- `@Table(name = ...)`: which table.
- `@Getter` (Lombok): generates `getUserId()`, `getFamilyId()` and so on. No setters: nobody
  should change these fields by hand.
- `extends BaseEntity`: gets `id`, `createdAt`, `updatedAt`, and the safe `equals`/`hashCode` (step 004).

```java
@Column(name = "user_id", nullable = false, updatable = false)
private UUID userId;
```
- `@Column(name = ...)`: which column this field maps to.
- `nullable = false`: Hibernate's own reminder that this can't be null (the database also has `NOT NULL`).
- `updatable = false`: Hibernate will **never** include this column in an `UPDATE`. A
  token never moves to another user.
- **Why `UUID userId` and not `@ManyToOne User user`?** The auth feature never needs the
  whole User object here. A plain id is simpler: no lazy loading, and no reaching into
  another feature's entity (features talk through services, AGENTS.md section 5). The
  foreign key in the database still guarantees the user exists.

```java
@Column(name = "revoked_at")
private Instant revokedAt;
```
- The only field that ever changes. It has no `updatable = false`.
- `Instant`: a moment in time, in UTC. It maps to `TIMESTAMPTZ`.

```java
protected RefreshToken() {
}

public RefreshToken(UUID userId, UUID familyId, String tokenHash, String deviceLabel, Instant expiresAt) { ... }
```
- The empty `protected` constructor is **for Hibernate only**. It creates an empty object,
  then fills it from the row.
- The public constructor is for **us**, when creating a new token.

```java
public boolean isRevoked() {
    return revokedAt != null;
}

public boolean isExpiredAt(Instant now) {
    return !now.isBefore(expiresAt);
}
```
- Two small questions the service asks.
- `!now.isBefore(expiresAt)` means "now is at or after the expiry". We pass `now` in
  instead of calling `Instant.now()` inside, so one request uses **one** "now" everywhere.

---

### 4.3 The repository: `RefreshTokenRepository.java`

```java
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);
```
- `extends JpaRepository<RefreshToken, UUID>`: Spring Data writes the class for us, with
  `save`, `findById` and so on (step 004).
- `findByTokenHash`: a **derived query**. Spring reads the method name and creates
  `select ... from refresh_tokens where token_hash = ?`.
- `Optional`: the row may not exist (a fake token), so "maybe a result".

Now the three new things: **`@Modifying` `@Query` updates**.

```java
@Modifying
@Query("""
        update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now
        where t.id = :id and t.revokedAt is null""")
int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);
```
Line by line:
- `@Query("...")`: we write the query ourselves instead of using a method name. It's
  **JPQL**, which looks like SQL but uses **class and field names** (`RefreshToken`,
  `revokedAt`), not table and column names (`refresh_tokens`, `revoked_at`). Hibernate
  translates it to SQL.
- `@Modifying`: "this query **changes** data (UPDATE/DELETE), it doesn't read". Without
  it, Spring Data expects a SELECT and fails.
- `:now`, `:id`: **named parameters**, the placeholders in the query.
- `@Param("id") UUID id`: "put this Java value into `:id`". The names must match.
- `int` return value: how many rows were changed. **This number is the important part**
  (next section).
- `where ... and t.revokedAt is null`: only change the row if it's still active.

**The "atomic claim", the most important idea in this step.** Imagine two requests
arrive at the same moment with the **same** refresh token (a thief and the real app, or a
buggy double-tap):

```
Request A                                  Request B
---------                                  ---------
find token → active                        find token → active
UPDATE ... WHERE id=? AND revoked IS NULL  UPDATE ... WHERE id=? AND revoked IS NULL
  → Postgres locks the row                   → waits for A's lock...
  → 1 row changed ✅ (A wins)
commit                                       → A committed, B re-checks the WHERE:
                                               revoked_at is no longer NULL
                                             → 0 rows changed ❌ → B knows it lost
```
- Postgres does "check the condition" and "change the row" as **one unbreakable step**
  (that's what *atomic* means).
- So exactly one request gets `1`, and the others get `0`. We need no extra locks and no
  `@Version`.
- **The wrong way** would be checking in Java:
  `if (!token.isRevoked()) { token.setRevokedAt(now); }`. Both requests could pass the
  `if` before either one saves, and both would win.

```java
int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);   // where t.familyId = :familyId and t.revokedAt is null
int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);  // where t.userId = :userId and t.revokedAt is null
```
- The same pattern, changing **many** rows at once (a "bulk update"): one family, or every
  token of a user.

**Two traps of `@Modifying` updates** (this is why the class comment mentions them):
1. **`@UpdateTimestamp` doesn't run.** It only works when *Hibernate* saves an entity it
   is tracking. A bulk `update` query goes straight to the database. So we set
   `t.updatedAt = :now` ourselves.
2. **Objects already loaded don't change.** If we loaded a `RefreshToken` before the
   update, that Java object still shows the old `revokedAt`. Hibernate doesn't know the
   database changed. Our code never reads the object again after updating, so this is
   safe, but remember it.

---

### 4.4 The heart: `RefreshTokenService.java`

#### Making a token
```java
private static final int TOKEN_BYTES = 32;
private static final SecureRandom RANDOM = new SecureRandom();
```
- 32 random bytes = 256 bits. There are more possible tokens than atoms in the universe,
  so guessing one is impossible.
- `SecureRandom`: Java's random generator for security. It gets its randomness from the
  operating system. Plain `Random` is predictable and must never be used for tokens.

```java
private IssuedRefreshToken issue(UUID userId, UUID familyId, String deviceLabel) {
    byte[] bytes = new byte[TOKEN_BYTES];
    RANDOM.nextBytes(bytes);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiresAt = Instant.now().plus(properties.ttl());
    repository.save(new RefreshToken(userId, familyId, hash(raw), deviceLabel, expiresAt));
    return new IssuedRefreshToken(raw, expiresAt);
}
```
Step by step:
1. Fill 32 bytes with random values.
2. Turn the bytes into text with **URL-safe Base64**, which uses only `A–Z a–z 0–9 - _`, so
   it's safe in JSON and URLs. `withoutPadding()` drops the trailing `=`. Result: 43 characters.
3. Expiry = now + 30 days (`properties.ttl()`, from `spendly.refresh-token.ttl=30d`).
4. Save a row with the **hash**, not the raw token.
5. Return the **raw** token to give to the app. This is the only time it exists in plain form.

#### Hashing the token
```java
static String hash(String rawToken) {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest);
}
```
- SHA-256 turns any text into a fixed 32-byte fingerprint. The same input always gives the
  same fingerprint, and you **can't** go back from the fingerprint to the input.
- `HexFormat.of().formatHex(...)` writes those bytes as 64 hex characters (`0-9a-f`),
  which is exactly what the database `CHECK` expects.
- **Why not BCrypt, like passwords?**
  - BCrypt is slow on purpose, to stop people guessing **weak** passwords. Our token is
    256 random bits, so there's nothing to guess.
  - BCrypt adds a random salt, so the same token would give a **different** hash each
    time, and we couldn't find the row by hash. SHA-256 always gives the same answer, so
    `findByTokenHash` works.

#### The result types: a "menu of answers"
```java
public record IssuedRefreshToken(String rawToken, Instant expiresAt) {
    @Override
    public String toString() { return "IssuedRefreshToken[rawToken=***, expiresAt=" + expiresAt + "]"; }
}
```
- A small box holding the raw token and its expiry.
- `toString()` is overridden so that if anyone ever logs it, the token shows as `***`.

```java
public sealed interface RotationResult {
    record Rotated(UUID userId, IssuedRefreshToken newToken) implements RotationResult { }
    record Rejected(Reason reason) implements RotationResult { }
    enum Reason { UNKNOWN, EXPIRED, REUSED }
}
```
- `sealed interface`: "a `RotationResult` can **only** be one of the types listed here".
  Nobody can add a third kind elsewhere.
- So `rotate()` returns **either** `Rotated` (success, with the new token) **or**
  `Rejected` (with the reason). It's like a parcel that's either "delivered" or "returned to
  sender, reason: ...".
- *Why not just throw an exception on failure?* See "the rollback trap" below. It's the key reason.

#### Login: start a new family
```java
@Transactional
public IssuedRefreshToken issueForLogin(UUID userId, String deviceLabel) {
    UUID familyId = UUID.randomUUID();
    IssuedRefreshToken issued = issue(userId, familyId, deviceLabel);
    log.info("Refresh token family started userId={} familyId={}", userId, familyId);
    return issued;
}
```
- A new login means a new `familyId`, so this device gets its own family.
- `@Transactional`: the insert is committed when the method ends (step 004).
- The log line holds **ids only**: no token, no hash, no email.

#### Refresh: the main method
```java
@Transactional
public RotationResult rotate(String rawToken) {
    Instant now = Instant.now();
    Optional<RefreshToken> found = repository.findByTokenHash(hash(rawToken));
    if (found.isEmpty()) {
        log.info("Refresh rejected reason=unknown");
        return new RotationResult.Rejected(RotationResult.Reason.UNKNOWN);
    }
    RefreshToken current = found.get();
```
1. Hash what the app sent and look for the row.
2. No row? It's a fake or very old token, so return `Rejected(UNKNOWN)`.

```java
    if (current.isRevoked() || repository.revokeIfActive(current.getId(), now) == 0) {
        int revoked = repository.revokeFamily(current.getFamilyId(), now);
        log.warn("Refresh token reuse detected userId={} familyId={} revokedTokens={}", ...);
        return new RotationResult.Rejected(RotationResult.Reason.REUSED);
    }
```
3. Two ways to detect reuse:
   - `current.isRevoked()`: the token was **already torn** before this request, so it's reuse;
   - `revokeIfActive(...) == 0`: it looked active, but another request **claimed it a
     moment ago** (the race from 4.3), so it's also reuse.
4. Either way, **revoke the whole family**. That device is logged out, and so is the thief.
5. `log.warn`: this is a security event, so it's logged louder than normal (WARN, not INFO).
6. If `revokeIfActive` returned `1`, **we** tore the ticket and carry on.

Java's `||` matters here: if `isRevoked()` is already `true`, the right side
(`revokeIfActive`) **doesn't run**. We only try to claim tokens that look active.

```java
    if (current.isExpiredAt(now)) {
        log.info("Refresh rejected reason=expired userId={}", current.getUserId());
        return new RotationResult.Rejected(RotationResult.Reason.EXPIRED);
    }
```
7. Too old? Rejected. (We already tore it in step 6. That's harmless, since it was dead anyway.)

```java
    IssuedRefreshToken next = issue(current.getUserId(), current.getFamilyId(), current.getDeviceLabel());
    log.info("Refresh token rotated userId={} familyId={}", current.getUserId(), current.getFamilyId());
    return new RotationResult.Rotated(current.getUserId(), next);
}
```
8. Success: a new token in the **same family**, with the same device label. Return `Rotated`.

Tearing the old ticket and inserting the new one happen in **one transaction**: either
both are saved, or neither.

#### The rollback trap: why `rotate()` returns instead of throwing
Remember from step 004: when a `RuntimeException` leaves a `@Transactional` method, Spring
**rolls back** (undoes) everything the method wrote.

Now imagine `rotate()` **threw** on reuse:
```
rotate():  revokeFamily(...)   ← writes "family revoked"
           throw new UnauthorizedException(...)
Spring:    exception left the method → ROLLBACK → "family revoked" is undone!
Result:    the thief's newer token still works. 😱
```
So instead:
```
rotate():       revokeFamily(...)  → return Rejected(REUSED)
Spring:         normal return → COMMIT → the revoke is saved ✅
AuthService:    sees Rejected → throws UnauthorizedException → 401 to the app
```
`AuthService` has **no** `@Transactional`, so its exception rolls nothing back. The
transaction was already closed.

#### Logout
```java
@Transactional
public void revokeFamilyOf(String rawToken) {
    repository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
        int revoked = repository.revokeFamily(token.getFamilyId(), Instant.now());
        log.info("Logout userId={} familyId={} revokedTokens={}", ...);
    });
}
```
- `ifPresent(...)`: only if the row exists. An unknown token does **nothing**, with no error.
  So logout can be retried safely (the word is "idempotent": doing it twice equals doing it once).

```java
@Transactional
public int revokeAllForUser(UUID userId) {
    int revoked = repository.revokeAllForUser(userId, Instant.now());
    ...
}
```
- Logout everywhere: every active token of this user, on all devices.
- Note: access tokens already given out keep working until they expire (at most 15
  minutes). That's the price of JWTs not being stored.

---

### 4.5 `AuthService.java`: using it

Login, after the password check:
```java
UUID userId = credentials.get().id();
IssuedRefreshToken refreshToken = refreshTokenService.issueForLogin(userId, request.deviceLabel());
log.info("Login succeeded userId={}", userId);
return tokens(userId, refreshToken);
```

Refresh:
```java
public TokenResponse refresh(RefreshTokenRequest request) {
    RotationResult result = refreshTokenService.rotate(request.refreshToken());
    if (result instanceof RotationResult.Rotated rotated) {
        return tokens(rotated.userId(), rotated.newToken());
    }
    throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN, "Your session has ended. Please log in again.");
}
```
- `result instanceof RotationResult.Rotated rotated`: "if it's a success, call it `rotated`
  and use it". This is Java's **pattern matching**, a check and a cast in one.
- Anything else is a rejection, so throw a 401.
- **One message for all three reasons** (unknown, expired, reused): the app does the same
  thing every time (go to login), and telling an attacker *why* only helps them. The real
  reason is in our logs.
- The message `"Your session has ended. Please log in again."` goes into the `detail` field
  of the error JSON, so the app may show it to the user.

```java
private TokenResponse tokens(UUID userId, IssuedRefreshToken refreshToken) {
    String accessToken = tokenService.issueAccessToken(userId);
    return TokenResponse.bearer(accessToken, tokenService.accessTokenTtl(), refreshToken.rawToken(),
            refreshToken.expiresAt());
}
```
- One helper used by both login and refresh, so the two always return the same shape.
- `TokenResponse.bearer(...)` is a **static factory method**: a named way to build the
  object that fills in `"Bearer"` and turns the duration into seconds.

`AuthService` has no `@Transactional` on purpose. BCrypt (about 250 ms) and JWT signing
shouldn't hold a database connection.

---

### 4.6 The DTOs: annotations and messages

`RefreshTokenRequest`:
```java
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required.")
        @Size(max = 200, message = "Refresh token is too long.")
        @Schema(description = "The refreshToken from the last login or refresh.", example = "q3Jw9mH0...")
        String refreshToken) {

    @Override
    public String toString() { return "RefreshTokenRequest[refreshToken=***]"; }
}
```
As in note 006, two kinds of annotation:
- `@NotBlank` and `@Size`: **real rules**, checked on every request because of `@Valid`. If
  one fails, the answer is 400 `VALIDATION_FAILED`, and **the `message` text is what the app
  receives** in `errors[]`:
  ```json
  "errors": [ { "field": "refreshToken", "message": "Refresh token is required." } ]
  ```
- `@Size(max = 200)`: our tokens are 43 characters. Without a cap, someone could send 10 MB
  and make us hash it. Limits protect the server.
- `@Schema`: **docs only**. It's shown in Swagger and never checks anything.
- `toString()` hides the token, so it can never land in a log by accident.

`LoginRequest`, the new field:
```java
@Size(max = 100, message = "Device label must be at most 100 characters.")
@Schema(description = "Optional name of this device, shown later in the device list.", example = "Pixel 8", nullable = true)
String deviceLabel
```
- No `@NotBlank`, so it's **optional**. The app may leave it out.
- `nullable = true` tells the docs it can be null.

```java
public LoginRequest {
    email = email == null ? null : email.strip();
    deviceLabel = deviceLabel == null || deviceLabel.isBlank() ? null : deviceLabel.strip();
}
```
- A **compact constructor** (step 004): it runs when Jackson builds the record from JSON.
- `"   "` (only spaces) becomes `null`, and `" Pixel 8 "` becomes `"Pixel 8"`. Clean data in the table.

```java
public LoginRequest(String email, String password) {
    this(email, password, null);
}
```
- An extra, shorter constructor, so old code and tests that send only email and password
  keep working. Jackson still uses the full one.

`TokenResponse` gained two fields, `refreshToken` and `refreshTokenExpiresAt`, each with
`@Schema` text that tells the app developer the most important rule: **single use, replace
both tokens on every refresh**.

---

### 4.7 The controller: `AuthController.java`

```java
@PostMapping("/refresh")
@SecurityRequirements
@Operation(summary = "Get new tokens", description = """ ... Run **one refresh at a time** ... """)
@ApiResponse(responseCode = "200", description = "New token pair")
@ApiResponse(responseCode = "401", description = "`INVALID_REFRESH_TOKEN`: unknown, expired, revoked or reused. ...", content = ...)
public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
    return authService.refresh(request);
}
```
- `@PostMapping("/refresh")`: the URL. The class has `/api/v1/auth`, so the full URL is `/api/v1/auth/refresh`.
- `@Valid @RequestBody`: turn the JSON into a `RefreshTokenRequest` and check its rules.
- `@SecurityRequirements`: **docs only**, "no lock icon". (The *real* "no token needed" rule is in `SecurityConfig`, 4.8.)
- `@Operation` / `@ApiResponse`: docs text (note 006).
- The method body is **one line**: controllers only do HTTP, and the logic lives in services.

```java
@PostMapping("/logout")
@ResponseStatus(HttpStatus.NO_CONTENT)
@SecurityRequirements
public void logout(@Valid @RequestBody RefreshTokenRequest request) {
    authService.logout(request);
}
```
- `@ResponseStatus(HttpStatus.NO_CONTENT)`: a **real** behavior annotation (Spring's, not
  Swagger's). It makes the answer **204 No Content**: "done, nothing to send back".
- `void`: there's no body to return.

```java
@PostMapping("/logout-all")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void logoutAll() {
    authService.logoutEverywhere(currentUser.id());
}
```
- **No** `@SecurityRequirements`, so it needs the access token (lock icon; 401 is added automatically).
- `currentUser.id()`: the user id from the **verified token**, never from the request (step 005).
  So you can only ever log out **yourself**.

The constructor now also receives `CurrentUser` (constructor injection, as always).

---

### 4.8 `SecurityConfig.java`: who may call what

```java
.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
        // The refresh token itself is the proof here, so no access token is needed.
        "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
```
- These four URLs work **without** an access token.
- *Why refresh?* The app calls it exactly **because** the access token has expired.
- *Why logout?* The user may tap "Log out" after the access token expired. The refresh
  token proves which session to end.
- `logout-all` is not in the list, so it falls into `.anyRequest().authenticated()` and needs the access token.

`@SecurityRequirements` in the controller and `permitAll` here must **agree**. The first
is what the docs *say*; the second is what *actually happens*.

---

### 4.9 The setting: `RefreshTokenProperties.java`

```java
@Validated
@ConfigurationProperties(prefix = "spendly.refresh-token")
public record RefreshTokenProperties(@NotNull Duration ttl) {
}
```
and in `application.properties`:
```properties
spendly.refresh-token.ttl=30d
```
- `@ConfigurationProperties(prefix = ...)`: Spring reads every property that starts with
  `spendly.refresh-token.` and puts it into this record. `ttl` matches `spendly.refresh-token.ttl`.
- `Duration ttl`: Spring understands `30d`, `15m` and `1h` and turns them into a Java `Duration`.
- `@Validated` + `@NotNull`: if the setting is missing, the app **refuses to start**, with
  a clear message. Better than crashing at the first login.
- It's registered in `JwtConfig`:
  `@EnableConfigurationProperties({ JwtProperties.class, RefreshTokenProperties.class })`.

---

## Part 5. One real request, step by step: a stolen token is used

Situation: the real app logged in and got token **T1**. It refreshed and got **T2** (T1
is now torn). A thief copied **T1** earlier and now tries it.

1. The thief sends `POST /api/v1/auth/refresh` with `{ "refreshToken": "T1" }`.
2. `RequestIdFilter` adds a request id. In the security chain, `/auth/refresh` is
   `permitAll`, so it passes with no token.
3. `AuthController.refresh`: `@Valid` checks the body (not blank, ≤ 200 characters). OK.
4. `AuthService.refresh` calls `refreshTokenService.rotate("T1")`. That call goes through
   Spring's **proxy**, which **opens a transaction**.
5. `findByTokenHash(sha256("T1"))` finds the row, with `revoked_at` set (torn at step "T2").
6. `isRevoked()` is `true`, so this is **reuse**.
7. `revokeFamily(familyId, now)` kills **T2** too, which is the newest token of that family.
8. `log.warn("Refresh token reuse detected userId=... familyId=...")`.
9. It returns `Rejected(REUSED)`, the proxy **commits**, and the revoke is saved.
10. Back in `AuthService`, the result isn't `Rotated`, so it throws
    `UnauthorizedException(INVALID_REFRESH_TOKEN, "Your session has ended. Please log in again.")`.
11. `GlobalExceptionHandler` builds the 401 Problem Details:
    ```json
    { "status": 401, "code": "INVALID_REFRESH_TOKEN", "detail": "Your session has ended. Please log in again.", "requestId": "..." }
    ```
12. Later, the real app tries to refresh with **T2** and also gets 401, so it shows the
    login screen. The user logs in again and gets a fresh family. The thief has nothing left.

We tested exactly this on Neon in step 007: the old token gave 401, then the new one gave 401 too.

---

## Part 6. Why this design (and not something else)

| Option | Why we didn't pick it |
|---|---|
| One long-lived JWT (30 days) | Can't be cancelled; a stolen one works for a month. |
| Refresh token as a JWT | It must be cancellable anyway, so we'd still need a table. A random string is simpler. |
| Store the raw token | A database leak would hand out working tokens. We store only the hash. |
| BCrypt the token | Slow for no reason, and its salt makes lookup by hash impossible. |
| Check "revoked?" in Java, then save | Two parallel requests could both pass. The `UPDATE ... WHERE` claim is atomic. |
| Throw inside `rotate()` | The rollback would undo the family revoke. We return a result instead. |
| Different error codes for unknown/expired/reused | The app does the same thing anyway, and the details only help attackers. |

---

## Part 7. Common mistakes

1. **Throwing inside a transaction after an important write.** Rollback undoes the write.
   Return a result, or save it in a separate transaction.
2. **Read, check, then write in Java for "use once" things.** Put the check inside the
   `UPDATE`'s `WHERE` and look at the row count.
3. **Forgetting `updatedAt` in a `@Modifying` query.** `@UpdateTimestamp` doesn't run for bulk updates.
4. **Using an entity after a bulk update.** Its fields are stale. Load it again if you need it.
5. **Logging the token or its hash.** The hash finds the row. Log ids only.
6. **The app refreshing twice at the same time.** The second one looks like reuse, and the
   user gets logged out. The app must allow **one** refresh at a time (this goes in the
   frontend hand-off).
7. **Saving only the new access token** after a refresh. The old refresh token is now
   dead, so the next refresh fails. Always replace **both**.

---

## Part 8. Try it yourself

1. **See the rollback trap.** In `rotate()`, replace
   `return new RotationResult.Rejected(RotationResult.Reason.REUSED);` with
   `throw new UnauthorizedException(ErrorCode.INVALID_REFRESH_TOKEN, "x");`. Run
   `RefreshTokenIntegrationTest.refresh_oldTokenAfterRotation_isReuse_andKillsTheWholeFamily`.
   It fails: the newer token still works, because the revoke was rolled back. Undo your change.
2. **See the missing timestamp.** Remove `t.updatedAt = :now` from `revokeFamily`, cause a
   reuse in Swagger, then look at `updated_at` in the table. It didn't change. Undo.
3. **Read the story in the table.** In Swagger: log in, refresh twice, then refresh with the
   **first** token. Then run in psql (or the Neon SQL editor):
   ```sql
   select family_id, device_label, created_at, revoked_at
   from refresh_tokens order by created_at desc limit 5;
   ```
   You'll see 3 rows with the same `family_id`, all with `revoked_at` set.
4. **Test the race yourself.** Read `refresh_concurrentUseOfOneToken_atMostOneSucceeds`.
   It fires 4 refreshes at once. Predict how many get 200, then run it.

---

## Remember these
- Access token: short, not stored, can't be cancelled. Refresh token: long, stored as a hash, can be cancelled.
- Every refresh token works **once**. Reuse kills the whole family (that device).
- `UPDATE ... WHERE revoked_at IS NULL` plus the row count is a lock-free "only one wins" guard.
- `@Modifying` queries skip `@UpdateTimestamp` and don't refresh loaded objects.
- Don't throw inside a transaction when a write must survive. Return a result and throw outside.
- Validation `message` = text the app user sees. `@Schema` = text the developer reads.
