# 002 — Handling errors in one place

Prompt: [`prompts/002-error-handling.md`](../../prompts/002-error-handling.md) · Before this, read [001](001-how-spring-boot-boots.md)

> **In short:** when anything goes wrong in any controller, one class (`GlobalExceptionHandler`) catches the error and sends back the **same, safe JSON shape** with a short **code** the mobile app can understand.

---

## 1. The idea (what and why)

### The problem

Imagine the mobile app sends a request and something goes wrong. Maybe:
- the user forgot to type a name,
- the account doesn't exist,
- or our code has a bug.

The app needs to know **what** went wrong so it can show the right message.

Without a plan:
- every controller would build error replies its own way (messy),
- the app would have to read English text like "Account not found" to guess what happened. That breaks as soon as we change the wording.
- worse, a crash might send back secret details like SQL queries or a Java stack trace.

### The solution, in two parts

**Part 1: one shape for every error.** There's an official standard for this called **Problem Details** (RFC 9457). Every error looks like this:

```json
{
  "title": "Not found",
  "status": 404,
  "detail": "Account not found.",
  "instance": "/api/v1/accounts/123",
  "code": "NOT_FOUND"
}
```

| Field | Meaning |
|---|---|
| `title` | Short name of the error type |
| `status` | The HTTP status number (404, 400, 500…) |
| `detail` | A human sentence, written by us, safe to show |
| `instance` | Which URL had the problem |
| `code` | **Our addition.** A fixed word the app checks in code, e.g. `if (code == "NOT_FOUND")` |

**Part 2: one place that builds it.** Instead of every controller handling its own errors, all errors "fall through" to **one class** that builds the reply.

**Real-life comparison:** a hospital reception desk.
- Doctors (controllers) don't talk to the outside world when something goes wrong. They just say "problem!" (throw an exception).
- The reception desk (`GlobalExceptionHandler`) gives the family a calm, standard message.
- The private medical details (the stack trace) stay inside the hospital, in the log file.

---

## 2. How it works inside

### What is an exception?

An **exception** is Java's way of saying "stop, something went wrong". When code runs `throw new NotFoundException("Account not found.")`:
1. the current method stops immediately,
2. Java goes back up the chain of calls looking for someone who will **catch** it.

### How the error reaches our handler

