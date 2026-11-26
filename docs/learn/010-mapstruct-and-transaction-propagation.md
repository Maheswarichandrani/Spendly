# 010: Categories — MapStruct (code that writes code) and joining a transaction

**In short:** two new ideas.
1. **MapStruct:** we write only the *method signature* `CategoryResponse toResponse(Category c)`.
   While compiling, MapStruct writes the body for us (copy name, copy icon, ...).
2. **Transaction propagation:** when registration creates a user, it also creates 19 default
   categories **in the same transaction**. Either all of it is saved or none of it.

Most of the rest (archive, reorder, unique names, ownership) is the same pattern as
accounts in note 009. This note points to it instead of repeating it.

---

## Part 1. The big picture

### What a category is
A label that says **what** money was for:
- **Expense** categories: Food & Dining, Groceries, Rent, Fuel...
- **Income** categories: Salary, Business, Interest...

Every expense and income (step 011) gets one category. That's how reports can later say
"you spent 30% on food".

### What the app can do now
| The user wants to... | App calls |
|---|---|
| see the category picker | `GET /api/v1/categories?kind=EXPENSE` |
| add "Pets" | `POST /api/v1/categories` |
| rename or recolor | `PATCH /api/v1/categories/{id}` |
| hide one | `POST /api/v1/categories/{id}/archive` (and `/unarchive`) |
| drag to reorder | `PUT /api/v1/categories/order` |
| delete one | `DELETE /api/v1/categories/{id}?replaceWith={otherId}` |

### What a new user sees
Right after registering, `GET /api/v1/categories?kind=INCOME` already returns:
```json
[
  { "name": "Salary",   "kind": "INCOME", "icon": "briefcase",  "color": "#16A34A", "sortOrder": 0, ... },
  { "name": "Business", "kind": "INCOME", "icon": "storefront", "color": "#0D9488", "sortOrder": 1, ... },
  ...
]
```

---

## Part 2. The files (the map)

| # | File | What it does |
|---|---|---|
| 1 | `pom.xml` | Adds MapStruct (annotations + the code generator). |
| 2 | `db/migration/V6__create_categories.sql` | Creates the table, plus gives defaults to users who already existed. |
| 3 | `category/entity/CategoryKind.java`, `Category.java` | INCOME/EXPENSE, and one row. |
| 4 | `category/service/DefaultCategories.java` | The starter list of 19. |
| 5 | `category/repository/CategoryRepository.java` | Queries, all with `userId`. |
| 6 | `category/dto/*` | Request and response shapes. |
| 7 | `category/mapper/CategoryMapper.java` | **The MapStruct interface.** |
| 8 | `category/service/CategoryService.java` | The rules. |
| 9 | `category/controller/CategoryController.java` | 8 endpoints. |
| 10 | `user/service/UserService.java` | Now calls `createDefaults` when a user registers. |

---

## Part 3. Concept 1: MapStruct

### The problem
Every feature needs "entity → response DTO" code. In step 009 we wrote it by hand:
```java
return new AccountResponse(account.getId(), account.getName(), account.getType(), account.getIcon(), ...16 fields);
```
It's boring, easy to get wrong (swap two Strings and nobody notices), and easy to forget
when a field is added.

### The idea, with an analogy
You fill in a form's **headings** ("Name", "Icon", "Color"), and an assistant copies each
value from the old form into the matching box of the new form. MapStruct is that assistant.
It matches boxes **by name**.

### Our mapper: just an interface
```java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);

    List<CategoryResponse> toResponses(List<Category> categories);

}
```
Line by line:
- `public interface CategoryMapper`: no method bodies, only "what goes in, what comes out".
- `@Mapper`: "MapStruct, please write the implementation of this interface".
- `componentModel = ...SPRING`: put `@Component` on the generated class, so it becomes a
  **Spring bean**, and the service can receive it through its constructor like any other dependency.
