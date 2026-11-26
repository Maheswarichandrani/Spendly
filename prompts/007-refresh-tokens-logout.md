# 007: Refresh tokens, rotation, logout

Part of the approved roadmap `prompts/ROADMAP-2026-11-26.md`.

## Goal
Keep users logged in for weeks without long-lived access tokens: login also returns a
refresh token; the app trades it for a new pair when the 15-minute access token expires.
Logout ends one device's session; logout-all ends every session.

## Decisions
- **Refresh token = 32 random bytes**, URL-safe base64 (43 chars). Not a JWT: it only has
  to be looked up, and it must be revocable.
- **Stored as a SHA-256 hex hash** (`token_hash`, unique). BCrypt is pointless for 256-bit
  random tokens, and its salt would prevent lookup by hash.
- **Families:** each login starts a `family_id`; rotations stay in that family. One family is one device session.
- **Rotation is single use.** The old token is "claimed" with
  `UPDATE ... SET revoked_at = now WHERE id = ? AND revoked_at IS NULL`. If 0 rows change,
  someone used it already, which counts as **reuse** and revokes the whole family.
- **Rejected results are returned, not thrown,** from `RefreshTokenService.rotate`, so the
  family revoke commits. `AuthService` throws the 401 after the transaction has ended.
- **One error code** `INVALID_REFRESH_TOKEN` (401) for unknown, expired, revoked and reused
  tokens. The app does the same thing in every case: show login. Reuse is logged at WARN.
- **TTL 30 days** (`spendly.refresh-token.ttl`). Every refresh issues a fresh 30 days (sliding).
- **logout is public** (the refresh token is the proof), always returns 204, and is
  idempotent. **logout-all needs the access token**, and access tokens already issued live
  on for at most 15 minutes.
- `user_id` is a plain UUID column (FK `ON DELETE CASCADE`), not `@ManyToOne User`.
- Deferred: a cleanup job for expired rows (comes with the scheduler step), a device list,
  and rate limiting (Redis step).

## Files (all CLAUDE WRITES)
- `db/migration/V3__create_refresh_tokens.sql`
- `auth/entity/RefreshToken.java`, `auth/repository/RefreshTokenRepository.java`
- `auth/service/RefreshTokenService.java`; `AuthService` (refresh, logout, logoutEverywhere)
- `auth/dto/RefreshTokenRequest.java`; `TokenResponse` (+ refreshToken, refreshTokenExpiresAt); `LoginRequest` (+ optional deviceLabel)
- `auth/controller/AuthController.java` (3 endpoints); `SecurityConfig` (permit refresh and logout)
- `common/config/RefreshTokenProperties.java`; `ErrorCode.INVALID_REFRESH_TOKEN`
- Tests: `RefreshTokenIntegrationTest`, plus updates to `AuthServiceTest`, `AuthControllerTest` and `FlywayBaselineTest`
- `docs/learn/007-refresh-tokens-and-bulk-updates.md`

## API contract
| Endpoint | Auth | Body | Success | Errors |
|---|---|---|---|---|
| `POST /api/v1/auth/login` | public | `{ email, password, deviceLabel? }` | 200 `TokenResponse` | 400, 401 `INVALID_CREDENTIALS` |
| `POST /api/v1/auth/refresh` | public | `{ refreshToken }` | 200 `TokenResponse` (new pair) | 400, 401 `INVALID_REFRESH_TOKEN` |
| `POST /api/v1/auth/logout` | public | `{ refreshToken }` | 204 | 400 |
| `POST /api/v1/auth/logout-all` | Bearer | none | 204 | 401 `UNAUTHENTICATED` |

`TokenResponse = { accessToken, tokenType: "Bearer", expiresIn: 900, refreshToken, refreshTokenExpiresAt }`

## Database
`refresh_tokens(id, user_id FK cascade, family_id, token_hash UNIQUE, device_label ≤100, expires_at, revoked_at, created_at, updated_at)`.
Indexes:
- `UNIQUE(token_hash)`: the lookup on every refresh.
- `(user_id)`: logout-all and the FK cascade.
- `(family_id)`: the family revoke.

## Spring concepts this step teaches
- `@Modifying` JPQL bulk updates, and why they skip `@UpdateTimestamp` and the persistence context.
- Rollback on exception vs. returning a result, so a needed write survives.
- An atomic conditional UPDATE as a concurrency guard (no locks, no `@Version`).
- Sealed interfaces and records as a service's result type.

## Transactions
- `issueForLogin`, `rotate`, `revokeFamilyOf` and `revokeAllForUser` are each one `@Transactional` method.
- `AuthService` has none. BCrypt and JWT signing run outside any DB transaction.
- Trap: throwing inside `rotate` would roll back the family revoke. That's why it returns `Rejected`.

## Logging
- INFO: family started, rotated, logout, logout everywhere (userId, familyId, counts).
- WARN: reuse detected (userId, familyId).
- Never: the raw token or its hash.

## Tests (all passing)
`RefreshTokenIntegrationTest`:
- stored only as a hash, with the device label;
- rotation returns a new working pair;
- an old token reused kills the family;
- reuse leaves other devices alone;
- of 4 concurrent refreshes, at most 1 gets 200;
- expired, unknown and blank tokens are rejected;
- logout needs no access token;
- logout with an unknown token returns 204;
- logout-all hits only that user (B untouched);
- logout-all without a token returns 401;
- deleting a user cascades to their tokens.

`AuthServiceTest`:
- login returns the refresh token;
- refresh rotated gives a new pair;
- every rejection reason gives `INVALID_REFRESH_TOKEN`;
- logout and logout-all delegate to `RefreshTokenService`.

## Manual test
1. Run in `dev`, then register and log in with `deviceLabel`. The response includes `refreshToken`.
2. `POST /auth/refresh` with it returns a new pair.
3. Refresh with the **old** token: 401 `INVALID_REFRESH_TOKEN`. The new one is now dead too.
4. `POST /auth/logout` with any token: 204.
