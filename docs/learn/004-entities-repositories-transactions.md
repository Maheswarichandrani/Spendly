# 004: Entities, repositories, and transactions

Prompt: [`prompts/004-user-registration.md`](../../prompts/004-user-registration.md) · Before this, read [002](002-exception-handling.md) and [003](003-request-id-and-filters.md)

> **In short:** a Java class (`User`) is mapped to a table (`users`). That makes it an **entity**. We never write SQL to save it: a **repository** interface (`UserRepository`) does that, and Spring writes the code behind it. A **transaction** (`@Transactional`) wraps the save so it is all-or-nothing. Hibernate keeps a **notepad** (the persistence context) and writes SQL to the database later, at **flush**.

---

## 1. The idea (what and why)

### The problem

Our app thinks in Java objects: `new User("ravi@mail.com", hash, "Ravi")`. The database thinks in rows: `INSERT INTO users (...) VALUES (...)`. Writing that SQL by hand for every table, and turning rows back into objects, is a lot of boring, error-prone code.

### The solution

**JPA** is a standard ("Jakarta Persistence") for mapping objects to tables. **Hibernate** is the library that does the work. **Spring Data JPA** sits on top and removes even the code that calls Hibernate.

**Real-life comparison:** a bank clerk with a filing cabinet.
- The **entity** is the record card's layout: which boxes exist (email, name...).
- The **repository** is the clerk. You say "file this card" or "do we have a card for this email?" and the clerk knows how.
- The **transaction** is the rule "either the whole form is filed, or nothing is". No half-filed cards.
- The **persistence context** is the clerk's desk tray. Cards sit there first and go into the cabinet in one batch (the **flush**).

---

## 2. How it works inside

### Piece 1: the entity (`User`, `BaseEntity`)

```java
@Entity                     // "this class is a table"
@Table(name = "users")      // ...this table
public class User extends BaseEntity {
    @Column(name = "email", nullable = false)
    private String email;
    ...
}
```

- `@Entity` + `@Table`: Hibernate maps this class to `users`.
- `@Column`: one field = one column. We always write the name, so renaming a Java field never silently renames a column.
- `@Enumerated(EnumType.STRING)`: store `"MONDAY"`, not the position `0`. If someone reordered the enum, `0` would suddenly mean something else.
- `protected User() {}`: Hibernate needs an empty constructor. When it loads a row, it creates an empty `User` and fills the fields itself.

**`BaseEntity` with `@MappedSuperclass`.** Every table needs `id`, `created_at`, `updated_at`. Instead of repeating them, `BaseEntity` holds them, and `@MappedSuperclass` says: "I'm not a table. Copy my columns into every child's table."

- `@UuidGenerator(style = VERSION_7)`: when you save, Hibernate creates the id in Java. A v7 UUID starts with the time, so new rows go at the **end** of the index, like adding pages to the end of a notebook instead of squeezing them into the middle.
- `@CreationTimestamp` / `@UpdateTimestamp`: Hibernate fills these with "now" (UTC) on insert and on update.

**Why `equals` is tricky.** Two Java objects can represent the same row. We say: same class + same id = equal. A new user has no id yet, so it's only equal to itself. `hashCode` returns the **same number for every User**, before and after saving. If it used the id, the hash would change at save time, and the object would get "lost" inside a `HashSet`. That's why we never use Lombok `@Data` on entities: it generates equals/hashCode from **all** fields.

### Piece 2: the repository (`UserRepository`)

```java
public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByEmail(String email);
}
```

There is **no class** that implements this. At startup, Spring Data sees the interface and builds an object behind it (a **proxy**, the same trick from 001). You get `save`, `findById`, `delete`... for free from `JpaRepository`.

**Derived query.** Spring reads the method **name**: `exists` + `By` + `Email` becomes:

```sql
select u1_0.id from users u1_0 where u1_0.email=? fetch first ? rows only
```

(You saw exactly this line in the test log.) A typo like `existsByEmial` fails at **startup**, not in production, because Spring checks the name against the entity's fields.

### Piece 3: the persistence context and flush

This part surprises everyone.

`repository.save(user)` does **not** run `INSERT` right away. Hibernate puts the user on its **desk tray** (the persistence context) and runs the SQL later, at **flush** time. Flush normally happens at **commit**, when the transaction ends.

Why it matters to us:

```java
try {
    userRepository.save(user);        // INSERT only queued
} catch (DataIntegrityViolationException ex) {
    // never reached! The INSERT runs at commit, after we've left the try block
}
```

So we call `saveAndFlush`, which says "run the SQL **now**". If the email is a duplicate, the error happens **inside** our `try`, and we turn it into a clean 409.

### Piece 4: `@Transactional`

```java
@Transactional
public UserResponse createUser(String email, String passwordHash, String name) { ... }
```

Spring wraps `UserService` in a proxy. When someone calls `createUser`:

```
caller → [proxy: BEGIN] → real createUser() → [proxy: COMMIT, or ROLLBACK if a RuntimeException escaped]
```

- **Commit**: everything inside is saved together.
- **Rollback**: an unchecked exception (like our `ConflictException`) escaped, so nothing is saved.

**Why `AuthService.register` has no `@Transactional`.** BCrypt takes about 250 ms. A transaction holds a database connection the whole time. Our pool has only 5 connections (`application-dev.properties`). Hashing inside a transaction means 5 people signing up at once could block **everyone else**. So: hash first (no connection held), then call `createUser` (a short transaction: one check and one insert).