1. A controller method throws an exception.
2. Nobody inside the controller catches it, so it goes up to **DispatcherServlet** (Spring's front desk from note 001).
3. The front desk asks: "Does anyone handle this kind of error?"
4. It finds our class, because it's marked `@RestControllerAdvice`. That annotation means "this class handles errors for **all** controllers".
5. Inside our class, it looks for a method marked `@ExceptionHandler` for that exception type.
6. That method builds the Problem Details JSON and sends it back.

### Which handler method is chosen?

Our class has several handler methods:

```java
@ExceptionHandler(ApiException.class)                    // our own errors
@ExceptionHandler(DataIntegrityViolationException.class) // database duplicate errors
@ExceptionHandler(Exception.class)                       // everything else (catch-all)
```

Rule: **Spring picks the most specific match.**

- `NotFoundException` is a kind of `ApiException`, which is a kind of `Exception`.
- Both `ApiException` and `Exception` match, but `ApiException` is **closer**, so its handler wins.
- An `IllegalStateException` (a bug) only matches `Exception`, so it goes to the catch-all.

Comparison: a letter addressed to "Ravi, Flat 4B" goes to Ravi, not to "anyone in the building". The catch-all is "anyone in the building": it only gets letters with no better address.

### Our own error classes

```java
public abstract class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    ...
}
```

- `ApiException` is the parent of all errors **we** throw on purpose.
- It carries an `ErrorCode` (like `NOT_FOUND`) and a safe message.
- `extends RuntimeException` makes it an *unchecked* exception, so Java doesn't force every method to declare `throws ...`. (It also matters for database transactions later, in step 004.)

The children:

| Class | Status | When to use |
|---|---|---|
| `NotFoundException` | 404 | The thing doesn't exist, **or it belongs to another user** (so nobody can tell whether an id exists) |
| `ConflictException` | 409 | Clashes with existing data, e.g. "this email is already registered" |
| `BusinessRuleException` | 422 | Input is fine, but a rule says no, e.g. "credit limit exceeded" |

The codes live in one list, the enum `ErrorCode`:

```java
NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
```

This reads as: "the code `NOT_FOUND` means HTTP status 404, and its title is 'Not found'".

**Rule:** once the app uses a code, we **never rename it**, or the app would break.

### Errors made by Spring itself

Some errors happen **before** our controller code even runs, for example:
- the JSON sent is broken (`{"name":`),
- the URL doesn't exist,
- the wrong HTTP method is used (DELETE instead of GET).

Spring has its own class for these, `ResponseEntityExceptionHandler`. It already knows how to handle about 15 kinds of Spring errors.

So our class **extends** it (inherits all of that). Then we change just **one** method, `handleExceptionInternal`. Every one of those 15 handlers passes through this method at the end, so changing it once adds our `code`, our safe `detail`, and `instance` to all of them.

Comparison: 15 different forms all go through the same stamping machine at the end. We changed the stamp once instead of rewriting 15 forms.

### Validation errors (missing or wrong fields)

In the test controller:

```java
record ProbeRequest(@NotBlank String name, @Positive BigDecimal amount) {}

@PostMapping("/validate")
ProbeRequest validate(@Valid @RequestBody ProbeRequest request) { ... }
```

- `@NotBlank`: "name must not be empty".
- `@Positive`: "amount must be more than 0".
- `@RequestBody`: "read this object from the JSON body".
- `@Valid`: "check the rules **before** running the method".

If a rule fails:
1. the method **never runs**,
2. Spring throws `MethodArgumentNotValidException`,
3. our handler turns it into a reply with a list of the bad fields:

```json
"errors": [
  { "field": "amount", "message": "must be greater than 0" },
  { "field": "name",   "message": "must not be blank" }
]
```

We **never** send back what the user typed (the "rejected value"), because it could be a password.

### Why the JSON has no `"type"` field

The standard says a missing `type` means `"about:blank"` ("no special type page"). Spring leaves it out to keep replies short. That's normal.

---

## 3. Following one request

The request: `POST /test/errors/validate` with `{ "name": "", "amount": -5 }`.

1. **DispatcherServlet** (the front desk) finds the method `validate` in `ErrorProbeController`.
2. **Jackson** (the JSON library) turns the text into a Java object: `ProbeRequest(name="", amount=-5)`.
3. Because of `@Valid`, the **validator** checks the rules. Two rules fail.
4. Spring throws `MethodArgumentNotValidException`. The `validate` method is never called.
5. The front desk finds our `GlobalExceptionHandler`. The inherited method for this error runs, then passes through our `handleExceptionInternal`, which:
   - picks the code `VALIDATION_FAILED`,
   - builds the list of 2 field errors, sorted by field name,
   - writes the detail "2 fields are invalid.",
   - logs a quiet DEBUG line (not an alarm, since it's the user's mistake).
6. Jackson turns the result into JSON with status **400** and content type `application/problem+json`.

No database is touched. All of this happens in the web layer.

---

## 4. Why we chose this

- **One handler class instead of `try/catch` in every controller.** Less code, the same shape every time, and nothing forgotten.
- **A `code` field instead of making the app read `title` or `detail`.** Text can change (new wording, translation). Codes never change.
- **Our own `detail` text instead of Spring's.** Spring's text can repeat what the user sent, e.g. `Failed to convert value 'abc'`. We never echo input back.
- **A few exception classes plus many codes instead of one class per error.** A new class is only needed when the **status** differs. Adding a code is one line.
- **`@WebMvcTest` for these tests instead of `@SpringBootTest`.**
  - `@WebMvcTest` starts only the web part: no database, no Docker.
  - It runs in about 3 seconds instead of about 25.

---

## 5. Mistakes to avoid

1. **Sending `ex.getMessage()` in the catch-all.** A database error message can contain SQL and table names. We log it and send "Unexpected error." instead. Our `boom` and `integrity` tests check this.
2. **Sending back the rejected value.** It could be a password.
3. **Having two error-handler classes** that both catch `Exception`. Which one runs isn't guaranteed. Keep one.
4. **Expecting this class to catch login errors.** Security errors (401, 403) happen *before* the front desk, so this class never sees them. Step 004 handles those.
5. **Logging every user mistake as an ERROR.** Real bugs get lost in the noise. Use DEBUG for user mistakes and ERROR for our bugs.

---

## 6. Try it yourself

1. **See the handler being chosen.**
   - Add `logging.level.org.springframework.web=DEBUG` to `application-dev.properties`, start the app, and run `curl -i localhost:8080/api/v1/nope`.
   - In the log, find the line that names our handler method. Remove the setting afterwards.
2. **Prove "most specific wins".**
   - Add a temporary method `@ExceptionHandler(NotFoundException.class)` that returns status 418.
   - Run the test `notFoundException_returns404ProblemWithCode`. It fails, because your new, more specific method won.
   - Delete the method.
3. **See life without the parent class.**
   - Temporarily remove `extends ResponseEntityExceptionHandler` and the `handleExceptionInternal` method.
   - Run the malformed-JSON test. What comes back now? (Hint: the catch-all gets it, so you'll see a 500 instead of a 400.)
   - Put the code back.

---

## Remember these

- **Exception** = Java's "stop, something went wrong".
- **`@RestControllerAdvice`** = one class that catches errors from **all** controllers.
- **`@ExceptionHandler(X.class)`** = "this method handles X". The **most specific** match wins.
- Every error reply has the same shape, plus a **`code`** the app checks.
- We **write `detail` ourselves**, so we never leak SQL, stack traces, or user input.
- **`@Valid`** checks request fields before the method runs.
