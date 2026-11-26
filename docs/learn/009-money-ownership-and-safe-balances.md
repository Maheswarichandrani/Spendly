# 009: Accounts — money done right, ownership, and balances that never lose updates

**In short:** three big ideas in this step.
1. **Money** is `BigDecimal` in Java, `NUMERIC(19,4)` in the database, and a **string** in JSON. Never `double`.
2. **Ownership:** the account id comes from the URL, but the owner always comes from the
   token. Every query asks for **both**, so someone else's account gives 404.
3. **Safe balances:** Hibernate is never allowed to write the balance column. Only one
   atomic SQL statement changes it, so two changes at the same moment can't erase each other.

Read top to bottom. Each part builds on the one before.

---

## Part 1. The big picture

### What the app can do now
| The user wants to... | App calls |
|---|---|
| see their accounts | `GET /api/v1/accounts` |
| see net worth | `GET /api/v1/accounts/summary` |
| add an account | `POST /api/v1/accounts` |
| edit one | `PATCH /api/v1/accounts/{id}` |
| hide an old one | `POST /api/v1/accounts/{id}/archive` (and `/unarchive`) |
| drag to reorder | `PUT /api/v1/accounts/order` |
| delete one | `DELETE /api/v1/accounts/{id}` |

### What an account looks like in JSON
```json
{
  "id": "01a2176f-8945-7a2c-a002-f3a68443acab",
  "name": "HDFC",
  "type": "BANK",
  "icon": "bank",
  "color": "#2563EB",
  "openingBalance": "25000.50",
  "currentBalance": "25000.50",
  "liability": false,
  "includeInTotal": true,
  "archived": false,
  "sortOrder": 0,
  "creditLimit": null, "availableCredit": null, "amountOwed": null,
  "statementDay": null, "dueDay": null,
  "createdAt": "2026-11-26T10:38:35.334635Z"
}
```
Look at the money: `"25000.50"` is in **quotes**, so it's a string, not a number. Part 4 explains why.

---

## Part 2. The files (the map)

| # | File | What it does in one sentence |
|---|---|---|
| 1 | `db/migration/V5__create_accounts.sql` | Creates the `accounts` table with its rules and indexes. |
| 2 | `account/entity/AccountType.java` | The 8 kinds of account, each with a default icon; only CREDIT_CARD is a liability. |
| 3 | `account/entity/Account.java` | The Java object for one row, with the **balance guard**. |
| 4 | `account/repository/AccountRepository.java` | Queries, all with `userId`, plus the atomic balance update and the net-worth sum. |
| 5 | `account/repository/AccountTotals.java` | A small record the net-worth query fills in. |
| 6 | `account/dto/*` | Request and response shapes. |
| 7 | `account/mapper/AccountMapper.java` | Entity to response, plus "amount owed" and "available credit". |
| 8 | `account/service/AccountService.java` | All the rules. |
| 9 | `account/controller/AccountController.java` | The 9 endpoints. |
| 10 | `common/money/Money.java` | Money helpers (display format, zero check). |
| 11 | `common/config/JacksonConfig.java` | "Write every BigDecimal as a JSON string." |
| 12 | `common/config/OpenApiConfig.java` | "Tell Swagger every BigDecimal is a string." |

---

## Part 3. Why money is never `double`

Try this in Java:
```java
System.out.println(0.1 + 0.2);            // 0.30000000000000004
System.out.println(new BigDecimal("0.1").add(new BigDecimal("0.2")));   // 0.3
```
- `double` stores numbers in **binary**. Just as 1/3 can't be written exactly in decimal
  (0.3333...), 0.1 can't be written exactly in binary. Tiny errors appear, and in a money
  app they add up across thousands of transactions.
- `BigDecimal` stores the **decimal digits exactly**, the way you write them on paper.

The database does the same with `NUMERIC(19,4)`:
- `19` = total digits allowed, `4` = digits after the point, so up to 15 before it:
  `999,999,999,999,999.9999`.
