# 003: Request id, filters, and MDC

Prompt: [`prompts/003-request-id-logging.md`](../../prompts/003-request-id-logging.md) · Before this, read [002](002-exception-handling.md)

> **In short:** a **filter** (`RequestIdFilter`) stands at the door of the app. It gives every request an id, writes the id on a per-thread notepad (**MDC**), and every log line prints that id automatically. The same id goes back to the caller in the `X-Request-Id` header and in every error body.

---

## 1. The idea (what and why)

### The problem

Our server handles many requests at the same time, and they all write to one log. It looks like this:

```
INFO  ... GET /api/v1/accounts -> 200
ERROR ... Unexpected error
INFO  ... POST /api/v1/transactions -> 201
ERROR ... Unexpected error
```

A user says "I got an error at 10:15". **Which** of those ERROR lines was theirs? We can't tell.

### The solution

We give each request a unique id and print it on every line:

```
INFO  [74f1dd58-...] GET /api/v1/accounts -> 200
ERROR [3e8cc74b-...] Unexpected error
INFO  [3e8cc74b-...] GET /test/errors/boom -> 500
```

The user's error screen shows `requestId: 3e8cc74b-...`. We search the log for it and see **only that request's story**.

**Real-life comparison:** a courier tracking number. Every warehouse scan carries the same number, so you can follow one parcel even though thousands are moving at once.

---

## 2. How it works inside

There are three pieces. Each one is new.

### Piece 1: the servlet filter

Recall from [001](001-how-spring-boot-boots.md): Tomcat receives the HTTP request and hands it to Spring's `DispatcherServlet`, which finds the controller.

A **filter** sits **between Tomcat and the DispatcherServlet**. Picture a corridor of doors. Each filter is one door:

```
Tomcat → [RequestIdFilter] → [other filters…] → DispatcherServlet → Controller
                ↑                                                        │
                └──────────────────── response comes back ───────────────┘
```

Each filter has one method. It receives the request and a `chain` ("the rest of the corridor"):

```java
// before: runs BEFORE the controller
chain.doFilter(request, response);   // ← go through the rest of the corridor
// after: runs AFTER the controller finished
```

So one method can act **before and after**. That's why a filter fits this job: before the controller we create the id, and after it we log "took 12 ms".

**`OncePerRequestFilter`.** Sometimes a request travels the corridor twice. For example, Spring forwards it internally to `/error`. This Spring base class remembers "already done" and runs our code **exactly once**. We write `doFilterInternal` instead of `doFilter`.

**How does Spring find our filter?** It has `@Component`. During startup, component scanning (see 001) creates the bean. Spring Boot sees that the bean **is a `Filter`** and registers it with Tomcat for every URL automatically. No XML and no extra config.

### Piece 2: filter order (`@Order`)

When there are several filters, who goes first? `@Order(Ordered.HIGHEST_PRECEDENCE)` means **first in the corridor**, because a smaller number runs earlier.

Why first? In step 004 we add Spring Security, which is itself a filter. If security rejects a request ("not logged in"), we still want that rejection logged **with an id**. So our filter must stand before security's door.

### Piece 3: MDC (the per-thread notepad)

**MDC** = *Mapped Diagnostic Context*. It's a small map, `key → value`, that belongs to **the current thread**.

Remember that Tomcat runs each request on one worker thread (like `nio-8080-exec-5` in the log). While a request is being handled, everything (filter, controller, service) runs on that **same thread**. So:

```java
MDC.put("requestId", "74f1dd58-...");   // write on this thread's notepad
log.info("hello");                       // Logback reads the notepad and prints the id
```