- `unmappedTargetPolicy = ReportingPolicy.ERROR`: if the **response** has a field that MapStruct
  can't fill (no getter with that name), **the build fails**. Without this, MapStruct only
  warns and the field would silently be `null` in the JSON.
- `toResponses(List<Category>)`: MapStruct sees a list in and a list out, and writes the loop itself.

### The code MapStruct writes for us
After `./mvnw compile`, open
`target/generated-sources/annotations/com/sm/spendly/category/mapper/CategoryMapperImpl.java`:
```java
@Component
public class CategoryMapperImpl implements CategoryMapper {

    @Override
    public CategoryResponse toResponse(Category category) {
        if ( category == null ) {
            return null;
        }
        ...
        id = category.getId();
        name = category.getName();
        kind = category.getKind();
        icon = category.getIcon();
        color = category.getColor();
        archived = category.isArchived();
        sortOrder = category.getSortOrder();
        createdAt = category.getCreatedAt();

        CategoryResponse categoryResponse = new CategoryResponse( id, name, kind, icon, color, archived, sortOrder, createdAt );
        return categoryResponse;
    }

    @Override
    public List<CategoryResponse> toResponses(List<Category> categories) {
        ...
        for ( Category category : categories ) {
            list.add( toResponse( category ) );
        }
        return list;
    }
}
```
This is plain Java, exactly what we'd have written by hand. **No magic at runtime**: no
reflection, so it's as fast as hand-written code. You can read it and even debug into it.

How it matched the fields:
| Response field | Found on Category as | Why it matched |
|---|---|---|
| `id` | `getId()` (from BaseEntity) | name `id` |
| `name`, `kind`, `icon`, `color` | `getName()` ... | same names |
| `archived` | `isArchived()` | for a `boolean`, `isX()` counts as property `x` |
| `sortOrder` | `getSortOrder()` | same name |
| `createdAt` | `getCreatedAt()` (from BaseEntity) | same name |
| *(not in response)* | `getUserId()`, `getArchivedAt()` | extra **source** fields are simply ignored |

The `userId` is not in the response and is never sent to the app. Leaving a field out of
the DTO is how we keep things private.

### How the code generator runs: annotation processors (the `pom.xml`)
```xml
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
</dependency>
```
- Only the **annotations** (`@Mapper`, ...). This small jar is needed by our code.

```xml
<annotationProcessorPaths>
    <path> lombok </path>
    <path> mapstruct-processor </path>
    <path> lombok-mapstruct-binding </path>
</annotationProcessorPaths>
```
- An **annotation processor** is a program that runs **inside the Java compiler**. It reads
  annotations and writes new source files, which then get compiled too.
- Lombok is one (it writes the getters for `@Getter`). MapStruct is another (it writes mapper classes).
- **The order problem:** MapStruct needs the getters to exist, but Lombok creates them
  during the same compile. `lombok-mapstruct-binding` makes them cooperate, so Lombok goes first.
- MapStruct isn't managed by Spring Boot, so we pin its version (`1.6.3`, the newest
  stable; 1.7 is still a beta) in `<properties>`.

### Using it in the service
```java
private final CategoryMapper mapper;    // really a CategoryMapperImpl, created by Spring

public CategoryService(CategoryRepository repository, CategoryMapper mapper) { ... }

return mapper.toResponse(saved);
return mapper.toResponses(categories);
```
The service only knows the interface. Spring injects the generated class.

### When to still write a mapper by hand
`AccountMapper` (step 009) stays hand-written because it **computes** things (`amountOwed`,
`availableCredit`, money formatting). MapStruct can do that with extra annotations, but plain
Java is clearer for logic. Rule: **pure copying, use MapStruct; real logic, use plain Java.**

---

## Part 4. Concept 2: joining a transaction (propagation REQUIRED)

### The problem
Registration now does two things:
1. insert the user row;
2. insert 19 default categories.

What if step 2 fails (a bug, the database restarts...)? Without care, we'd have a user with
**no categories**, and the app's category picker would be empty. A broken account.

