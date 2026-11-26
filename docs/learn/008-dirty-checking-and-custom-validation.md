# 008: Saving without `save()` (dirty checking) and your own validation annotations

**In short:** two new ideas in this step.
1. **Dirty checking:** inside a `@Transactional` method, if you load an entity and change
   it, Hibernate saves the change **by itself** when the transaction ends. No `save()` call.
2. **Custom validation annotations:** we wrote `@ValidCurrency` and `@ValidTimezone`, our
   own versions of `@NotBlank` and `@Size`, so a bad currency or timezone is rejected with
   a clear field error, just like any other rule.

Plus the features built with them: edit profile (PATCH), finish onboarding, change
password, delete account.

---

## Part 1. The big picture

### What the app can do now
| The user wants to... | App calls | Answer |
|---|---|---|
| pick a currency, timezone and week start (onboarding or settings) | `PATCH /api/v1/users/me` | the updated profile |
| finish onboarding | `POST /api/v1/users/me/onboarding/complete` | profile with `onboardingCompleted: true` |
| change their password | `POST /api/v1/auth/change-password` | a **new token pair**; other devices are logged out |
| delete their account | `POST /api/v1/auth/delete-account` | 204, everything gone |

### What "PATCH" means
- `PUT` means "here is the **whole** object, replace it".
- `PATCH` means "here are **only the parts I want to change**".

So `{ "theme": "DARK" }` changes only the theme. Every other field stays as it was. In our
code, **a field that is missing or `null` means "don't touch it"**.

---

## Part 2. The files we touched (the map)

| # | File | What it does in one sentence |
|---|---|---|
| 1 | `db/migration/V4__add_users_onboarded_at.sql` | Adds the `onboarded_at` column. |
| 2 | `user/entity/User.java` | New field `onboardedAt` and "change" methods (`rename`, `changeCurrency`...). |
| 3 | `user/entity/WeekStart.java` | An enum with only the 3 allowed week starts. |
| 4 | `common/validation/ValidCurrency.java` + `CurrencyCodeValidator.java` | Our `@ValidCurrency` annotation and the check behind it. |
| 5 | `common/validation/ValidTimezone.java` + `TimezoneValidator.java` | Our `@ValidTimezone` annotation and its check. |
| 6 | `user/dto/UpdateProfileRequest.java` | The PATCH body. |
| 7 | `user/dto/UserResponse.java`, `user/mapper/UserMapper.java` | Now also returns `onboardingCompleted`. |
| 8 | `user/service/UserService.java` | updateProfile, completeOnboarding, changePasswordHash, deleteUser. |
| 9 | `user/controller/UserController.java` | `PATCH /me` and `POST /me/onboarding/complete`. |
| 10 | `auth/dto/ChangePasswordRequest.java`, `DeleteAccountRequest.java` | The two request bodies. |
| 11 | `auth/service/AuthService.java`, `auth/controller/AuthController.java` | Change password and delete account. |
| 12 | `common/exception/ErrorCode.java` | `CURRENT_PASSWORD_INCORRECT`, `CURRENCY_REQUIRED`. |

---

## Part 3. Concept 1: dirty checking (saving without `save()`)

### An analogy: a teacher with a photocopy
A teacher hands out your exam paper, but **first makes a photocopy**. When time's up, she
compares your paper with the copy and writes down **only what changed**. You never had to
say "I changed question 3". She finds out by comparing.

| Teacher | Hibernate |
|---|---|
| hands out the paper | `findById` loads the `User` |
| keeps a photocopy | keeps a **snapshot** of the loaded values in the **persistence context** |
| you write on the paper | your code calls `user.changeCurrency("INR")` |
| "time's up" | the `@Transactional` method returns, so the transaction commits |
| compares paper with copy | **dirty checking**: compares each field with the snapshot |
| writes down the changes | runs `UPDATE users SET currency = 'INR', updated_at = ... WHERE id = ?` |

"Dirty" just means "changed since it was loaded".

