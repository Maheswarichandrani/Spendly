# 002 — Error handling (RFC 9457 Problem Details)

## Goal

Every error the API returns, whether it comes from our code or from Spring MVC itself, is an `application/problem+json` body with a stable machine-readable `code` the mobile app can switch on. Stack traces, SQL, and class names never leak. This step teaches **how Spring MVC turns an exception into an HTTP response**.

Request-id logging (MDC), originally planned together with this, moves to **003** so that each step introduces one concept.

## Skills read

- `java-springboot`: `SKILL.md` and `references/rest-api.md` ("Errors: ProblemDetail"). The skill's pattern is: extend `ResponseEntityExceptionHandler` so MVC's own exceptions become Problem Details, add `code` with `setProperty`, never put internals in `detail`, and test with `jsonPath` plus `contentTypeCompatibleWith(APPLICATION_PROBLEM_JSON)`.

## Code inspected

- `backend/` after 001: only `SpendlyApplication`, no controllers. `spring-boot-starter-webmvc` and `spring-boot-starter-validation` are present (Boot 4.1.2, Spring Framework 7, Jackson 3).
- `application.properties` already has `server.error.include-stacktrace=never` and `include-message=never`. Those cover Boot's fallback `/error` page, not the API errors built here.

## Decisions and assumptions

1. **One advice class.** `GlobalExceptionHandler` is annotated `@RestControllerAdvice`. It extends `ResponseEntityExceptionHandler` and lives in `common/exception`.
2. **Codes live in one enum.** `ErrorCode` holds each code's HTTP status and default title. Codes are UPPER_SNAKE strings and are never renamed once shipped, because the app switches on them.
3. **Our own exceptions.**
   - An abstract `ApiException extends RuntimeException` carries an `ErrorCode` and a safe `detail` message.
   - It has three subclasses for now: `NotFoundException` (404), `ConflictException` (409), and `BusinessRuleException` (422, for "valid input, but the rule forbids it", such as an insufficient credit limit).
   - Features add specific codes, not new exception classes, unless the behaviour differs.
   - These exceptions extend `RuntimeException` so they can roll back `@Transactional` methods (003+), and checked exceptions would force noise through every layer.
4. **`type` stays `about:blank`.** RFC 9457 allows it. There's no public docs site to point a URI at, and the client relies on `code`. When there are docs, this can be changed without breaking clients.
5. **`instance` = request path.** It is set on every response for easier debugging. The request id is added in 003.
6. **Validation errors list the fields** as `errors: [{ "field", "message" }]`. Messages come from Bean Validation's defaults (for example "must not be blank"). Rejected values are **never** echoed back, because a field might be a password.
7. **Spring MVC's own exceptions** each get a `code` by overriding `handleExceptionInternal`. That is the single hook every built-in handler passes through:

   | Exception (examples) | Status | code |
   |---|---|---|
   | `MethodArgumentNotValidException`, `HandlerMethodValidationException` | 400 | `VALIDATION_FAILED` |
   | `HttpMessageNotReadableException` (malformed JSON, wrong type) | 400 | `MALFORMED_REQUEST` |
   | `TypeMismatchException` (for example a non-UUID path id), `MissingServletRequestParameterException` | 400 | `INVALID_PARAMETER` |
   | `NoResourceFoundException` (unknown route) | 404 | `NOT_FOUND` |
   | `HttpRequestMethodNotSupportedException` | 405 | `METHOD_NOT_ALLOWED` |
   | `HttpMediaTypeNotAcceptableException` | 406 | `NOT_ACCEPTABLE` |
   | `HttpMediaTypeNotSupportedException` | 415 | `UNSUPPORTED_MEDIA_TYPE` |
   | `MaxUploadSizeExceededException` | 413 | `PAYLOAD_TOO_LARGE` |
   | any other 4xx/5xx from the framework | as given | `BAD_REQUEST` / `INTERNAL_ERROR` by status family |

8. **Database safety net.** A `DataIntegrityViolationException` (a unique or foreign-key constraint fired) maps to 409 `CONFLICT` with a generic detail. Services should check rules explicitly and throw `ConflictException` with a precise code. This handler only catches races, such as two concurrent registrations with the same email.
9. **Catch-all.** Any other `Exception` returns 500 `INTERNAL_ERROR` with detail "Unexpected error." and is logged at ERROR with its stack trace on the server.
10. **Out of scope:** errors raised in servlet filters before MVC (for example 401 and 403 from Spring Security) never reach a `@RestControllerAdvice`. Step 004 adds Problem Details for those through Security's entry point and access-denied handler. Boot's `/error` fallback stays, with stack traces and messages already hidden.
11. **No new dependencies.**

