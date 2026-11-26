# 008: Profile, preferences, onboarding, change password, delete account

Part of the approved roadmap `prompts/ROADMAP-2026-11-26.md`.

## Goal
Let the user set their currency, timezone, week start, theme and name (onboarding and
settings), finish onboarding, change their password, and delete their account.

## Decisions
- **`PATCH /api/v1/users/me`**: partial update. A field that is missing or null stays
  unchanged. Email and password aren't editable here.
- **Saved by dirty checking**: the service loads the managed `User`, calls named methods
  (`rename`, `changeCurrency`, ...), and never calls `save`. Hibernate writes the UPDATE at commit.
- **Custom constraints** `@ValidCurrency` (ISO 4217 via `java.util.Currency`) and
  `@ValidTimezone` (IANA region ids only, so `+05:30` is rejected). Both allow null; that
  case is the job of `@NotNull`.
- **`WeekStart` enum** (MONDAY, SATURDAY, SUNDAY) replaces `DayOfWeek` in the entity and the
  DTOs. It stores the same strings, so no data migration is needed. An unknown value gives
  400 `MALFORMED_REQUEST`, and Swagger lists the 3 values.
- **Onboarding** is stored as `users.onboarded_at` (V4, nullable). `POST /users/me/onboarding/complete`:
  - needs a currency first, otherwise 422 `CURRENCY_REQUIRED`;
  - is idempotent (the first timestamp is kept).
  The response has `onboardingCompleted`.
- **Change password** is `POST /api/v1/auth/change-password`, in auth because it needs
  BCrypt and the sessions. It checks the current password, applies the 72-byte rule to the
  new one, saves the hash, revokes **all** refresh tokens, and returns a **new pair** for this device.
- **Delete account** is `POST /api/v1/auth/delete-account` with `{ password }` and returns
  204. `DELETE` with a body is avoided because some clients drop the body. The database
  cascades to everything the user owns.
- **Wrong current password gives 400 `CURRENT_PASSWORD_INCORRECT`, not 401.** The app treats 401 as
  "refresh the token", and that must not happen here.
- Changing the currency changes formatting only (AGENTS.md section 7).

## Files (all CLAUDE WRITES)
- `db/migration/V4__add_users_onboarded_at.sql`
- `user/entity/User.java` (+ onboardedAt and change methods), `user/entity/WeekStart.java`
- `common/validation/ValidCurrency`, `CurrencyCodeValidator`, `ValidTimezone`, `TimezoneValidator`
- `user/dto/UpdateProfileRequest.java`; `UserResponse` (+ onboardingCompleted, WeekStart); `UserMapper`
- `user/service/UserService.java` (updateProfile, completeOnboarding, getLoginCredentials, changePasswordHash, deleteUser)
- `user/controller/UserController.java` (PATCH, onboarding)
- `auth/dto/ChangePasswordRequest.java`, `DeleteAccountRequest.java`; `AuthService`; `AuthController`
- `common/exception/ErrorCode.java` (+ CURRENT_PASSWORD_INCORRECT, CURRENCY_REQUIRED)
- Tests: `ProfileIntegrationTest`, `ValidatorsTest`, plus additions to `UserServiceTest` and `AuthServiceTest`
- `docs/learn/008-dirty-checking-and-custom-validation.md`

## API contract
| Endpoint | Auth | Body | Success | Errors |
|---|---|---|---|---|
| `GET /api/v1/users/me` | Bearer | | 200 `UserResponse` | 404 `USER_NOT_FOUND` |
| `PATCH /api/v1/users/me` | Bearer | `{ name?, currency?, timezone?, weekStart?, theme? }` | 200 `UserResponse` | 400 `VALIDATION_FAILED`, 400 `MALFORMED_REQUEST` |
| `POST /api/v1/users/me/onboarding/complete` | Bearer | | 200 `UserResponse` | 422 `CURRENCY_REQUIRED` |
| `POST /api/v1/auth/change-password` | Bearer | `{ currentPassword, newPassword, deviceLabel? }` | 200 `TokenResponse` | 400 `VALIDATION_FAILED` / `PASSWORD_TOO_LONG` / `CURRENT_PASSWORD_INCORRECT` |
| `POST /api/v1/auth/delete-account` | Bearer | `{ password }` | 204 | 400 `VALIDATION_FAILED` / `CURRENT_PASSWORD_INCORRECT` |

`UserResponse = { id, name, email, emailVerified, currency, timezone, weekStart, theme, onboardingCompleted, createdAt }`

## Database
V4: `ALTER TABLE users ADD COLUMN onboarded_at TIMESTAMPTZ;` It's nullable and has no
default, so the change is instant and existing rows become NULL.

## Spring concepts this step teaches
- **Dirty checking**: managed entities, the persistence context snapshot, the UPDATE at flush and commit.
- **Custom Bean Validation constraints**: `@Constraint`, `ConstraintValidator`, and the message/groups/payload members.
- PATCH semantics with nullable record fields.

## Transactions
- `UserService.updateProfile`, `completeOnboarding`, `changePasswordHash` and `deleteUser`
  are each `@Transactional`. `getLoginCredentials` is `readOnly`.
- `AuthService.changePassword` has no transaction (two BCrypt calls). It runs three short
  transactions: save the hash, revoke all tokens, issue a new one.
- Known gap: if the revoke fails after the hash was saved, old sessions survive until
  logout-all. Accepted, so that no DB connection is held during BCrypt.

## Security
- Password confirmation is needed for change-password and delete-account.
- A password change logs out every device.
- Ids only ever come from the token. The A-vs-B test proves PATCH touches only the caller.

## Logging
- INFO: profile updated, onboarding completed, password changed, user deleted, password confirmation failed (userId only).
- Never: passwords, hashes, tokens.

## Tests (all passing; 146 total)
- `ProfileIntegrationTest` (12):
  - partial PATCH, checked in the DB row;
  - empty PATCH changes nothing;
  - 3 field errors with our messages;
  - FRIDAY gives `MALFORMED_REQUEST`;
  - A doesn't change B;
  - PATCH without a token gives 401;
  - the onboarding 422, then 200, then idempotent;
  - change password: new works, old fails, all devices are logged out;
  - wrong current password gives 400 and changes nothing;
  - a short new password gives a field error;
  - delete removes the user and sessions, and the old access token then gets 404 on `/me`;
  - delete with a wrong password keeps everything.
- `ValidatorsTest` (20): valid and invalid currencies and timezones.
- `UserServiceTest` (+6) and `AuthServiceTest` (+6).

## Manual test
1. Run in `dev`, then register and log in in Swagger, and click Authorize.
2. `POST /users/me/onboarding/complete` returns 422 `CURRENCY_REQUIRED`.
3. `PATCH /users/me` with `{ "currency": "inr", "timezone": "Asia/Kolkata" }` returns 200, with currency `INR`.
4. Onboarding complete returns 200, with `onboardingCompleted: true`.
5. Change password returns 200 with new tokens. Logging in with the old password returns 401.
6. Delete account returns 204. `GET /users/me` returns 404.
