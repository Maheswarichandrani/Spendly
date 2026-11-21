# 003 — Request id in every log line

## Goal (in short)

Give every HTTP request a short **id**, such as `7f3a9c2e-...`, and print that id on **every log line** the request produces. Return it to the caller in the `X-Request-Id` header, and include it in every error body as `requestId`.

**Why it matters:** suppose a user says "the app showed an error". The error screen can show the `requestId`. We search the logs for that id and see everything that happened for that one request, even when hundreds of other requests were logging at the same time.

**Real-life comparison:** a courier tracking number. Every scan at every warehouse is recorded with the same number, so you can follow one parcel's whole journey.

## Skills read

- `java-springboot`: `SKILL.md` (constructor injection, logs never contain secrets) and `references/configuration.md` (logging properties).
- The skill has no specific MDC recipe, so this follows the Spring Boot logging docs for 4.1. The exact property names are checked against the docs while implementing.

## Code inspected

- `GlobalExceptionHandler` (002) builds every error body in one `problem(...)` helper and in `handleExceptionInternal`. We add `requestId` in those two places.
- There are no filters yet, and the log pattern is Boot's default.
- Branch: `feature/exception-handling` (not merged into `main` yet).

## Decisions

1. **Filter, not controller code.** A **filter** is code that runs **before** the request reaches any controller and **after** the response leaves it, like a security guard at the building door. Every request passes it, so it's the right place to stamp the id.
2. **Where the id comes from:**
   - If the request already has an `X-Request-Id` header **and** it looks safe (only letters, digits, and `-`, 8 to 64 characters), we reuse it. The mobile app can then send its own id and match its logs with ours.
   - Otherwise we create a new random UUID.
   - Unsafe values are ignored, never logged. This stops someone from sneaking fake lines into our logs (called *log injection*).
3. **MDC.** MDC (Mapped Diagnostic Context) is a small notepad attached to the **current thread**. Whatever we write there (`requestId=...`) is printed automatically on every log line from that thread. We **must** clear it when the request ends, because the thread is reused for the next request.
4. **Log format.** We add `[requestId]` to each log line through Boot's logging pattern property. There is no custom `logback.xml`, to keep it simple.
5. **One summary line per request**, at INFO: `GET /api/v1/x -> 404 in 12 ms`. Rules for it:
   - Path only, never the query string, since it could contain search text or an email.
   - `/actuator/**` requests are logged at DEBUG instead, so health checks don't flood the log.
6. **Error bodies get `requestId`**, read from MDC inside `GlobalExceptionHandler`.
7. **Filter order:** first, before any other filter. Even a request that fails early (for example the security check in 004) gets an id.
8. **Git:** new branch `feature/request-id-logging`, created from `feature/exception-handling`, because this step changes the error handler. Once the 002 PR is merged, the 003 PR shows only this step's changes.

## Files

All paths are under `backend/src/`.

| File | Who | Change |
|---|---|---|
| `main/java/com/sm/spendly/common/logging/RequestIdFilter.java` | CLAUDE WRITES | the filter (Decisions 1–3, 5, 7) |
| `main/java/com/sm/spendly/common/exception/GlobalExceptionHandler.java` | CLAUDE WRITES | add `requestId` to every error body |
| `main/resources/application.properties` | CLAUDE WRITES | log pattern shows `[requestId]` |
| `test/java/com/sm/spendly/common/logging/RequestIdFilterTest.java` | see Tests | filter tests |
| `test/java/com/sm/spendly/common/exception/GlobalExceptionHandlerTest.java` | see Tests | one extra test: error body has `requestId` |
| `docs/learn/003-request-id-and-filters.md` (repo root) | CLAUDE WRITES | learning note, simple style |

## What the API looks like

There are no new endpoints. Every response gets this header:

```
X-Request-Id: 7f3a9c2e-1b4d-4c8e-9f00-3a2b1c0d9e8f
```