- Why 4 decimals when rupees have 2? Some currencies use 3 (Bahraini dinar), and 4 leaves
  room for exact intermediate math. One rule for every currency.

**Two BigDecimal rules to remember:**
1. **Always create it from a String**: `new BigDecimal("0.1")`. `new BigDecimal(0.1)`
   (from a double) carries the double's error into it: `0.1000000000000000055511...`.
2. **Compare with `compareTo`, not `equals`**:
   ```java
   new BigDecimal("2.0").equals(new BigDecimal("2.00"))      // false! (different scale)
   new BigDecimal("2.0").compareTo(new BigDecimal("2.00"))   // 0 = equal
   ```
   That's why `Money.isZero` uses `compareTo`, and why the update code says
   `request.openingBalance().compareTo(account.getOpeningBalance()) != 0`.

---

## Part 4. Money in JSON: a string, set up once for the whole app

### The problem
JSON has one number type. Many JSON readers (JavaScript, and also Dart's `jsonDecode`) turn
every number into a 64-bit `double`. So even if **we** send `1250.10` perfectly, the app might
read `1250.0999999999999`.

### The fix: send money as a string
`"1250.10"` is text. Every JSON reader keeps text exactly as it is, and the app converts it
with a **decimal** type (`Decimal` in Dart). No digit is ever lost.

### The code: `JacksonConfig.java`
```java
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer moneyAsString() {
        return builder -> builder
            .withConfigOverride(BigDecimal.class,
                    override -> override.setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING)))
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN);
    }

}
```
Line by line:
- **Jackson** is the library that turns Java objects into JSON and back (Spring uses it for
  every `@RequestBody` and every returned DTO). Spring Boot 4 uses **Jackson 3**, whose
  packages start with `tools.jackson`.
- `@Bean JsonMapperBuilderCustomizer`: Spring Boot builds Jackson's `JsonMapper` for us.
  Before it finishes, it calls every `JsonMapperBuilderCustomizer` bean, so **this is our hook
  to change Jackson's settings** without replacing Boot's whole setup.
- `builder -> builder...`: a lambda that receives the builder and changes it.
- `.withConfigOverride(BigDecimal.class, override -> ...)`: "for the type `BigDecimal`, and
  only that type, change this setting". Every other number stays a number.
- `setFormat(JsonFormat.Value.forShape(JsonFormat.Shape.STRING))`: "write it in the
  **shape** of a string". It's the same as putting `@JsonFormat(shape = STRING)` on every
  BigDecimal field, but done once, so nobody can forget it on a new DTO.
- `.enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)`: BigDecimal can print itself
  in scientific notation (`1E+3`). This makes it always print plain digits (`1000`).

**Reading** still accepts both forms: `"openingBalance": "25000.5"` and
`"openingBalance": 0.1` both work. When Jackson reads `0.1` straight into a `BigDecimal`,
it uses the exact text `0.1`, never a double in between. The test
`create_acceptsMoneyAsStringOrNumber_andKeepsExactDecimals` proves this.

### Telling Swagger the same thing: `OpenApiConfig.java`
```java
static {
    SpringDocUtils.getConfig().replaceWithSchema(BigDecimal.class,
            new StringSchema().format("decimal").example("1250.50"));
}
```
- A `static { ... }` block runs **once**, when Java first loads the class.
- `replaceWithSchema(BigDecimal.class, ...)`: "wherever a DTO has a BigDecimal, document it
  as a string with format `decimal`". Otherwise Swagger would say "number", and your friend
  would be confused when a string arrives.

### Looking nice: `Money.forDisplay`
The database gives back `25000.5000` (always 4 decimals). Sending that is correct but ugly.
```java
public static BigDecimal forDisplay(BigDecimal value) {
    if (value == null) {
        return null;
    }
    BigDecimal stripped = value.stripTrailingZeros();
    return stripped.scale() < 2 ? stripped.setScale(2) : stripped;
}
```
- `stripTrailingZeros()`: `25000.5000` becomes `25000.5`, and `1000.0000` becomes `1E+3` (scale −3, see below).
- `scale()`: how many digits after the point. `25000.5` has scale 1.
- `setScale(2)`: pad to 2 decimals. `25000.5` becomes `25000.50`.
- `12.3456` keeps all 4 digits: real precision is never cut off.

| Database | `forDisplay` | JSON |
|---|---|---|
| 25000.5000 | 25000.50 | `"25000.50"` |
| 0.0000 | 0.00 | `"0.00"` |
| 12.3456 | 12.3456 | `"12.3456"` |
| -5000.0000 | -5000.00 | `"-5000.00"` |

(`1000.0000` → `stripTrailingZeros` gives `1E+3`, which has scale −3 < 2, so `setScale(2)`
gives `1000.00`. `MoneyTest` covers this case.)

### Validating money input
```java
@Digits(integer = 15, fraction = 4, message = "Opening balance must have at most 15 digits and 4 decimals.")
BigDecimal openingBalance,
```
- `@Digits(integer = 15, fraction = 4)`: at most 15 digits before the point and 4 after,
  the same as the column. `1.23456` (5 decimals) gets a 400 with this exact message, before
  it could ever reach the database.

```java
@DecimalMin(value = "0", message = "Credit limit cannot be negative.")
BigDecimal creditLimit,
```
- `@DecimalMin("0")`: the value must be ≥ 0. The limit is a **String** (`"0"`), for the same exactness reason.

---

## Part 5. Ownership: `@PathVariable` and `findByIdAndUserId`

### The danger
URLs now contain ids: `GET /api/v1/accounts/01a2176f-...`. An attacker can type **any** id
into the URL. If our code just did `findById(id)`, they could read anyone's account.

### The rule (AGENTS.md section 5)
The id in the URL says **which** account. The token says **who is asking**. We always search
with **both**.

### The controller
```java
@GetMapping("/{id}")
public AccountResponse get(@PathVariable UUID id) {
    return accountService.get(currentUser.id(), id);
}
```
- `@GetMapping("/{id}")`: `{id}` is a **placeholder** in the URL.
- `@PathVariable UUID id`: "take the `{id}` part of the URL and give it to me as a UUID".
  Spring converts the text to a `UUID` itself. Not a valid UUID? Then 400 `INVALID_PARAMETER`
  (test `badUuidInPath_returns400`).
- `currentUser.id()`: who is asking, from the verified token (step 005).

### The repository
```java
Optional<Account> findByIdAndUserId(UUID id, UUID userId);
```
- A derived query: `select ... from accounts where id = ? and user_id = ?`.
- Your account: found. Someone else's: **empty**, exactly as if it didn't exist.

### The service
```java
private Account load(UUID userId, UUID accountId) {
    return repository.findByIdAndUserId(accountId, userId).orElseThrow(AccountService::notFound);
}
```
- Every method uses this one helper, so there's no way to forget the userId.
- `AccountService::notFound` is a method reference to the method that builds the 404 exception.

**Why 404 and not 403 ("forbidden")?** A 403 would say "this id exists, but it's not
yours". Then an attacker could test ids and learn which ones exist. 404 says nothing.

The test `otherUsersAccount_looksLikeItDoesNotExist` tries every endpoint as user B on user
A's account (get, edit, edit balance, archive, delete, reorder), checks that each one gives
404, and then checks that A's account is untouched.

---

## Part 6. The big trap: Hibernate can erase a balance change

### The trap, as a story
By default, when Hibernate saves a changed entity, its UPDATE writes **every column**, not
just the one you changed.

```
Time  Request A (rename "Wallet")             Request B (spend 100)           DB balance
----  -------------------------------------   -----------------------------   ----------
 1    loads account: balance 1000 in memory                                      1000
 2                                            balance = balance - 100, commit      900
 3    rename, commit:
      UPDATE accounts SET name='Daily wallet',
             current_balance=1000, ...         ← old value from memory!          1000 ❌
```
The expense from B is **gone**. Nobody saw an error. This is called a **lost update**.

### The guard: `updatable = false`
```java
// Written on INSERT, never on UPDATE (see the class comment).
@Column(name = "current_balance", nullable = false, precision = 19, scale = 4, updatable = false)
private BigDecimal currentBalance;
```
- `updatable = false`: Hibernate **leaves this column out of every UPDATE** it writes.
  It's still written on INSERT (the starting balance).
- Now step 3 becomes `UPDATE accounts SET name='Daily wallet', ... WHERE id=?`, with no balance,
  so B's 900 survives.
- `precision = 19, scale = 4`: describes `NUMERIC(19,4)` to Hibernate. With
  `ddl-auto=validate`, Hibernate checks at startup that the column really has a matching type.

### So how DOES the balance change? One atomic statement
```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
        update Account a set a.currentBalance = a.currentBalance + :delta, a.updatedAt = :now
        where a.id = :id and a.userId = :userId""")
int adjustBalance(@Param("id") UUID id, @Param("userId") UUID userId, @Param("delta") BigDecimal delta,
        @Param("now") Instant now);
```
- `current_balance = current_balance + :delta`: the **database** reads the current value and
  adds to it, in one step. Two of these at the same time? Postgres runs them one after the
  other on that row, and **both** count. Nothing is read into Java and written back.
- `and a.userId = :userId`: ownership, even here.
- `a.updatedAt = :now`: bulk updates skip `@UpdateTimestamp` (note 007), so we set it ourselves.
- `flushAutomatically = true`: **before** running, send any changes still waiting in this
  transaction (for example a new opening balance set by dirty checking). Otherwise the order of
  SQL statements could surprise you.
- `clearAutomatically = true`: **after** running, empty the persistence context (Hibernate's
  notebook, note 008). The `Account` object we had in memory still shows the **old** balance,
  so we throw it away, and the next `find` reads the real row from the database.
- `int` return: rows changed (1, or 0 if it's not this user's account).

From step 011 on, **every** income, expense and transfer will call this method.

### Using it: editing the opening balance
```java
if (request.openingBalance() != null && request.openingBalance().compareTo(account.getOpeningBalance()) != 0) {
    BigDecimal delta = request.openingBalance().subtract(account.getOpeningBalance());
    account.changeOpeningBalance(request.openingBalance());
    repository.adjustBalance(accountId, userId, delta, Instant.now());
    account = load(userId, accountId);
}
```
1. Only if a new opening balance was sent **and** it's really different (`compareTo`, not `equals`!).
2. `delta` = new − old. Opening 1000 → 1500 gives delta +500. The whole history shifts up
   by 500, so the current balance must too.
3. `changeOpeningBalance(...)`: changes the `opening_balance` column (normal dirty checking).
4. `adjustBalance(...)`: flushes step 3, then moves the balance **atomically**, then clears.
5. `load(...)` again: the fresh row, with the new balance, for the response.

### Proof that it works (and that the test means something)
`AccountBalanceConcurrencyTest` plays exactly the story above:
```java
transactionTemplate.executeWithoutResult(status -> {
    Account account = repository.findByIdAndUserId(accountId, userId).orElseThrow();   // balance 1000

    CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(
            other -> repository.adjustBalance(accountId, userId, new BigDecimal("-100.00"), Instant.now())))
        .join();                                    // another thread spends 100 and commits

    account.rename("Daily wallet");               // our transaction commits after this
});
// then: name is "Daily wallet" AND balance is 900.00
```
- `TransactionTemplate`: runs a lambda inside a transaction, like `@Transactional` but in
  code. Handy in tests.
- `CompletableFuture.runAsync(...)`: runs on **another thread**, which means another database
  connection and another transaction, like a second phone using the app.
- `.join()`: wait until that thread is done (committed).

**I removed `updatable = false` on purpose and ran the test.** It failed:
`expected: 900.00 but was: 1000.0000`. That's the lost update, caught. Then I put the guard back.

---

## Part 7. Net worth: let the database add up

AGENTS.md section 9: never load every row into Java just to sum it. Ask the database.

```java
@Query("""
        select new com.sm.spendly.account.repository.AccountTotals(
            coalesce(sum(case when a.type <> :liability then a.currentBalance end), cast(0 as BigDecimal)),
            coalesce(sum(case when a.type = :liability then -a.currentBalance end), cast(0 as BigDecimal)),
            count(a))
        from Account a
        where a.userId = :userId and a.archivedAt is null and a.includeInTotal = true""")
AccountTotals totals(@Param("userId") UUID userId, @Param("liability") AccountType liability);
```
Piece by piece:
- `select new com.sm....AccountTotals(...)`: a **DTO projection**. Instead of returning
  entities, the query builds an `AccountTotals` record directly from the result row. The
  full class name is required.
- `sum(case when a.type <> :liability then a.currentBalance end)`: "add up the balances of
  accounts that are **not** credit cards". `case when ... then X end` gives X for matching
  rows and nothing (null) for the others, and `sum` skips nulls.
- `-a.currentBalance` for cards: a card at `-300` means 300 owed, so we report liabilities
  as a positive `300`.
- `coalesce(x, cast(0 as BigDecimal))`: if there are **no** matching rows, `sum` gives
  `null`. `coalesce` means "use the first non-null value", so we get 0 instead. The `cast` makes
  the 0 a BigDecimal (a plain `0` would be an Integer, and the record wants BigDecimal).
- `:liability` is passed as `AccountType.CREDIT_CARD`, a parameter instead of a hard-coded string.
- `where ... archivedAt is null and includeInTotal = true`: only active accounts the user wants counted.

The service then does: `netWorth = assets − liabilities`.

Test `summary_countsOnlyActiveIncludedAccounts_andTreatsCardsAsLiabilities`:
| Account | Balance | Counted? |
|---|---|---|
| Bank | 1000 | ✅ asset |
| Cash | 500.50 | ✅ asset |
| Card | −300 | ✅ liability 300 |
| Hidden wallet | 99 | ❌ includeInTotal = false |
| Archived bank | 1000 | ❌ archived |

Result: assets `1500.50`, liabilities `300.00`, net worth `1200.50`, count 3.

---

## Part 8. Names: a partial unique index

```sql
CREATE UNIQUE INDEX accounts_user_name_active_key ON accounts (user_id, lower(name)) WHERE archived_at IS NULL;
```
- `UNIQUE ... (user_id, lower(name))`: per user, no two names that are equal **ignoring case**
  ("HDFC" and "hdfc" clash). `lower(name)` is an **expression index**: it indexes the
  lowercased value.
- `WHERE archived_at IS NULL`: a **partial** index, which only covers active accounts. Archive
  "Wallet", and you may create a new "Wallet".

The service checks first, for a friendly answer:
```java
if (repository.activeNameExists(userId, request.name())) {
    throw new ConflictException(ErrorCode.ACCOUNT_NAME_TAKEN, NAME_TAKEN_DETAIL);
}
```
and **also** catches the index error, for the case where two requests race past the check
at the same moment (the same pattern as email in step 004):
```java
private Account saveCatchingNameClash(Account account) {
    try {
        return repository.saveAndFlush(account);
    }
    catch (DataIntegrityViolationException ex) {
        throw nameClashOr(ex);   // our index's name? -> 409, anything else -> rethrow
    }
}
```
For edits made by dirty checking we call `repository.flush()` inside the same kind of
try/catch. **Flush** means "send the pending SQL now", so the clash happens here, where we can
turn it into a 409, instead of at commit, outside our code.

---

## Part 9. The smaller rules (quick tour)

**AccountType: default icons and "liability"**
```java
public enum AccountType {
    CASH("money"), BANK("bank"), ..., CREDIT_CARD("credit-card"), ...;
    public boolean isLiability() { return this == CREDIT_CARD; }
}
```
An enum constant can carry data (`"money"`), passed to the enum's constructor.

**Type can't change** (`@Column(..., updatable = false)` on `type`): turning a bank into a
card would flip what its balance means.

**Credit-card-only fields**: the service says 422 `CREDIT_CARD_FIELDS_ONLY`, and the database
`CHECK` makes it impossible anyway, so there are two layers of protection.

**Credit card numbers for the app** (`AccountMapper`):
```java
BigDecimal amountOwed = liability ? balance.negate().max(BigDecimal.ZERO) : null;
BigDecimal availableCredit = liability && account.getCreditLimit() != null
        ? account.getCreditLimit().add(balance) : null;
```
- Balance `-5000`: owed `5000`. Balance `+200` (overpaid): owed `0`, thanks to `.max(ZERO)`.
- Limit `100000` + balance `-5000` gives available credit `95000`.
- The server computes these, because AGENTS.md says the app never computes money itself.

**Reorder**: a `LinkedHashSet` removes duplicate ids but **keeps their order**. Then each
account's `moveTo(position)` is saved by dirty checking.

**Delete**: allowed for now. **In step 011**, an account with transactions will only be archivable.

---

## Part 10. Common mistakes

1. `double` for money. Use `BigDecimal`.
2. `new BigDecimal(0.1)`. Use `new BigDecimal("0.1")`.
3. `a.equals(b)` on BigDecimal. Use `compareTo`.
4. `findById(id)` on user data. Use `findByIdAndUserId(id, userId)`.
5. Returning 403 for someone else's id. Return 404.
6. Letting Hibernate write a balance column. Use `updatable = false` plus an atomic `UPDATE ... + :delta`.
7. Using an entity after a `clearAutomatically` update. It's stale, so load it again.
8. `sum()` without `coalesce` gives `null` when there are no rows.
9. Migration type ≠ entity type. `ddl-auto=validate` stops startup. (It happened in this
   step: the migration said `SMALLINT` but Java's `Integer` expects `INTEGER`. We fixed the
   migration before it was ever applied. Never edit a migration that **has** been applied.)

---

## Part 11. Try it yourself

1. **See the lost update.** Remove `updatable = false` from `currentBalance`, run
   `AccountBalanceConcurrencyTest`, read the failure, then put it back.
2. **See the double problem.** In a scratch test, print `0.1 + 0.2` and
   `new BigDecimal("0.1").add(new BigDecimal("0.2"))`.
3. **Ownership by hand.** In Swagger, create an account as user A and copy its id. Log in as
   user B, Authorize, and `GET /accounts/{that id}`. You get 404.
4. **Watch the SQL.** With `logging.level.org.hibernate.SQL=DEBUG`, rename an account and
   check that the `update accounts set ...` line has **no** `current_balance`.
5. **Net worth by hand.** Create a bank (1000), a card (-300) and an excluded wallet. Predict
   `/accounts/summary`, then call it.

---

## Remember these
- Money: `BigDecimal` from Strings, `NUMERIC(19,4)`, JSON string. Compare with `compareTo`.
- Every user-data query has `userId`. Someone else's id gives 404.
- Balance column: `updatable = false`; changes only via `SET balance = balance + :delta`.
- `@Modifying(flushAutomatically, clearAutomatically)`, then reload the entity.
- Sums happen in the database (`select new ...(sum(...))`), wrapped in `coalesce`.
- A partial unique index gives "unique among active rows".