## Files

| File | Who | Change |
|---|---|---|
| `common/exception/ErrorCode.java` | CLAUDE WRITES | enum: `code` (= name), `HttpStatus status`, `String title` |
| `common/exception/ApiException.java` | CLAUDE WRITES | abstract, `ErrorCode errorCode`, safe message |
| `common/exception/NotFoundException.java` | CLAUDE WRITES | default `NOT_FOUND`; constructor that takes a more specific code |
| `common/exception/ConflictException.java` | CLAUDE WRITES | `CONFLICT` family |
| `common/exception/BusinessRuleException.java` | CLAUDE WRITES | `BUSINESS_RULE_VIOLATION` family (422) |
| `common/exception/ValidationError.java` | CLAUDE WRITES | `record ValidationError(String field, String message)`. Not named `FieldError`, to avoid clashing with Spring's class |
| `common/exception/GlobalExceptionHandler.java` | CLAUDE WRITES | the advice (Decisions 1, 5–9) |
| `src/test/.../common/exception/ErrorProbeController.java` | CLAUDE WRITES | **test-only** controller that triggers each error type (there are no real endpoints yet) |
| `src/test/.../common/exception/GlobalExceptionHandlerTest.java` | CLAUDE WRITES (2 examples) + **USER WRITES** (rest) | `@WebMvcTest` |
| `docs/learn/002-exception-handling.md` | CLAUDE WRITES | learning note |

All main paths are under `backend/src/main/java/com/sm/spendly/`.

## Requirements