Every error body gets one more field:

```json
{
  "title": "Not found",
  "status": 404,
  "detail": "No resource exists at this path.",
  "instance": "/api/v1/nope",
  "code": "NOT_FOUND",
  "requestId": "7f3a9c2e-1b4d-4c8e-9f00-3a2b1c0d9e8f"
}
```

A log line looks like this (the shape is shown; exact spacing follows Boot's pattern):

```
2026-11-20T10:15:02 INFO [7f3a9c2e-...] RequestIdFilter : GET /api/v1/nope -> 404 in 3 ms
```

## Database changes

None.

## Security

- A client-sent id is used only if it matches `^[A-Za-z0-9-]{8,64}$`. Anything else, such as line breaks or very long text, is replaced with a fresh UUID. This blocks log injection.
- The summary line logs the method, path, status, and time only. It never logs the query string, headers, or body.
- The request id is not a secret, so it's fine to show it to the user.

## Spring concepts this step teaches (simple words)

1. **Servlet filter:** code that wraps every request, running before and after the controller. Covers `OncePerRequestFilter`, which runs exactly once per request even if the request is forwarded internally.
2. **Filter order:** `@Order` decides who runs first when there are several filters.
3. **MDC:** a per-thread notepad that logging reads automatically. Includes why it **must** be cleared in `finally`.
4. **Logging pattern properties:** how Spring Boot builds a log line and how we add our field without a `logback.xml`.

## Tests

| Test | Expected |
|---|---|
| `generatesRequestId_whenHeaderMissing` | response has an `X-Request-Id` header in UUID form |
| `reusesRequestId_whenHeaderIsSafe` | sent `abc12345-xyz`, got the same value back |
| `ignoresRequestId_whenHeaderIsUnsafe` | sent `bad\nvalue` or a 200-character string, got a fresh UUID, not the sent value |
| `clearsMdc_afterRequest` | after the request finishes, `MDC.get("requestId")` is `null` |
| `errorBody_containsSameRequestIdAsHeader` (in `GlobalExceptionHandlerTest`) | `$.requestId` equals the `X-Request-Id` response header |

CLAUDE WRITES all tests (your choice, 2026-11-20).

There's no ownership test, because there's no user data yet.

## Logging

| What | Level | Content |
|---|---|---|
| Request summary | INFO (DEBUG for `/actuator/**`) | method, path, status, duration in ms |
| Unsafe incoming id | DEBUG | "ignored unsafe X-Request-Id" (the value itself is never printed) |

## Transactions

None in this step.

## Acceptance criteria

- [ ] Every response has `X-Request-Id`.
- [ ] Every log line during a request shows the same `[id]`.
- [ ] Error bodies have `requestId`, and it matches the header.
- [ ] A safe client id is reused and an unsafe one is replaced.
- [ ] MDC is empty after the request.
- [ ] `./mvnw -q verify` passes, and all earlier tests still pass.
- [ ] The learning note is written in the simple style.

## Checks to run

From `backend/`:
1. `./mvnw -q verify`
2. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`, then the manual steps below.

## Manual test steps

1. Start the app with the dev profile.
2. Run `curl -i localhost:8080/api/v1/nope`.
   - The headers include `X-Request-Id: <uuid>`.
   - The body's `requestId` has the same value.
   - The app log has a line `GET /api/v1/nope -> 404 in N ms` with that same id in brackets.
3. Run `curl -i -H "X-Request-Id: my-test-12345" localhost:8080/api/v1/nope`.
   - The response header and body both show `my-test-12345`.
4. Run `curl -i -H "X-Request-Id: bad!!" localhost:8080/api/v1/nope`.
   - You get a fresh UUID, not `bad!!`.
5. Run `curl localhost:8080/actuator/health`.
   - No INFO summary line appears (it's logged at DEBUG only).
6. Commit to `feature/request-id-logging` and push. I give you the PR link.