### The idea: one transaction for both
We want "all or nothing". In note 004 we learned that a `@Transactional` method commits
everything at the end, or rolls everything back on an exception. So both steps must run in
the **same** transaction.

### The code
In `UserService`:
```java
@Transactional
public UserResponse createUser(String email, String passwordHash, String name) {
    ...
    User saved = userRepository.saveAndFlush(new User(email, passwordHash, name));
    // Same transaction (propagation REQUIRED, see CategoryService.createDefaults):
    // if seeding fails, the user row is rolled back too.
    categoryService.createDefaults(saved.getId());
    log.info("User registered userId={}", saved.getId());
    return UserMapper.toResponse(saved);
}
```
In `CategoryService`:
```java
@Transactional
public void createDefaults(UUID userId) {
    ...
    repository.saveAll(defaults);
}
```

### What "propagation" means
Both methods have `@Transactional`. So when `createUser` (already in a transaction) calls
`createDefaults` (which also wants one), what happens? Spring asks the annotation's
**propagation** setting. We use the default:

| Propagation | Meaning |
|---|---|
| **`REQUIRED`** (default) | "If a transaction is already running, **join it**. If not, start one." |
| `REQUIRES_NEW` | "Always start my **own** transaction; pause the caller's until I'm done." |
| `MANDATORY` | "There must already be one, or throw an error." |

With `REQUIRED`, `createDefaults` **joins** `createUser`'s transaction:
```
createUser starts      ── transaction T1 opens ─────────────────────────────┐
  INSERT users                                                              │
  createDefaults()     ── joins T1 (no new transaction) ──┐                 │
    INSERT 19 categories                                  │                 │
  ─────────────────────────────────────────────────────────┘                │
createUser returns     ── T1 COMMITS: user + 19 categories saved together ──┘
```
If anything throws in between, T1 **rolls back**, and neither the user nor any category is saved.

### Why the call goes through the proxy here
Note 004's trap: calling a `@Transactional` method **of the same class** skips Spring's proxy.
Here `UserService` calls a **different** bean (`categoryService`), so the call goes through
`CategoryService`'s proxy, which sees the running transaction and joins it. Correct.

(`createDefaults` would also work without its own `@Transactional`, since it would just run
inside T1. We keep it so the method is safe if someone ever calls it on its own.)

### Why not do it in `AuthService.register`?
`AuthService` has **no** transaction (BCrypt is slow, note 005). Calling `createUser` and then
`createDefaults` from there would make **two separate** transactions: the user could commit,
and then the categories could fail. Putting the call inside `createUser` keeps them together.

The unit test checks the call happens:
```java
verify(categoryService).createDefaults(id);              // on success
verify(categoryService, never()).createDefaults(any());  // when the email is taken
```

---

## Part 5. Giving categories to users who already existed (the backfill)

New users get defaults from Java. But 5 users already existed in Neon **before** this step.
They'd have no categories. So V6 also inserts the defaults for **every existing user**:

```sql
INSERT INTO categories (id, user_id, name, kind, icon, color, sort_order, created_at, updated_at)
SELECT gen_random_uuid(), u.id, d.name, d.kind, d.icon, d.color, d.sort_order, now(), now()
FROM users u
CROSS JOIN (VALUES
    ('Food & Dining', 'EXPENSE', 'fork-knife', '#F97316', 0),
    ...
    ('Other',         'INCOME',  'dots-three-circle', '#6B7280', 5)
) AS d (name, kind, icon, color, sort_order);
```
Piece by piece:
- `(VALUES (...), (...)) AS d (name, kind, ...)`: an **inline table** written right in the
  query, with 19 rows and named columns.
- `CROSS JOIN`: **every** user combined with **every** row of `d`. 5 users × 19 = 95 new rows.
- `gen_random_uuid()`: Postgres makes an id for each row. These are v4 (random) ids instead
  of our usual v7 (time-ordered); for a one-time insert of a few rows that doesn't matter.
