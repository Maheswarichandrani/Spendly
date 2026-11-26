# 005: The security filter chain and JWT

Prompt: [`prompts/005-login-jwt.md`](../../prompts/005-login-jwt.md) · Before this, read [003](003-request-id-and-filters.md) (filters) and [004](004-entities-repositories-transactions.md) (transactions)

> **In short:** at login we give the user a **signed badge** (a JWT). On every later request, a row of security filters (the **security filter chain**) checks the badge **before** any controller runs. No valid badge means 401 at the door. A valid badge means the user's id is written in the **SecurityContext**, and `CurrentUser` reads it from there.

---

## 1. The idea (what and why)

### The problem

HTTP forgets everything between requests. After you log in, the next request (`GET /users/me`) arrives with no memory of the login. How does the server know who you are?

### Two classic answers

1. **Sessions:** the server remembers "session 42 = Ravi" and gives the browser a cookie with `42`. The server has to store sessions, and cookies bring CSRF problems.
2. **Tokens (our choice):** the server gives the app a **signed note** saying "this is user `0193...`, valid until 10:30". The app sends the note with every request. The server stores **nothing**; it only checks the signature.

**Real-life comparison:** a concert wristband. At the entrance (login) they check your ticket once and put a wristband on you. Inside, every guard just looks at the wristband. They don't call the ticket office each time. A fake wristband doesn't have the right hologram (the signature).

---

## 2. How it works inside

### Piece 1: what a JWT is

A JWT is three pieces of text joined by dots:

```
eyJraWQiOi...   .   eyJzdWIiOiIw...   .   Qm9Hc2lnbmF0dXJl...
   header               payload               signature
```

- **Header** (base64): `{"kid":"spendly-dev-1","alg":"RS256"}`: which key and which algorithm.
- **Payload** (base64): the **claims**: `{"sub":"0193...","iss":"spendly","iat":...,"exp":...,"jti":"..."}`.
- **Signature**: made from header + payload with our **private key**.

Important: base64 is **not** encryption. Anyone holding the token can read the payload (try it on jwt.io). That's why we put **only the user id** in it, and never the email or name.

### Piece 2: signing with RSA (private and public key)

RSA uses a **pair** of keys:
- The **private key** can **make** signatures. Only our server has it (`JWT_PRIVATE_KEY`).
- The **public key** can only **check** signatures. It's safe to share.

**Real-life comparison:** a wax seal. Only the king has the seal ring (private key). Everyone knows what the king's seal looks like (public key), so anyone can check a letter, but nobody can forge one.

Our code (`JwtKeys`) gets the public key out of the private key, because the private key file already contains the two numbers that make up the public key. One secret, no mismatch.

### Piece 3: making a token (`TokenService`)

```java
JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(properties.keyId()).build();
JwtClaimsSet claims = JwtClaimsSet.builder()
    .issuer("spendly")                  // who made it
    .subject(userId.toString())         // who it's about
    .issuedAt(now)
    .expiresAt(now.plus(15 minutes))    // useless after this
    .id(UUID.randomUUID().toString())   // unique id of this token
    .build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

`JwtEncoder` (a bean from `JwtConfig`) does the base64 and the RSA signature for us.

### Piece 4: the security filter chain

Remember the corridor of doors from 003. Spring Security adds **one** door, and behind that door is a whole **row of its own guards**:

```
Tomcat → [RequestIdFilter] → [Spring Security door] → DispatcherServlet → Controller
                                   │
                                   └─ inside: a row of security filters, for example
                                      BearerTokenAuthenticationFilter  (reads and checks the JWT)
                                      ExceptionTranslationFilter       (turns "no access" into a 401)
                                      AuthorizationFilter              (public or locked?)