### Piece 5: `@Configuration` + `@Bean` (`PasswordConfig`)

`BCryptPasswordEncoder` is a library class. We can't add `@Component` to someone else's code. So:

```java
@Configuration
public class PasswordConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
}
```

At startup Spring calls this method **once** and keeps the result as a bean. Anyone asking for a `PasswordEncoder` in their constructor gets it. `AuthService` asks for the **interface**, not BCrypt, so swapping the algorithm later changes one line.

---

## 3. One request, start to finish

`POST /api/v1/auth/register` with `{"name":" Ravi ","email":"Ravi@Mail.com","password":"correct-horse-42"}`:

1. **Tomcat** gets the request on a worker thread.
2. **`RequestIdFilter`** (003) gives it an id, puts it in the MDC.
3. **`DispatcherServlet`** finds `AuthController.register`.
4. **Jackson** turns the JSON into `RegisterRequest`. Its compact constructor strips the spaces: `"Ravi"`, `"Ravi@Mail.com"`.
5. **`@Valid`** checks the rules (`@NotBlank`, `@Email`, `@Size`). If it fails → `MethodArgumentNotValidException` → `GlobalExceptionHandler` → 400 `VALIDATION_FAILED`.
6. **`AuthService.register`** (no transaction):
   - lowercases the email → `ravi@mail.com`
   - checks the password is ≤ 72 **bytes** → else 400 `PASSWORD_TOO_LONG`
   - `passwordEncoder.encode(...)` → `$2a$12$...` (about 250 ms)
7. **`UserService.createUser`** through its **proxy** → `BEGIN`:
   - `existsByEmail` → `select ... where email=?` → false
   - `saveAndFlush` → Hibernate assigns the UUIDv7 and timestamps → `INSERT INTO users ...`
   - logs `User registered userId=...`
   - `UserMapper.toResponse` → `UserResponse` (no password field)
8. Proxy → `COMMIT`. The row is now permanent in Postgres.
9. **Jackson** turns `UserResponse` into JSON → **201 Created**.

If the email already exists, step 7 throws `ConflictException` → the proxy does `ROLLBACK` → `GlobalExceptionHandler` → 409 `EMAIL_ALREADY_REGISTERED`.

---

## 4. Why this design

| Choice | Rejected alternative | Why |
|---|---|---|
| Spring Data repository | Writing JDBC/SQL by hand | Far less code; queries checked at startup |
| Flyway creates the table, Hibernate only `validate`s | `ddl-auto=update` | Hibernate guessing the schema is unpredictable; migrations are reviewed, versioned SQL |
| Check `existsByEmail` **and** catch the unique error | Only one of them | The check gives a fast, clear answer; the DB constraint is the real guarantee when two requests race |
| Hash outside the transaction | One big `@Transactional` on `register` | Don't hold a DB connection during 250 ms of hashing |
| Lowercase email in code **and** a DB `CHECK` | Code only | A future bug can't create `Ravi@` and `ravi@` as two accounts |
| UUIDv7 made in Java | Random UUIDv4 / DB `uuidv7()` | Ordered ids keep the index compact; no dependency on the Postgres version |
| `logServerErrorDetail=false` | Default driver setting | Postgres error "Detail" lines contain the row's values (email, hash). They must never reach logs. |

---

## 5. Common mistakes

- **`save` instead of `saveAndFlush` and expecting to catch the duplicate.** The error happens at commit, outside your `try`.
- **Calling a `@Transactional` method from the same class** (`this.createUser(...)`). That call skips the proxy, so there's **no transaction**. Our call works because it goes from `AuthService` to **another bean**.
- **`@Transactional` on a `private` method.** Proxies can't see private methods. Silently ignored.
- **Catching an exception inside `@Transactional` and carrying on** with the same Hibernate session after a failed flush. The session is broken; throw straight away (we do).
- **Returning the entity from the controller.** Jackson would print `passwordHash`. Always map to a DTO.
- **`EnumType.ORDINAL`.** Reorder the enum and old rows change meaning.
- **`@Data` on an entity.** Broken equals/hashCode, and `toString` can trigger lazy loading.
- **`toLowerCase()` without `Locale.ROOT`.** On a Turkish server, `"I"` becomes `"ı"`.

---

## 6. Try it

1. **See the flush.** In `UserService`, change `saveAndFlush` to `save`. Run `UserServiceTest`: it now fails, because the mock was told what `saveAndFlush` returns, not `save`. Now think: in a real race, where would the duplicate error be thrown? (Answer: at commit, after `createUser`'s code has finished, so our `catch` never sees it. The safety net in `GlobalExceptionHandler` answers with a generic `CONFLICT` instead of `EMAIL_ALREADY_REGISTERED`.) Change it back.
2. **Break self-invocation.** Add a method `createTwice` in `UserService` without `@Transactional` that calls `createUser` twice with the same email. Call it from a test. Is the first user still saved after the second one fails? (Yes: there's no proxy in the inner calls, so each `saveAndFlush` runs in its own repository transaction.)
3. **Derived query typo.** Rename `existsByEmail` to `existsByEmial` and start the app. Read the startup error. Rename it back.
4. **Look at the SQL.** Run `UserRepositoryTest` and find the `Hibernate: insert into users ...` lines in the output. Count the columns and compare them with `V2__create_users.sql`.
