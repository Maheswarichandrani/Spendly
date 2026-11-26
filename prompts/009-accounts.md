# 009: Accounts

Part of the approved roadmap `prompts/ROADMAP-2026-11-26.md`.

## Goal
Users create and manage the places their money lives (cash, bank, savings, debit and
credit cards, wallet, investment, other), see each balance, and see their net worth.

## Decisions
- **Money**:
  - `BigDecimal` in Java and `NUMERIC(19,4)` in Postgres;
  - a **JSON string** in both directions (input also accepts numbers);
  - global Jackson config (`JacksonConfig`) and a global springdoc schema (`OpenApiConfig`);
  - output normalized by `Money.forDisplay`: at least 2 decimals, no trailing zeros beyond that.
- **Input bounds**: `@Digits(integer = 15, fraction = 4)` matches the column.
- **Credit cards are liabilities**:
  - the balance is negative when money is owed (`-5000` = owe 5000);
  - the response adds `liability`, `amountOwed` and `availableCredit`;
  - credit fields (`creditLimit`, `statementDay`, `dueDay`) are allowed only on `CREDIT_CARD` (422 `CREDIT_CARD_FIELDS_ONLY`, plus a DB CHECK).
- **Balance safety**:
  - `current_balance` is `updatable = false` on the entity, so Hibernate never writes it in an UPDATE;
  - it's changed only by `AccountRepository.adjustBalance`, an atomic `SET current_balance = current_balance + :delta`;
  - proven by `AccountBalanceConcurrencyTest`, which fails (1000 instead of 900) when the guard is removed.
- **Opening balance edit** moves the current balance by the same difference (atomic), then re-reads the row.
- **Type is immutable** (`updatable = false`). Changing asset to liability would flip the balance's meaning.
- **Names** are unique per user among **active** accounts, ignoring case. This is a partial
  unique index `(user_id, lower(name)) WHERE archived_at IS NULL`, plus a pre-check for a
  friendly 409 `ACCOUNT_NAME_TAKEN`. A race is caught from the index name.
- **Archive** = `archived_at` timestamp. Archived accounts are hidden, left out of totals, and can't be edited (422 `ACCOUNT_ARCHIVED`); unarchive is possible.
- **Delete** is a hard delete for now. **Step 011 must block deleting an account that has transactions.**
- **Limit**: 100 active accounts per user (422 `ACCOUNT_LIMIT_REACHED`).
- **Defaults**:
  - icon by type (`bank`, `credit-card`, `money`...);
  - color `#2563EB`;
  - opening balance 0;
  - includeInTotal true;
  - new accounts go to the end of the list.
- **Net worth** comes from one JPQL aggregate into a record projection (`AccountTotals`).
  Assets = non-card balances; liabilities = minus the card balances; net = assets − liabilities.
  Only active accounts with `includeInTotal = true` count.
- **Reorder**: `PUT /accounts/order` with ids top to bottom. Listed ids come first, the rest
  keep their order. A foreign or unknown id gives 404.
- MapStruct is moved to step 010; `AccountMapper` is hand-written.

## Files (all CLAUDE WRITES)
- `db/migration/V5__create_accounts.sql`
- `account/entity/Account.java`, `AccountType.java`
- `account/repository/AccountRepository.java`, `AccountTotals.java`
- `account/dto/CreateAccountRequest`, `UpdateAccountRequest`, `ReorderAccountsRequest`, `AccountResponse`, `AccountSummaryResponse`
- `account/mapper/AccountMapper.java`, `account/service/AccountService.java`, `account/controller/AccountController.java`
- `common/money/Money.java`, `common/config/JacksonConfig.java`, `common/config/OpenApiConfig.java` (BigDecimal schema)
- `common/exception/ErrorCode.java` (+5 account codes)
- Tests: `AccountIntegrationTest` (18), `AccountBalanceConcurrencyTest`, `MoneyTest`, `FlywayBaselineTest`
- `docs/learn/009-money-ownership-and-safe-balances.md`