```

- The door is a filter called `FilterChainProxy`.
- The **rules** for the guards come from our `SecurityFilterChain` bean in `SecurityConfig`. Spring Boot finds the bean and wires it in.

Our rules, line by line:

```java
.sessionManagement(s -> s.sessionCreationPolicy(STATELESS))   // never create a session or a cookie
.csrf(c -> c.disable())                                        // see "Why CSRF is off" below
.formLogin(f -> f.disable()).httpBasic(b -> b.disable())       // no login page, no password popup
.authorizeHttpRequests(auth -> auth
    .requestMatchers(POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
    .requestMatchers(GET, "/actuator/health", "/actuator/health/**").permitAll()
    .anyRequest().authenticated())                             // everything else: token required
.oauth2ResourceServer(rs -> rs.jwt(withDefaults()) ...)        // turn on the JWT guard
```

**Why CSRF is off.** CSRF means "a bad website makes your browser send a request to our server, and the browser automatically attaches your **cookie**". We use **no cookies**. The app puts the token in the `Authorization` header itself, and a bad website can't make your phone do that. So there's nothing for CSRF to steal. (If we ever used cookies, CSRF protection would have to come back.)

### Piece 5: checking a token (`JwtDecoder`)

For each request, `BearerTokenAuthenticationFilter`:
1. Reads the header `Authorization: Bearer eyJ...`.
2. Gives the token to our `JwtDecoder` bean, which checks:
   - **the algorithm is RS256** (pinned). A token saying `"alg":"none"` ("no signature needed") is rejected straight away. That's the most famous JWT attack.
   - **the signature** matches our public key.
   - **`exp`** is in the future (60 seconds of clock difference allowed).
   - **`iss`** is `spendly`.
3. If everything passes: it puts a `JwtAuthenticationToken` (meaning "this is user `0193...`") into the **SecurityContext**.
4. If anything fails: it calls our **entry point**, which gives a 401.

### Piece 6: the SecurityContext and `CurrentUser`

The **SecurityContext** is a per-request holder, just like the MDC from 003: one per thread, cleared after the request.

```java
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
if (auth instanceof JwtAuthenticationToken jwtAuth) {
    return UUID.fromString(jwtAuth.getToken().getSubject());   // the "sub" claim
}
```

`CurrentUser.id()` is the **only** place a user id may come from. The URL has no id (`/users/me`, not `/users/{id}`), so a user simply cannot ask for someone else's profile.

### Piece 7: 401s that look like every other error

Security filters run **before** the DispatcherServlet, so `GlobalExceptionHandler` (002) doesn't see their errors on its own. `ProblemDetailsAuthenticationEntryPoint` passes the error to Spring MVC's `HandlerExceptionResolver`, and that one calls our handler:

```java
@ExceptionHandler(AuthenticationException.class)
ResponseEntity<ProblemDetail> handleAuthentication(...)   // 401, code UNAUTHENTICATED, requestId, WWW-Authenticate: Bearer
```

The result is one place that builds every error body.

### Piece 8: `@ConfigurationProperties` (`JwtProperties`)

```java
@Validated
@ConfigurationProperties(prefix = "spendly.jwt")
public record JwtProperties(@NotBlank String issuer, @NotNull Duration accessTokenTtl,
                            @NotBlank String keyId, @NotBlank String privateKey) { }
```

Spring Boot reads `spendly.jwt.issuer`, `spendly.jwt.access-token-ttl=15m`, and the others from the properties files and the environment, and builds this record. Notice that `access-token-ttl` (properties style) maps to `accessTokenTtl` (Java style) automatically, and `15m` becomes a `Duration`.

`@Validated` checks the rules **at startup**. A missing key stops the app with a message naming `spendly.jwt.private-key`. A fast, clear failure beats a crash at the first login.

### Piece 9: `@Transactional(readOnly = true)`

The two new `UserService` methods only read. `readOnly = true` tells Hibernate "nothing will change", so at the end it skips checking every loaded object for changes. Small speed-up, and clearer intent.

---

## 3. One request, start to finish

### A. Login: `POST /api/v1/auth/login`

1. `RequestIdFilter` sets the request id.
2. The security door: the URL is in the `permitAll` list, so the guards let it pass without a token.
3. `AuthController.login`: `@Valid` checks `LoginRequest`.
4. `AuthService.login`:
   - lowercases the email
   - `userService.findLoginCredentials(email)` → a short read transaction → `Optional<LoginCredentials>`
   - unknown email, no password, or too long → a BCrypt check against the dummy hash (same time cost) → `401 INVALID_CREDENTIALS`
   - otherwise `passwordEncoder.matches(password, hash)` → wrong gives the same 401
   - correct → `tokenService.issueAccessToken(userId)`
5. `200 {"accessToken":"eyJ...","tokenType":"Bearer","expiresIn":900}`

### B. Profile: `GET /api/v1/users/me` with `Authorization: Bearer eyJ...`

1. `RequestIdFilter` sets the request id.
2. The security door → `BearerTokenAuthenticationFilter` → `JwtDecoder` checks the token → OK → the SecurityContext now holds user `0193...`.
3. `AuthorizationFilter`: the URL needs `authenticated()`, and the user is authenticated, so it passes.
4. `UserController.me` → `currentUser.id()` → `userService.getById(id)` (read-only transaction) → `UserResponse`.
5. `200` with the profile.

Without a token, step 3 fails → `ExceptionTranslationFilter` → our entry point → `GlobalExceptionHandler` → `401 UNAUTHENTICATED`. The controller never runs.

---

## 4. Why this design

| Choice | Rejected alternative | Why |
|---|---|---|
| Spring's resource server checks tokens | Hand-written JWT filter | Most JWT security bugs live in hand-written filters (alg none, missing exp check) |
| RS256 (private/public key) | HS256 (one shared secret) | With RSA, only the signer needs the secret; checking needs only the public key |
| 15-minute access token | Long-lived token | A stolen token is only useful briefly. Long sessions come from refresh tokens (006) |
| Only the user id in the token | Email/name/roles in the token | Anyone can read a JWT; also no stale data when the email changes |
| Same 401 and same timing for every login failure | "Email not found" / "Wrong password" | Different answers or speeds tell an attacker which emails exist |
| Stateless, no cookie | Server sessions | Nothing stored on the server; and no cookies means no CSRF |
| `/users/me` without an id | `/users/{id}` | The client can't even ask for someone else's id |

---

## 5. Common mistakes

- **Putting personal data in the JWT.** It's readable by anyone. Only ids.
- **Not pinning the algorithm.** Accepting whatever `alg` the token says opens the `alg: none` and key-confusion attacks.
- **Taking the user id from the request** (`?userId=`, path, body). Always use `CurrentUser`.
- **Different errors for "no such email" and "wrong password".** It's an account-enumeration leak.
- **Skipping the BCrypt check for unknown emails.** The answer arrives faster, which is the same leak through timing.
- **Disabling CSRF while using cookies.** It's only safe because we use no cookies.
- **Logging tokens.** A logged token is a stolen token. We log only `userId` and `jti`.
- **Committing the private key.** Anyone with it can sign tokens as any user. It lives only in `.env` or the hosting environment.

---

## 6. Try it

1. **Read your token.** Log in, paste the token into https://jwt.io. Find `sub`, `exp`, `kid`. Is there anything personal in it?
2. **Break the signature.** Change one character in the **middle part** of the token and call `/users/me`. You get 401. Why? (The signature no longer matches the payload.)
3. **Lock out health.** In `SecurityConfig`, remove the `/actuator/health` line and run `SecurityIntegrationTest.health_isPublic`. It fails with 401. Put it back.
4. **Startup check.** Remove `JWT_PRIVATE_KEY` from `.env` and start the app. Read the error. Put it back.
5. **Think about it:** if someone steals a token, how long can they use it? What would we need to stop them sooner? (Answer: up to 15 minutes. Revocation comes with refresh tokens in 006.)

---

## Remember these

- A **JWT** is a readable, **signed** note: header.payload.signature. Signed, not secret.
- **Private key signs, public key checks.** Only the server has the private key.
- The **security filter chain** runs **before** controllers. `permitAll` = public, `authenticated` = token needed.
- After the token is checked, the user lives in the **SecurityContext**; `CurrentUser.id()` reads it.
- Login failures: **same answer, same timing**, always.
- `@ConfigurationProperties` + `@Validated` = typed settings that fail fast at startup.