### The words
- **Persistence context**: Hibernate's notebook for **one transaction**. It holds every
  entity loaded in that transaction, plus a snapshot of how each one looked.
- **Managed entity**: an entity that is in the notebook. Hibernate watches it.
- **Flush**: the moment Hibernate turns the changes into SQL and sends them. It happens
  automatically just before **commit** (and before a query that might need the new data).

### The code: `UserService.updateProfile`
```java
@Transactional
public UserResponse updateProfile(UUID id, UpdateProfileRequest request) {
    User user = load(id);
    if (request.name() != null) {
        user.rename(request.name());
    }
    if (request.currency() != null) {
        user.changeCurrency(request.currency());
    }
    if (request.timezone() != null) {
        user.changeTimezone(request.timezone());
    }
    if (request.weekStart() != null) {
        user.changeWeekStart(request.weekStart());
    }
    if (request.theme() != null) {
        user.changeTheme(request.theme());
    }
    log.info("Profile updated userId={}", id);
    return UserMapper.toResponse(user);
}
```
Line by line:
1. `@Transactional`: Spring's proxy opens a transaction **and a persistence context** when
   the method starts (note 004).
2. `User user = load(id);`: `findById` runs a `SELECT`. The `User` is now **managed**, and
   Hibernate keeps its snapshot.
3. Each `if (... != null)`: this is the PATCH rule. Only fields that were sent are changed.
4. `user.changeCurrency(...)`: only changes a Java field in memory. **No SQL yet.**
5. `UserMapper.toResponse(user)`: the object already has the new values, so the response shows them.
6. The method returns, the proxy commits, Hibernate **flushes**: it compares the object with
   the snapshot, sees `currency` and `theme` changed, and sends **one** `UPDATE`.
   `@UpdateTimestamp` sets `updated_at` at that moment.
7. If nothing changed (an empty `{}` PATCH), Hibernate sends **no** UPDATE at all.

**There is no `userRepository.save(user)`, and that's on purpose.** For a managed entity,
`save` would do nothing extra. The test proves it:
```java
verify(userRepository, never()).save(any());
```
and `ProfileIntegrationTest.patch_changesOnlySentFields_andIsReallySaved` reads the table
row directly to prove the UPDATE really happened.

### Why named methods instead of setters?
`User` has no `setCurrency`. It has `changeCurrency`, `rename`, `completeOnboarding`:
```java
public void rename(String name) {
    this.name = name;
}

/** Safe to call twice: the first time is kept. */
public void completeOnboarding(Instant now) {
    if (this.onboardedAt == null) {
        this.onboardedAt = now;
    }
}
```
- The service reads like the business rule: `user.completeOnboarding(now)` instead of `user.setOnboardedAt(now)`.
- A rule can live **inside** the method. `completeOnboarding` keeps the first time even if
  it's called twice. With a setter, every caller would have to remember that.
- Fields nobody should change (`email`, `id`) simply have no method.

### The same idea elsewhere in this step
```java
@Transactional
public void changePasswordHash(UUID id, String newPasswordHash) {
    Objects.requireNonNull(newPasswordHash, "newPasswordHash");
    load(id).changePasswordHash(newPasswordHash);   // no save: dirty checking
    log.info("Password changed userId={}", id);
}
```
- `Objects.requireNonNull(...)`: if a bug passes `null`, it fails right here with a clear
  message, instead of later in the database.

```java
@Transactional
public void deleteUser(UUID id) {
    userRepository.delete(load(id));
    log.info("User deleted userId={}", id);
}
```
- Deleting **does** need a call (`delete`). Hibernate can't guess you want a row gone.
- The database then removes everything the user owns (`ON DELETE CASCADE`, for example
  `refresh_tokens` from step 007). Future tables (accounts, transactions...) will cascade the same way.

```java
private User load(UUID id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new NotFoundException(ErrorCode.USER_NOT_FOUND, "User not found."));
}
```
- One private helper for "find, or 404", so every method behaves the same.
- `orElseThrow(() -> ...)`: if the `Optional` is empty, create and throw this exception.
- A **private** method has no proxy and no transaction of its own. That's fine here: it is
  always called from inside a `@Transactional` method, so it uses that one.