- Migrations run **once** (Flyway remembers in `flyway_schema_history`), so this never runs twice.

**I checked on Neon:** `users=5 usersWithoutCategories=0 flywayVersion=6`.

The list is now in **two places** (Java and this SQL). The SQL is a one-time copy and never
changes, because applied migrations are never edited. Future changes go only in `DefaultCategories.java`.

---

## Part 6. The rest: same patterns as accounts (quick tour)

| Feature | How | See |
|---|---|---|
| Owner check | `findByIdAndUserId`; someone else's id gives 404 | note 009, Part 5 |
| Names unique, ignoring case, only among active ones | partial unique index `(user_id, kind, lower(name)) WHERE archived_at IS NULL` | note 009, Part 8 |
| Edits saved without `save()` | dirty checking | note 008 |
| Kind can't change | `@Column(updatable = false)` | note 009 |
| Archive / unarchive / reorder | the same code shape as accounts | note 009 |

New in categories:
- **Unique per kind**: "Gifts" can be both an income and an expense category, because `kind` is in the index.
- **Reorder per kind**: the request says which list (`"kind": "EXPENSE"`). Sending an income
  id in an expense reorder gives 404.
- **`kind` as an optional query parameter**:
  ```java
  @RequestParam(required = false) CategoryKind kind
  ```
  - `required = false`: may be left out, in which case it's `null`, meaning "both kinds".
  - Spring turns the text `EXPENSE` into the enum. An unknown value like `TRANSFER` gives
    400 `INVALID_PARAMETER`.

### Delete with a replacement
`DELETE /api/v1/categories/{id}?replaceWith={otherId}`
- **Why?** AGENTS.md: a category with transactions must never be deleted "out from under"
  them. They must move somewhere.
- **The replacement must be** yours, active, of the **same kind**, and not the category
  itself. Otherwise 422 `INVALID_REPLACEMENT_CATEGORY`.
- **Step 011 finishes this:** if the category has transactions and no `replaceWith` is given,
  the answer is 409 `CATEGORY_IN_USE`; with one, the transactions move first, then the delete.
  The API shape is set now, so your friend's code won't need to change later.

---

## Part 7. Common mistakes

1. **Forgetting `unmappedTargetPolicy = ERROR`**: a new response field silently becomes `null`.
2. **Missing `lombok-mapstruct-binding`**: MapStruct can't see Lombok's getters and fails
   with "Unknown property".
3. **Putting logic in MapStruct annotations**: keep calculations in plain Java (like `AccountMapper`).
4. **Seeding from a method without a transaction** (like `AuthService`): you get half-created users.
5. **Calling the seeding method on `this`**: self-invocation skips the proxy (note 004).
6. **Editing V6 later to change the defaults**: never edit an applied migration. Change `DefaultCategories.java`.

---

## Part 8. Try it yourself

1. **Read the generated code.** Run `./mvnw -q compile` and open `CategoryMapperImpl.java` in
   `target/generated-sources/annotations`.
2. **Make MapStruct complain.** Add `String note` to `CategoryResponse` and compile. The build
   fails with "Unmapped target property: note". Remove it again.
3. **Prove all-or-nothing.** Temporarily add `throw new IllegalStateException("boom");` at the
   end of `createDefaults`, register in Swagger, and get a 500. Then try to log in with that
   email: 401, because the user row was rolled back too. Remove the line.
4. **See the defaults.** Register a new user in Swagger and call `GET /categories?kind=EXPENSE`.

---

## Remember these
- MapStruct: write the interface; the build writes the class. Matching is by name. Use `ERROR` for unmapped targets.
- Annotation processors run inside the compiler. Lombok goes before MapStruct (the binding).
- `@Transactional` default propagation `REQUIRED` means "join the running transaction", so the work is all-or-nothing.
- Cross-bean calls go through the proxy; same-class calls don't.
- A migration can insert data too (backfill), and it runs exactly once.
