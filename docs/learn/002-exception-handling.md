# 002 — How Spring MVC turns an exception into a response

Prompt: [`prompts/002-error-handling.md`](../../prompts/002-error-handling.md) · Builds on [001](001-how-spring-boot-boots.md) (DispatcherServlet, auto-configuration)

---

## 1. The concept

Errors are part of the API contract. The mobile app must be able to tell "this email is taken" from "your session expired" from "the server crashed" **without parsing English text**. We need two things:

- **One format.** RFC 9457 *Problem Details* is a standard JSON shape (`type`, `title`, `status`, `detail`, `instance`) with the media type `application/problem+json`. We add one field: `code`, a stable string the app can `switch` on.
- **One place that produces it.** Without that place, every controller would wrap its code in `try/catch` and build error JSON by hand, and each one would do it slightly differently.

Spring gives us that one place: a `@RestControllerAdvice` class whose `@ExceptionHandler` methods catch whatever escapes *any* controller.

## 2. How Spring does it under the hood

### The DispatcherServlet's exception path

In 001 you saw that every request goes through `DispatcherServlet`. When the controller (or argument binding before it, or JSON writing after it) throws, `DispatcherServlet.processHandlerException` asks a list of **`HandlerExceptionResolver`s**, in order, "can you handle this?":

```
exception thrown
 │
 ├─ 1. ExceptionHandlerExceptionResolver
 │      Looks for an @ExceptionHandler method: first in the controller class itself,
 │      then in every @ControllerAdvice / @RestControllerAdvice bean.
 │      ← our GlobalExceptionHandler is found here
 ├─ 2. ResponseStatusExceptionResolver   (@ResponseStatus on exception classes)
 └─ 3. DefaultHandlerExceptionResolver   (Spring's built-in fallback for its own exceptions,
                                           empty body + status code)
 │
 none handled it → exception propagates to Tomcat → Boot's /error page (BasicErrorController)
```

Because our advice extends `ResponseEntityExceptionHandler`, which has `@ExceptionHandler` methods for **all** of Spring MVC's standard exceptions, resolver 1 catches everything. Resolvers 2 and 3 never get a turn.

### Which `@ExceptionHandler` wins

Spring picks the handler whose declared exception type is **closest** in the class hierarchy to the thrown exception:

- `NotFoundException` → `ApiException` is 1 step up, `Exception` is 3 steps up, so `handleApiException` wins.
- `IllegalStateException` → only `Exception` matches, so `handleUnexpected` runs.

That's why a catch-all `Exception.class` handler is safe. It only receives what nothing more specific claims.

### `ResponseEntityExceptionHandler`: a template method

The parent class has one method per framework exception (`handleMethodArgumentNotValid`, `handleHttpRequestMethodNotSupported`, `handleNoResourceFoundException`, …). Each one builds a `ProblemDetail`, then **all of them** call one protected hook:

```java
protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
        HttpHeaders headers, HttpStatusCode statusCode, WebRequest request)
```

This is the *template method* pattern. We override only that single hook in `GlobalExceptionHandler`, and every framework error automatically gets our `code`, our safe `detail`, `instance`, and validation `errors`. We didn't have to override a dozen methods.

### How `ProblemDetail` becomes JSON

`ProblemDetail` is a plain Spring class with getters plus a `Map<String, Object> properties`. Spring registers a Jackson 3 *mixin* for it that:
- writes `properties` entries (`code`, `errors`) as **top-level** fields, not nested under `"properties"`;
- **omits `type` when it is `about:blank`** (you saw this in the responses). RFC 9457 says an absent `type` means `about:blank`, so this is correct.

When a handler returns a `ProblemDetail` (or `ResponseEntity<ProblemDetail>`), Spring picks the `application/problem+json` content type automatically.

### Bean Validation at the web boundary

`spring-boot-starter-validation` brings Hibernate Validator. When a parameter is annotated `@Valid @RequestBody ProbeRequest request`:

1. Jackson reads the JSON into the record. If the JSON is broken, you get `HttpMessageNotReadableException` → `MALFORMED_REQUEST`.
2. The validator checks the record's constraints (`@NotBlank`, `@Positive`).
3. If any fail, the controller method is **never called**. `MethodArgumentNotValidException` is thrown with a `BindingResult` that holds every failure, and that becomes `VALIDATION_FAILED` with the `errors` list.

Constraints directly on `@PathVariable` or `@RequestParam` parameters (for example `@RequestParam @Max(100) int size`) fail with `HandlerMethodValidationException` instead. We map both to the same code.

### Why the Actuator 405 also came back as Problem Details

Actuator's HTTP endpoints are registered through a special `HandlerMapping` inside the same `DispatcherServlet`. A `DELETE` on `/actuator/health` is rejected by the dispatcher with `HttpRequestMethodNotSupportedException`, which goes down the same resolver path as above, so our advice formats it too.