### When dirty checking does NOT happen (traps)
1. **No transaction.** Without `@Transactional`, the entity isn't managed after `findById`
   returns, so your changes are never saved and you get no error.
2. **Self-invocation.** Calling a `@Transactional` method of the **same** class skips the
   proxy (note 004), so there may be no transaction.
3. **`readOnly = true`.** Hibernate skips dirty checking on purpose (faster). Changes in a
   read-only method are thrown away.
4. **`@Modifying` bulk updates** (note 007) are the other way round: they skip the
   notebook entirely.

---

## Part 4. Concept 2: our own validation annotations

### The problem
`currency` must be a real code like `INR`, not `RUPEE`. `timezone` must be a real zone like
`Asia/Kolkata`, not `+05:30`. There is no built-in `@NotBlank`-style annotation for that.

We could check it in the service and throw an exception. But then the error would look
different from all other input errors (no `errors[]` with the field name). So we write
**our own annotation**, and the result looks exactly like `@Size` or `@Email`:
```json
{
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "currency", "message": "Currency must be a valid 3-letter ISO 4217 code, like INR or USD." },
    { "field": "timezone", "message": "Timezone must be an IANA name, like Asia/Kolkata or Europe/London." }
  ]
}
```

### An analogy
Bean Validation is like airport security with a set of standard checks (`@NotBlank` =
"has a passport", `@Size` = "bag not too big"). A custom annotation is like **adding your
own check** to the line ("ticket must be for a real flight"). Once registered, it runs in
the same line, and failures are reported the same way.

A custom constraint always has **two parts**:
1. the **annotation** (the label you stick on a field), and
2. the **validator** (the class that does the check).

### Part 1: the annotation, `ValidCurrency.java`
```java
@Documented
@Constraint(validatedBy = CurrencyCodeValidator.class)
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {

    String message() default "Currency must be a valid 3-letter ISO 4217 code, like INR or USD.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
```
Line by line:
- `public @interface ValidCurrency`: `@interface` (with the `@`) is how Java **declares a
  new annotation**. After this, `@ValidCurrency` can be written on fields.
- `@Constraint(validatedBy = CurrencyCodeValidator.class)`: **the key line.** It tells Bean
  Validation: "this is a validation rule, and `CurrencyCodeValidator` does the check".
- `@Target({ FIELD, PARAMETER, RECORD_COMPONENT })`: **where** you may put it. `RECORD_COMPONENT`
  is needed because our DTOs are records.
- `@Retention(RetentionPolicy.RUNTIME)`: keep the annotation in the compiled class, so it
  can be found **while the app runs**. Without this, Java throws it away when compiling, and
  the rule silently never runs.
- `@Documented`: show the annotation in generated Javadoc. Cosmetic.
- `String message() default "..."`: the error text that goes to the app in `errors[]`. You
  can override it per field: `@ValidCurrency(message = "Pick a currency")`.
- `groups()` and `payload()`: **required by the Bean Validation rules** for every
  constraint, even if you don't use them. `groups` lets you run some rules only in some
  situations; `payload` attaches extra data. We use neither, so both default to empty. Just
  copy these two lines into every custom annotation.

### Part 2: the validator, `CurrencyCodeValidator.java`
```java
public class CurrencyCodeValidator implements ConstraintValidator<ValidCurrency, String> {

    private static final Set<String> CODES = Currency.getAvailableCurrencies().stream()
        .map(Currency::getCurrencyCode)
        .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || CODES.contains(value);
    }

}
```
Line by line:
- `implements ConstraintValidator<ValidCurrency, String>`: "I check the `@ValidCurrency`
  annotation, on values of type `String`".
- `CODES`: Java knows every ISO 4217 currency (about 300). We collect their codes **once**
  into a `Set`, so each check is a fast lookup.
  - `.stream()`, `.map(Currency::getCurrencyCode)`, `.collect(...)`: "take each currency,
    get its code, put all codes in a set".
  - `toUnmodifiableSet()`: nobody can change the set later.
