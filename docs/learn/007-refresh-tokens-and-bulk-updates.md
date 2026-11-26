# 007: Refresh tokens, rotation and bulk updates

## 1. The concept
An access token (step 005) lives 15 minutes, so a stolen one is useful only briefly. But
nobody wants to type a password every 15 minutes. So login also hands out a **refresh
token**: a long random string the app keeps in secure storage and trades for a new access
token when the old one expires.

**Rotation:** every trade also returns a *new* refresh token and kills the old one. It's
like a cinema ticket that gets torn at the door. If someone shows up with an
already-torn ticket, a copy exists, so we cancel the whole booking (the **family**) and
that device must log in again.

## 2. How it works under the hood
**Storage.** We store `SHA-256(token)`, never the token. On refresh we hash what the app
sent and look the row up by `token_hash` (a unique index). A database leak gives an
attacker only hashes, and you can't turn a hash back into the token.

**The atomic claim.** In `RefreshTokenRepository.revokeIfActive`:
```sql
UPDATE refresh_tokens SET revoked_at = now WHERE id = ? AND revoked_at IS NULL
```
Postgres locks the row while it updates it. If two requests race, the second waits; when
the first commits, the second re-checks `revoked_at IS NULL`, finds it false, and updates
**0 rows**. The return value (rows changed) tells us who won. No `@Version` and no
`SELECT ... FOR UPDATE` needed.

**`@Modifying` bulk updates.** A JPQL `update` runs directly in the database:
- it does **not** go through the persistence context, so entities already loaded (our
  `current` token) still show the old `revokedAt`;
- Hibernate's `@UpdateTimestamp` only runs when *Hibernate* writes an entity it tracks, so
  we set `t.updatedAt = :now` ourselves.

**Rollback rules.** Spring's `@Transactional` proxy (note 004) rolls back when a
`RuntimeException` leaves the method. On reuse we *want* the revoke saved. So `rotate()`
returns `RotationResult.Rejected(REUSED)`, and the proxy commits. `AuthService.refresh`,
which has no transaction, then throws the 401.

## 3. Request walk-through: reusing an old token
1. `POST /api/v1/auth/refresh` passes `RequestIdFilter`, then the security chain
   (`permitAll` for this path), then `AuthController.refresh`, where `@Valid` checks the body.
2. `AuthService.refresh` calls `refreshTokenService.rotate(raw)`. That call goes through
   the proxy, which opens a transaction.
3. `findByTokenHash(sha256(raw))` returns a row with `revoked_at` set, so `isRevoked()` is true.
4. `revokeFamily(familyId, now)` runs one `UPDATE`, which kills the newer token from the
   same family. Then a WARN log line, and `Rejected(REUSED)` is returned.
5. The proxy **commits**.
6. Back in `AuthService`, the result isn't `Rotated`, so it throws
   `UnauthorizedException(INVALID_REFRESH_TOKEN)`.
7. `GlobalExceptionHandler` turns that into a 401 Problem Details response.

## 4. Why this design
- **Why not long-lived JWTs?** A JWT can't be revoked before it expires; a DB row can.
- **Why not BCrypt for the hash?** BCrypt is slow to protect *guessable* passwords. 256
  random bits aren't guessable, and BCrypt's salt would stop us looking rows up by hash.
- **Why one error code for unknown, expired and reused?** The app reacts the same way
  ("log in again"), and the details only help an attacker. We still log the reason.
- **Why is logout public?** The refresh token proves the session, and the access token
  may already be expired when the user taps "Log out".
- **Why `user_id` as a plain UUID?** Auth doesn't need the User entity. A plain UUID
  avoids lazy loading and keeps features separate. The FK still enforces correctness.

## 5. Common mistakes
- **Throwing inside the transaction after a security write:** the rollback undoes it.
  Use a result type, `noRollbackFor`, or a separate transaction.
- **Read, check, then write in Java** (`if (!token.isRevoked()) token.revoke()`): two
  threads both pass the check. Put the check inside the `UPDATE`'s `WHERE`.
- **Trusting loaded entities after a bulk update:** they're stale. Re-query, or use
  `@Modifying(clearAutomatically = true)`.
- **Logging the token or its hash:** the hash is enough to find the row. Log ids only.
- **The app refreshing twice in parallel:** that counts as reuse and logs the user out.
  The app must run a single in-flight refresh (that's in the frontend hand-off).

## 6. Try it
1. In `rotate()`, replace `return new RotationResult.Rejected(REUSED)` with a `throw` of
   `UnauthorizedException`, then run
   `refresh_oldTokenAfterRotation_isReuse_andKillsTheWholeFamily`. It fails: the second
   token still works, because the revoke was rolled back.
2. Remove `t.updatedAt = :now` from `revokeFamily`, run a reuse, and check `updated_at` in
   the table. It didn't change.
3. In psql, run `select family_id, revoked_at is null as active from refresh_tokens order by created_at;`
   after a login, two refreshes and a reuse, and read the story in the rows.