### Response shape (every error)

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "2 fields are invalid.",
  "instance": "/api/v1/accounts",
  "code": "VALIDATION_FAILED",
  "errors": [
    { "field": "name", "message": "must not be blank" },
    { "field": "amount", "message": "must be greater than 0" }
  ]
}
```

- `errors` is present only for `VALIDATION_FAILED`.
- `type` is omitted by Spring when it is `about:blank`. RFC 9457 treats a missing `type` as `about:blank`.
- `Content-Type: application/problem+json`.

### Initial `ErrorCode` values

`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `INVALID_PARAMETER`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE`, `PAYLOAD_TOO_LARGE`, `CONFLICT`, `BUSINESS_RULE_VIOLATION`, `BAD_REQUEST`, `INTERNAL_ERROR`.

Feature slices add their own codes (for example `EMAIL_ALREADY_REGISTERED` in 004).

### Test-only `ErrorProbeController` (under `/test/errors`)

| Endpoint | Behaviour |
|---|---|
| `GET /not-found` | throws `NotFoundException` |
| `GET /conflict` | throws `ConflictException` |
| `GET /business-rule` | throws `BusinessRuleException` |
| `POST /validate` | body `record ProbeRequest(@NotBlank String name, @Positive BigDecimal amount)` with `@Valid` |
| `GET /items/{id}` | `@PathVariable UUID id` |
| `GET /boom` | throws `IllegalStateException("secret internal detail")` |
| `GET /integrity` | throws `DataIntegrityViolationException("duplicate key value violates unique constraint users_email_key")` |

## API contract

There are no new production endpoints. The contract is the error shape above, which every future endpoint inherits. Status codes used: 400, 404, 405, 406, 409, 413, 415, 422, 500.

## Database changes

None.

## Security considerations

- `detail` is always a message we wrote. Raw exception messages from framework, JDBC, or Jackson exceptions are never copied into the response. They can contain SQL, table names, or class names. Probe tests assert on `boom` and `integrity`.
- Rejected values are never echoed back in validation errors.
- 404 for an unknown id and 404 for an unknown route look alike. Ownership checks (AGENTS §5) will rely on this, so a foreign id returns 404 and can't be probed.

## Spring concepts this step teaches

1. **The `DispatcherServlet` exception path.** When a controller throws, `HandlerExceptionResolver`s are tried in order: `ExceptionHandlerExceptionResolver` (your `@ExceptionHandler`s) → `ResponseStatusExceptionResolver` → `DefaultHandlerExceptionResolver`.
2. **`@RestControllerAdvice` and `@ExceptionHandler` matching.** The handler for the closest exception type wins, which is why the `Exception` catch-all doesn't swallow everything.
3. **`ResponseEntityExceptionHandler` and `ProblemDetail`.** Covers the template method `handleExceptionInternal` and how Jackson 3 flattens `ProblemDetail.properties` into top-level JSON fields.
4. **Bean Validation at the web boundary.** `@Valid` on `@RequestBody` throws `MethodArgumentNotValidException`; constraints on path or query params throw `HandlerMethodValidationException`.
5. **The `@WebMvcTest` slice and `MockMvc`.** This test starts only the web layer, with no database and no Docker.

## Tests

`@WebMvcTest(ErrorProbeController.class)` with `@Import(GlobalExceptionHandler.class)` if needed, plus `MockMvc` and `jsonPath`.

| Test | Who | Expected |
|---|---|---|
| `notFoundException_returns404ProblemWithCode` | Claude (example) | 404, problem+json, `code=NOT_FOUND`, `instance=/test/errors/not-found` |
| `invalidBody_returns400WithFieldErrors` | Claude (example) | 400, `code=VALIDATION_FAILED`, `errors` has 2 entries with `field` names `name` and `amount`, no rejected values |
| `conflictException_returns409` | **User** | 409, `code=CONFLICT` |
| `businessRuleException_returns422` | **User** | 422, `code=BUSINESS_RULE_VIOLATION` |
| `malformedJson_returns400MalformedRequest` | **User** | POST `{"name":` gives 400 `MALFORMED_REQUEST` |
| `nonUuidPathVariable_returns400InvalidParameter` | **User** | `/items/abc` gives 400 `INVALID_PARAMETER` |
| `unknownRoute_returns404NotFound` | **User** | `/test/errors/nope` gives 404 `NOT_FOUND` |
| `wrongMethod_returns405` | **User** | `DELETE /test/errors/not-found` gives 405 `METHOD_NOT_ALLOWED` |
| `wrongContentType_returns415` | **User** | POST `/validate` with `text/plain` gives 415 `UNSUPPORTED_MEDIA_TYPE` |
| `unexpectedException_returns500WithoutLeakingMessage` | **User** | 500 `INTERNAL_ERROR`, and the body does **not** contain `secret internal detail` |
| `dataIntegrityViolation_returns409WithoutLeakingSql` | **User** | 409 `CONFLICT`, and the body does **not** contain `users_email_key` |

Hints:
- Copy the structure of the two example tests.
- To assert something is absent, use `content().string(not(containsString(...)))` (Hamcrest).
- The unknown-route test needs no extra config. Boot 4 throws `NoResourceFoundException` (see learning note §2).

There is no ownership test (no user data yet).

## Logging

| Case | Level | Content |
|---|---|---|
| 4xx from `ApiException` or validation | DEBUG | code, path. Client mistakes would otherwise flood the logs |
| 409 from `DataIntegrityViolationException` | WARN | code, path, **constraint name only** (not the full SQL message) |
| 500 catch-all | ERROR | code, path, full stack trace (server side only) |

Never logged: request bodies, header values, or validation rejected values. A password could be among them.

## Transactions

None in this step. Note for 004: an `ApiException` thrown inside a `@Transactional` service method rolls the transaction back, because it is unchecked. This handler runs **after** the transaction has already ended.

## Acceptance criteria

- [ ] Every probe endpoint returns `application/problem+json` with `status`, `title`, `detail`, `instance`, and `code`.
- [ ] No response contains an exception's internal message, SQL, class names, or rejected values.
- [ ] All 11 tests pass. `./mvnw -q verify` passes, and the 001 tests still pass.
- [ ] `ErrorProbeController` exists only in `src/test`, not in the production jar.
- [ ] The learning note exists and covers the five concepts.

## Checks

From `backend/`:
1. `./mvnw -q verify`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then hit real routes for the manual steps below. The probe controller isn't in the main app, so the manual checks use framework errors.

## Manual test steps

1. Start the app with the dev profile.
2. `curl -i localhost:8080/api/v1/nope` should return `404`, `Content-Type: application/problem+json`, and `"code":"NOT_FOUND"`.
3. `curl -i -X DELETE localhost:8080/actuator/health` should return `405` with `"code":"METHOD_NOT_ALLOWED"`. If Actuator answers with its own format, note it: Actuator endpoints are not MVC controllers, and the learning note explains why.
4. Run the tests: `./mvnw -q test -Dtest=GlobalExceptionHandlerTest`.