- `isValid(...)`: Bean Validation calls this for every annotated field. Return `true` = OK,
  `false` = add an error with the annotation's `message`.
- `value == null || ...`: **null counts as valid.** This is the convention for every
  constraint except `@NotNull`. In a PATCH, null means "not sent", which is allowed. If a
  field must be present, add `@NotNull` too. Each annotation checks one thing.
- `context`: lets you build fancier messages. We don't need it.

`TimezoneValidator` is the same shape:
```java
private static final Set<String> ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || ZONES.contains(value);
}
```
- `ZoneId.getAvailableZoneIds()`: every **named** zone Java knows ("Asia/Kolkata",
  "Europe/London", "UTC", ...).
- **Why not `ZoneId.of(value)`?** It also accepts fixed offsets like `+05:30`. An offset
  ignores daylight saving, so for a user in London, "this month" would be an hour wrong for
  half the year. Only named zones are allowed.

### Part 3: using it, `UpdateProfileRequest.java`
```java
public record UpdateProfileRequest(
        @Size(min = 1, max = 100, message = "Name must be 1 to 100 characters.")
        @Schema(example = "Ravi Kumar", nullable = true)
        String name,

        @ValidCurrency
        @Schema(description = "ISO 4217 code. Changes formatting only; amounts are never converted.",
                example = "INR", nullable = true)
        String currency,

        @ValidTimezone
        @Schema(description = "IANA timezone. Decides what 'today' and 'this month' mean.", example = "Asia/Kolkata",
                nullable = true)
        String timezone,

        @Schema(nullable = true) WeekStart weekStart,

        @Schema(nullable = true) ThemePreference theme) {
```
- `@ValidCurrency` / `@ValidTimezone`: our new rules, used exactly like `@Size`.
- **No `@NotNull`/`@NotBlank` anywhere**, because PATCH fields are all optional.
- `@Size(min = 1, ...)` on `name`: if a name **is** sent, it can't be empty.
- `@Schema(...)`: docs only, as always (note 006).

