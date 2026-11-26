# 010: Categories

Part of the approved roadmap `prompts/ROADMAP-2026-11-26.md`.

## Goal
Users pick a category for every income and expense. Every user starts with a default set,
and can add, edit, archive, reorder and delete categories. A category is never left
"orphaned": deleting one that has transactions needs a replacement.

## Decisions
- **Kinds**: `INCOME` and `EXPENSE` only (transfers and debts have no category). The kind
  is immutable (`updatable = false`).
- **Defaults**: 13 expense and 6 income categories (`DefaultCategories.java`), with Phosphor
  icons and fixed colors. They're created **inside the registration transaction**:
  `UserService.createUser` calls `CategoryService.createDefaults` with propagation
  REQUIRED, so a user can never exist without categories.
- **Existing users**: V6 backfills the same list with SQL (`CROSS JOIN VALUES`). Verified
  on Neon: 5 users, 0 without categories.
- **Names** are unique per user **per kind** among active ones, ignoring case (a partial
  unique index on `(user_id, kind, lower(name)) WHERE archived_at IS NULL`). "Gifts" may be
  both income and expense.
- **Custom defaults**: icon `tag`, color `#6B7280`. A new category goes to the end of its kind's list.
- **Archive** hides a category from pickers and blocks edits (422 `CATEGORY_ARCHIVED`); unarchive checks for name clashes.
- **Reorder** happens per kind (`PUT /categories/order` with `kind`). An id of the other kind gives 404.
- **Delete**: `DELETE /categories/{id}?replaceWith={id}`. The replacement must be the user's
  own, active, of the same kind, and not the category itself (422
  `INVALID_REPLACEMENT_CATEGORY`). **Step 011** adds the in-use check (409
  `CATEGORY_IN_USE` without a replacement) and moves the transactions in the same transaction.
- **Limit**: 200 active categories.
- **MapStruct 1.6.3** is introduced here: `CategoryMapper` is an interface, the implementation
  is generated at compile time, `componentModel = SPRING`, `unmappedTargetPolicy = ERROR`.
  The pom adds the processor next to Lombok, plus `lombok-mapstruct-binding` 0.2.0.
- `requireActiveCategory(userId, id, kind)` is ready for step 011 (422 `CATEGORY_KIND_MISMATCH`).

## Files (all CLAUDE WRITES)
- `backend/pom.xml` (MapStruct dependency, processor and binding)
- `db/migration/V6__create_categories.sql` (table, indexes, backfill)
- `category/entity/Category.java`, `CategoryKind.java`
- `category/repository/CategoryRepository.java`
- `category/dto/CreateCategoryRequest`, `UpdateCategoryRequest`, `ReorderCategoriesRequest`, `CategoryResponse`
- `category/mapper/CategoryMapper.java` (MapStruct)
- `category/service/CategoryService.java`, `DefaultCategories.java`
- `category/controller/CategoryController.java`
- `user/service/UserService.java` (seeds defaults), `common/exception/ErrorCode.java` (+7)
- Tests: `CategoryIntegrationTest` (12), `UserServiceTest` (seeding verified), `FlywayBaselineTest`
- `docs/learn/010-mapstruct-and-transaction-propagation.md`

## API contract
| Endpoint | Body / params | Success | Errors |
|---|---|---|---|
| `GET /api/v1/categories?kind=&includeArchived=false` | kind optional | 200 `CategoryResponse[]` | 400 `INVALID_PARAMETER` |
| `GET /api/v1/categories/{id}` | | 200 | 404 `CATEGORY_NOT_FOUND` |
| `POST /api/v1/categories` | `{ name, kind, icon?, color? }` | 201 | 400, 409 `CATEGORY_NAME_TAKEN`, 422 `CATEGORY_LIMIT_REACHED` |
| `PATCH /api/v1/categories/{id}` | `{ name?, icon?, color? }` | 200 | 400, 404, 409, 422 `CATEGORY_ARCHIVED` |
| `POST /api/v1/categories/{id}/archive` and `/unarchive` | | 200 | 404, 409, 422 |
| `PUT /api/v1/categories/order` | `{ kind, categoryIds[1..300] }` | 200 | 400, 404 |
| `DELETE /api/v1/categories/{id}?replaceWith=` | | 204 | 404, 409 `CATEGORY_IN_USE` (from 011), 422 `INVALID_REPLACEMENT_CATEGORY` |

`CategoryResponse = { id, name, kind, icon, color, archived, sortOrder, createdAt }`

## Database
`categories` table with CHECKs (name length, kind, icon and color format). Indexes:
- the partial unique `(user_id, kind, lower(name))`;
- `(user_id, kind, sort_order)`, for the picker query.

## Spring concepts this step teaches
- **MapStruct**: annotation processors, generated code, the component model, the unmapped-target policy.
- **Transaction propagation REQUIRED**: joining the caller's transaction for all-or-nothing registration.
- Data backfill inside a Flyway migration.
- Optional enum `@RequestParam` (a bad value gives 400).

## Transactions
- `createDefaults` joins `createUser`'s transaction.
- Reads are `readOnly`; each write method is one `@Transactional`.

## Logging
INFO: defaults created (count), created (kind), updated, archived, unarchived, deleted
(with replacement id), reordered. Ids only.

## Tests (186 total, all passing)
`CategoryIntegrationTest`:
- defaults arrive in order;
- an unknown kind gives 400;
- create uses the defaults and goes to the end;
- names are unique per kind, ignoring case;
- invalid input gives 3 field errors;
- PATCH works;
- archive hides, blocks edits and frees the name;
- reorder stays within the kind;
- an id of the other kind gives 404;
- 4 kinds of bad replacement give 422;
- delete with or without a replacement works;
- A vs B on every endpoint, including using a stranger's category as the replacement.

`UserServiceTest` verifies that seeding is called on registration and not on a duplicate email.

## Manual test
1. Register a new user in Swagger, log in, and Authorize.
2. `GET /categories?kind=INCOME` returns 6 (Salary first).
3. `POST /categories` with `{ "name": "Pets", "kind": "EXPENSE", "icon": "paw-print" }` returns 201, with sortOrder 13.
4. `DELETE /categories/{Pets id}?replaceWith={Salary id}` returns 422 (other kind).