Logback (Boot's default logging library) reads the notepad every time it prints a line. We told it to print the id with one property:

```properties
logging.pattern.level=%5p [%X{requestId:-}]
```

- `%5p` is the level (`INFO`, `ERROR`), padded to 5 characters. That's Boot's normal level part.
- `%X{requestId}` means "print the MDC value for the key `requestId`".
- `:-` means "if there's none, print nothing". Startup lines have no request, so they show `[]`.

We only replaced the **level** part of Boot's default log line, so the rest (time, thread, class) stays as before and we need no `logback.xml`.

### The big trap: clearing the notepad

Tomcat **reuses threads**. The thread `exec-5` handles request A, then request B. If we forget to clear the notepad, request B's log lines show **A's id**. That's very confusing, and it's a wrong record.

That's why the code is:

```java
MDC.put(MDC_KEY, requestId);
try {
    chain.doFilter(request, response);
} finally {
    logSummary(...);
    MDC.remove(MDC_KEY);   // ALWAYS runs, even if the controller crashed
}
```

`finally` runs **no matter what**: on success or on an exception. The test `clearsMdc_evenWhenControllerThrows` proves it.

---

## 3. One request, step by step

`curl -i -H "X-Request-Id: my-test-12345" localhost:8080/api/v1/nope`

1. **Tomcat** receives it on thread `exec-7`.
2. **`RequestIdFilter.doFilterInternal`** runs first:
   - It reads the header `my-test-12345` and checks it against `^[A-Za-z0-9-]{8,64}$`. It matches, so it keeps it.
   - `MDC.put("requestId", "my-test-12345")`.
   - `response.setHeader("X-Request-Id", ...)`. This happens **now**, because once the body starts being written, headers can't be added any more.
   - It calls `chain.doFilter(...)`.
3. **DispatcherServlet** finds no controller for `/api/v1/nope` and throws `NoResourceFoundException`.
4. **`GlobalExceptionHandler`** (from 002) builds the error. Inside `problem(...)` it calls `MDC.get("requestId")`. That's the same thread, so it gets `my-test-12345` and adds `"requestId": "my-test-12345"` to the body.
5. Back in the filter's `finally`:
   - It logs `GET /api/v1/nope -> 404 in 7 ms`. Logback prints `[my-test-12345]` in front.
   - `MDC.remove("requestId")`. The notepad is clean for the next request on `exec-7`.

Real output from the manual test:

```
INFO [my-test-12345] ... RequestIdFilter : GET /api/v1/nope -> 404 in 7 ms
```

```json
{"detail":"No resource exists at this path.", ..., "code":"NOT_FOUND","requestId":"my-test-12345"}
```

---

## 4. Why this design

| Rejected option | Why not |
|---|---|
| Create the id in each controller | We'd have to remember it everywhere, and requests that fail before a controller (404s, security) would get no id. |
| A Spring MVC `HandlerInterceptor` | It runs **inside** the DispatcherServlet, so it comes after security. It would miss requests that security rejects. |
| Pass the id as a method parameter everywhere | Every method signature gets polluted. MDC carries it invisibly. |
| Always trust the client's header | A client could send `abc\nERROR fake line` and forge log lines (log injection). So we only accept safe characters. |
| A custom `logback.xml` | It works, but it's a whole extra file. One property does the job. |
| Spring's tracing (Micrometer Tracing) | That's for many services calling each other. We have one backend. It's too much for now. |

---

## 5. Common mistakes

- **Forgetting `finally` / `MDC.remove`.** The next request on the same thread logs the wrong id.
- **Setting the header after `chain.doFilter`.** The response may already be sent, and the header is silently dropped.
- **Logging the query string** (`getRequestURL()` plus the query). Search text or emails end up in logs. We use `getRequestURI()`, which is the path only.
- **Logging the rejected unsafe header value.** That's exactly the injection we're blocking. We only log "ignored".
- **Background threads.** MDC belongs to *one* thread. If code later starts work on another thread (`@Async`, schedulers), that thread's notepad is empty. We'll handle that when we get there.
- **Not ordering the filter first.** Requests rejected by earlier filters have no id.

---

## 6. Try it

1. Start the app (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`). Run `curl -i localhost:8080/api/v1/nope` three times. Every log line has a different id, and each one matches its response header.
2. **Break it on purpose:** in `RequestIdFilter`, comment out `MDC.remove(MDC_KEY);` and run `./mvnw test -Dtest=RequestIdFilterTest`. Which tests fail, and why? (Put the line back afterwards.)
3. Change `logging.pattern.level` to `%5p [req=%X{requestId:-none}]`, restart, and look at the startup lines and request lines. What changed?
4. Send `curl -i -H "X-Request-Id: hello world 123" localhost:8080/api/v1/nope`. Why don't you get `hello world 123` back? (Hint: look at the regex.)