## API contract
| Endpoint | Body | Success | Errors |
|---|---|---|---|
| `GET /api/v1/accounts?includeArchived=false` | | 200 `AccountResponse[]` | 401 |
| `GET /api/v1/accounts/summary` | | 200 `{ totalAssets, totalLiabilities, netWorth, accountCount, currency }` | 401 |
| `GET /api/v1/accounts/{id}` | | 200 | 404 `ACCOUNT_NOT_FOUND`, 400 `INVALID_PARAMETER` |
| `POST /api/v1/accounts` | `{ name, type, icon?, color?, openingBalance?, includeInTotal?, creditLimit?, statementDay?, dueDay? }` | 201 | 400, 409 `ACCOUNT_NAME_TAKEN`, 422 `ACCOUNT_LIMIT_REACHED` / `CREDIT_CARD_FIELDS_ONLY` |
| `PATCH /api/v1/accounts/{id}` | same fields minus type, all optional | 200 | 400, 404, 409, 422 `ACCOUNT_ARCHIVED` / `CREDIT_CARD_FIELDS_ONLY` |
| `POST /api/v1/accounts/{id}/archive` | | 200 | 404 |
| `POST /api/v1/accounts/{id}/unarchive` | | 200 | 404, 409, 422 |
| `PUT /api/v1/accounts/order` | `{ accountIds: [...] }` (1..200) | 200 active list | 400, 404 |
| `DELETE /api/v1/accounts/{id}` | | 204 | 404 |

## Database
`accounts` with CHECKs for name length, type, icon and color format, credit-fields-only,
credit limit ≥ 0, and day ranges. Indexes:
- `accounts_user_name_active_key` UNIQUE `(user_id, lower(name)) WHERE archived_at IS NULL`, for name uniqueness. It also serves the FK on `user_id`.
- `accounts_user_sort_idx (user_id, sort_order)`, for the list screen.

## Spring concepts this step teaches
- BigDecimal for money; a Jackson 3 `JsonMapperBuilderCustomizer` with a config override per type.
- `@PathVariable` plus ownership scoping with `findByIdAndUserId` (404 on a stranger's id).
- `@Column(updatable = false)` and the full-row UPDATE lost-update trap.
- `@Modifying(flushAutomatically, clearAutomatically)` for an atomic increment, and re-reading afterwards.
- JPQL aggregates with `select new` into a record projection; `coalesce` and `cast`.
- Partial unique indexes, mapped to 409 by constraint name.

## Transactions
- Reads are `readOnly`.
- create/update/archive/unarchive/reorder/delete are each one `@Transactional`.
- `update` flushes early, so a unique violation becomes a 409 inside the method.

## Logging
INFO: created (with type), updated, opening balance changed, archived, unarchived, deleted,
reordered (count). Ids only; never names or amounts.

## Tests (174 total, all passing)
- **Create**: defaults and money as a string; string or number input with exact decimals;
  too many decimals gives 400 for that field; bad color or icon.
- **Credit cards**: amount owed and available credit; credit fields on a bank account give 422.
- **Names**: unique ignoring case, freed by archiving, and unarchive clashes.
- **Edit**: the opening balance moves the balance, rename keeps the balance, editing an archived account gives 422.
- **List**: hides archived accounts unless asked.
- **Summary**: the mixed case (active, excluded, archived, card) and the empty case.
- **Reorder**: the new order, and an unknown id gives 404.
- **Delete**: then GET gives 404.
- **Ownership**: user A vs user B on every endpoint.
- **Errors**: a bad UUID gives 400; no token gives 401.
- **Concurrency**: rename during a balance change keeps both changes.
- `MoneyTest`.

## Manual test
1. Run in `dev`, then register and log in in Swagger, and click Authorize.
2. `POST /accounts` with `{ "name": "HDFC", "type": "BANK", "openingBalance": "25000.50" }` returns 201, with `currentBalance` `"25000.50"`.
3. `POST /accounts` with `{ "name": "Amex", "type": "CREDIT_CARD", "openingBalance": "-5000", "creditLimit": "100000" }` gives `amountOwed` `"5000.00"`.
4. `GET /accounts/summary` gives netWorth `"20000.50"`.
5. Create another "hdfc" and get 409 `ACCOUNT_NAME_TAKEN`.