```java
public UpdateProfileRequest {
    name = name == null ? null : name.strip();
    currency = currency == null ? null : currency.strip().toUpperCase(Locale.ROOT);
    timezone = timezone == null ? null : timezone.strip();
}
```
- The **compact constructor** runs when Jackson builds the record, **before** validation.
- `" Ravi "` becomes `"Ravi"`.
- `"   "` becomes `""`, which then **fails** `@Size(min = 1)`. A blank name is rejected, not saved.
- `"inr"` becomes `"INR"`. We're friendly about case, but strict about the code itself.
- `Locale.ROOT`: uppercasing that doesn't depend on the server's language (note 004's Turkish "I" trap).

### What happens on a bad request, step by step
`PATCH /api/v1/users/me` with `{ "currency": "RUPEE" }`:
1. Jackson builds `UpdateProfileRequest`, and the compact constructor makes `"RUPEE"` (already uppercase).
2. The controller parameter has `@Valid`, so Bean Validation checks every annotation on the record.
3. It sees `@ValidCurrency` on `currency`, looks at `@Constraint(validatedBy = CurrencyCodeValidator.class)`,
   and calls `isValid("RUPEE", ...)`, which returns `false`.
4. It records an error: field `currency`, message = the annotation's `message()`.
5. Spring throws `MethodArgumentNotValidException`. **The service never runs.**
6. `GlobalExceptionHandler` (note 002) turns it into 400 `VALIDATION_FAILED` with `errors[]`.

### Another tool: a dedicated enum, `WeekStart`
```java
public enum WeekStart {
    MONDAY,
    SATURDAY,
    SUNDAY;

    public DayOfWeek toDayOfWeek() {
        return DayOfWeek.valueOf(name());
    }
}
```
- Before, the entity used Java's `DayOfWeek`, which has **7** values. The database only allows
  3 (the `CHECK` in V2). Sending `"FRIDAY"` would have passed Java and then crashed in the
  database as a 500.
- With our own enum of exactly 3 values, Jackson **can't** build `FRIDAY`, and the answer is
  400 `MALFORMED_REQUEST`. Swagger also shows the app developer the 3 allowed values.
- It's stored by name (`@Enumerated(EnumType.STRING)`), the same strings as before, so no
  data had to change.
- `toDayOfWeek()`: for date math later (budgets, "start of this week").

**Which tool when?**
| The value is... | Use |
|---|---|
| one of a small fixed list | an `enum` (Jackson rejects others) |
| a string with rules a regex can express | `@Pattern` |
| a string checked against a real list or logic | a **custom constraint** (`@ValidCurrency`) |
| a rule that needs the database (for example "email not taken") | a check in the **service** |

---

## Part 5. Change password and delete account

### `AuthService.changePassword`
```java
public TokenResponse changePassword(UUID userId, ChangePasswordRequest request) {
    requireCurrentPassword(userId, request.currentPassword());
    requireBcryptSafeLength(request.newPassword());

    userService.changePasswordHash(userId, passwordEncoder.encode(request.newPassword()));
    refreshTokenService.revokeAllForUser(userId);
    return tokens(userId, refreshTokenService.issueForLogin(userId, request.deviceLabel()));
}
```
Step by step:
1. Check the **current** password (if someone grabs an unlocked phone, they can't change it).
2. Check the new one fits BCrypt's 72 bytes (same rule as register, now shared in one helper).
3. Hash it and save it. `changePasswordHash` is its own short transaction, using dirty checking.
4. **Log out every device.** If the password was changed because it leaked, whoever had it is out now.
5. Start a **new session for this device** and return the tokens, so the user who just
   changed their password stays logged in.

No `@Transactional` here, because BCrypt is slow (about 250 ms, twice). Each step is its own short transaction.

### `requireCurrentPassword`
```java
private void requireCurrentPassword(UUID userId, String password) {
    String storedHash = userService.getLoginCredentials(userId).passwordHash();
    boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    if (storedHash == null || tooLong || !passwordEncoder.matches(password, storedHash)) {
        log.info("Password confirmation failed userId={}", userId);
        throw new BadRequestException(ErrorCode.CURRENT_PASSWORD_INCORRECT, "Current password is incorrect.");
    }
}
```
- `getLoginCredentials(userId)`: asks the user feature for **id + hash only**, never the
  entity (features talk through services).
- Three ways to fail, one answer: no password set, too long, or wrong.
- `passwordEncoder.matches(raw, hash)`: BCrypt hashes the typed password with the salt
  stored inside the hash, and compares.

**Why 400 and not 401?** In ErrorCode:
```java
// Not 401: the app treats every 401 as "token expired, refresh". A wrong current
// password must not trigger that.
CURRENT_PASSWORD_INCORRECT(HttpStatus.BAD_REQUEST, "Current password incorrect"),
```
The app's network layer sees a 401 and automatically tries `/auth/refresh`. A typo in
"current password" must show an error under the field, not start a token refresh.

### Delete account
```java
public void deleteAccount(UUID userId, DeleteAccountRequest request) {
    requireCurrentPassword(userId, request.password());
    userService.deleteUser(userId);
}
```
- Confirm the password, then delete. The database cascade removes the rest.
- **Why `POST /auth/delete-account` and not `DELETE /users/me`?** We need a body (the
  password), and some HTTP clients and proxies drop the body of a `DELETE`. `POST` is reliable.
- After deleting, the old access token still has a **valid signature** for up to 15 minutes,
  but `/users/me` answers 404 `USER_NOT_FOUND`, because the user is gone. The test checks this.

### Finish onboarding: `UserService.completeOnboarding`
```java
@Transactional
public UserResponse completeOnboarding(UUID id) {
    User user = load(id);
    if (user.getCurrency() == null) {
        throw new BusinessRuleException(ErrorCode.CURRENCY_REQUIRED,
                "Choose a currency before finishing onboarding.");
    }
    user.completeOnboarding(Instant.now());
    log.info("Onboarding completed userId={}", id);
    return UserMapper.toResponse(user);
}
```
- A **business rule**: every amount on every screen is shown in the user's currency, so
  onboarding can't finish without one. The answer is 422 `CURRENCY_REQUIRED` ("I understood
  you, but the rules don't allow it now").
- If it throws, the transaction rolls back. Here nothing had changed yet anyway.
- `completeOnboarding(Instant.now())` plus dirty checking makes one `UPDATE users SET onboarded_at = ...`.

---

## Part 6. The controller annotations (quick recap)

```java
@RestController
@RequestMapping("/api/v1/users/me")
public class UserController {

    @GetMapping                       // GET   /api/v1/users/me
    public UserResponse me() { ... }

    @PatchMapping                     // PATCH /api/v1/users/me
    public UserResponse update(@Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(currentUser.id(), request);
    }

    @PostMapping("/onboarding/complete")   // POST /api/v1/users/me/onboarding/complete
    public UserResponse completeOnboarding() { ... }
}
```
- The base path is now `/api/v1/users/me`, because every endpoint here is about "me".
- `@PatchMapping`: Spring's annotation for HTTP PATCH (like `@GetMapping` / `@PostMapping`).
- `@Valid`: runs the rules, including our custom ones.
- `currentUser.id()`: the id **only** comes from the token. There is no id in the URL, so
  nobody can edit someone else's profile. `patch_onlyChangesTheCaller_notOtherUsers` proves it.
- The Swagger annotations (`@Operation`, `@ApiResponse`) are docs only (note 006).

---

## Part 7. Common mistakes

1. **Calling `save()` "to be safe"** on a managed entity. It's harmless but misleading.
   Worse is **forgetting `@Transactional`** and expecting changes to be saved: they are
   silently lost.
2. **Changing an entity inside a `readOnly = true` method.** The change is ignored.
3. **Forgetting `@Retention(RUNTIME)`** on a custom annotation. The rule never runs, and you get no error.
4. **Returning `false` for null in a custom validator.** Every optional field becomes
   required. Leave null to `@NotNull`.
5. **Using `ZoneId.of` to validate timezones.** It accepts `+05:30`.
6. **Using 401 for "wrong current password".** The app would try to refresh the token instead of showing the error.
7. **Treating PATCH like PUT** (setting every field from the request). Fields the app
   didn't send would be wiped to null.

---

## Part 8. Try it yourself

1. **Watch the SQL.** In `application-dev.properties`, temporarily add
   `logging.level.org.hibernate.SQL=DEBUG`. PATCH `{ "theme": "DARK" }` in Swagger and find
   the `select` and then the single `update` in the log. Now PATCH `{}` and see that there's no `update` at all.
2. **Break dirty checking.** Remove `@Transactional` from `updateProfile`, run
   `ProfileIntegrationTest.patch_changesOnlySentFields_andIsReallySaved`, and watch it fail:
   the response looks right, but the database row didn't change. Put it back.
3. **Write your own constraint.** Create `@ValidColorHex` (for example `#1A2B3C`) with a
   validator using `value == null || value.matches("^#[0-9A-Fa-f]{6}$")`. We'll need it for
   account colors in step 009.
4. **See the enum guard.** PATCH `{ "weekStart": "FRIDAY" }` and get 400 `MALFORMED_REQUEST`.

---

## Remember these
- Inside `@Transactional`: load, change, done. Hibernate saves changed managed entities at commit (dirty checking).
- No transaction or `readOnly`, and changes are not saved.
- A custom constraint = an `@interface` with `@Constraint(validatedBy = ...)` + a `ConstraintValidator` class.
- Custom validators return `true` for null; `@NotNull` decides "required".
- Small fixed lists are enums. Real-world lists (currencies, zones) are custom constraints. Database facts are checked in the service.
- PATCH: null or missing means "don't change".