### Why unknown routes work without extra config

Since Framework 6.1, a request that matches no controller falls through to the static-resource handler. When that handler finds no file, it throws `NoResourceFoundException`, which our advice maps to `NOT_FOUND`. Older guides tell you to set `spring.mvc.throw-exception-if-no-handler-found`. With Boot 4 that isn't needed.

## 3. The request walk-through

`POST /test/errors/validate` with `{ "name": "", "amount": -5 }`. In the tests this runs through `MockMvc`, which drives a real `DispatcherServlet` without opening a network socket.

1. **`DispatcherServlet`** finds the handler `ErrorProbeController.validate` through `RequestMappingHandlerMapping`.
2. **`RequestResponseBodyMethodProcessor`** (the argument resolver for `@RequestBody`) uses Jackson 3 to build `ProbeRequest("", -5)`.
3. Because of `@Valid`, it runs **Hibernate Validator**, which reports two violations. It throws `MethodArgumentNotValidException`. The controller method never runs.
4. `DispatcherServlet` asks **`ExceptionHandlerExceptionResolver`**, which finds `ResponseEntityExceptionHandler.handleMethodArgumentNotValid` (inherited by `GlobalExceptionHandler`).
5. That method calls our **`handleExceptionInternal`**:
   - `codeFor` → `VALIDATION_FAILED`
   - `validationErrors` → `[amount: must be greater than 0, name: must not be blank]`, sorted by field
   - builds the `ProblemDetail` with `code`, `instance`, `errors`; logs at DEBUG
6. Jackson serializes it as `application/problem+json` with status 400.

No database is involved. This is the web layer only, which is exactly what `@WebMvcTest` loads.

## 4. Why this design

| Choice | Rejected alternative | Why |
|---|---|---|
| One `@RestControllerAdvice` | `try/catch` in controllers | Duplicated, inconsistent, and easy to forget. Controllers stay thin (AGENTS §5). |
| Extend `ResponseEntityExceptionHandler` | `spring.mvc.problemdetails.enabled=true` only | The property gives Problem Details for framework errors but no `code` field and no control over `detail`. Extending gives both. Boot's built-in advice backs off automatically when we define our own (`@ConditionalOnMissingBean`, from 001). |
| Stable `code` enum | Clients parse `title` or `detail` | Text changes (wording, translation) and would break the app. Codes don't change. |
| Our own `detail` text for framework errors | Spring's default detail | Spring's text can echo input, e.g. `Failed to convert value 'abc'…`. Echoing input can leak data, and it's how attackers probe. |
| Few exception classes plus many codes | One class per error (`EmailTakenException`, …) | Classes only matter when *behaviour* differs (status family). Codes are cheap. |
| Unchecked `ApiException` | Checked exceptions | `@Transactional` rolls back on unchecked exceptions by default (004). Checked exceptions would add `throws` to every layer. |
| `@WebMvcTest` for handler tests | `@SpringBootTest` | The full context needs Docker and takes about 25 s. The slice starts in about 3 s and tests only what we're testing. |

## 5. Common mistakes

- **Catch-all returning `ex.getMessage()`.** One NullPointerException or SQL error later, you're leaking internals. Our `boom` and `integrity` probe tests exist to prevent this.
- **Echoing `rejectedValue` in validation errors.** That field could be a password.
- **Two advice classes both handling `Exception`.** The order is undefined unless you use `@Order`. Keep one advice.
- **Expecting the advice to catch filter errors.** Exceptions thrown in a servlet filter, such as Spring Security's 401 and 403, happen *before* `DispatcherServlet` and never reach `@RestControllerAdvice`. 004 handles those separately.
- **Logging every 4xx at WARN or ERROR.** Clients send bad input all the time, so the real errors drown. Use DEBUG for client errors and ERROR for our bugs.
- **Annotating the advice with `@ControllerAdvice` (not `Rest…`)** and returning objects. Without `@ResponseBody` semantics, Spring tries to render a view.

## 6. Try it

1. **Watch the resolver choice:**
   - Temporarily add `logging.level.org.springframework.web=DEBUG` to `application-dev.properties`, run the app, and call `curl -i localhost:8080/api/v1/nope`.
   - Find the log line naming `ExceptionHandlerExceptionResolver` and the handler method it chose. Remove the setting afterwards.
2. **Prove the "closest match" rule:**
   - Add a temporary `@ExceptionHandler(NotFoundException.class)` method that returns status 418.
   - Run `notFoundException_returns404ProblemWithCode`. It fails, because the more specific handler won. Remove the method.
3. **Without the parent class:**
   - Temporarily remove `extends ResponseEntityExceptionHandler` (and the `@Override` method), then run the malformed-JSON test.
   - What status and body come back, and which resolver from section 2 produced them? Restore the code.
4. **Write the 9 tests** listed in the prompt. When they pass, send me the file to review.
