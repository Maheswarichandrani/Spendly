# 006: API docs with Swagger (OpenAPI + springdoc), explained simply

This note explains, in plain words, **what** we built in step 006, **why**, and **every line
of code and every annotation**. Read it top to bottom. Each part builds on the one before.

---

## Part 1. The big picture

### The problem
Your friend is building the Flutter app. To call our API, they must know, for **every** URL:

- the URL and method (`POST /api/v1/auth/login`);
- what JSON to send (`email`, `password`) and the rules (email required, max 254 characters);
- what JSON comes back on success;
- which errors can come back (`401 INVALID_CREDENTIALS`, ...);
- whether a token is needed.

We could write all that in a Word document. But the day we change a field, the document
is wrong, and nobody notices.

### The solution
Let the **code describe itself**. A library reads our controllers and DTOs while the app
is running and produces the documentation automatically. Change the code, and the docs
change too.

### A simple analogy
Think of a restaurant:

| Restaurant | Our app |
|---|---|
| The kitchen (what is really cooked) | Our controllers and DTOs |
| A **menu printed by the kitchen itself**, always matching what it cooks | The **OpenAPI spec**, a JSON file at `/v3/api-docs` |
| A nice menu board where you can order a sample | **Swagger UI**, a web page at `/swagger-ui.html` with "Try it out" buttons |
| The person who prints the menu | **springdoc**, the library that reads our code and writes the spec |
| Notes the chef adds to the menu ("spicy", "contains nuts") | **Annotations** like `@Operation` and `@Schema` that add human text |

### Three words to keep apart
1. **OpenAPI**: a *standard format* (a set of rules) for describing an API in JSON. Like
   "the standard layout of a menu".
2. **springdoc**: the *Java library* that reads our Spring code and writes an OpenAPI file.
3. **Swagger UI**: a *web page* that reads an OpenAPI file and draws it nicely, with
   buttons to try each endpoint. springdoc includes it.

---

## Part 2. What you see in the browser

Start the app with the `dev` profile and open `http://localhost:8080/swagger-ui.html`:

```
┌─────────────────────────────────────────────────────────────┐
│ Spendly API  v1                                [Authorize 🔓]│ ← from OpenApiConfig (title, Authorize button)
│ Personal finance API for the Spendly mobile app...           │ ← from OpenApiConfig (description)
├─────────────────────────────────────────────────────────────┤
│ Auth   Register, log in, refresh tokens, log out...         │ ← from @Tag on AuthController
│   POST /api/v1/auth/register   Create an account            │ ← from @PostMapping + @Operation(summary)
│   POST /api/v1/auth/login      Log in                       │
│   POST /api/v1/auth/logout-all Log out everywhere      🔒   │ ← lock = needs a token
├─────────────────────────────────────────────────────────────┤
│ Users  The logged-in user's profile.                         │ ← from @Tag on UserController
│   GET  /api/v1/users/me        Get my profile          🔒   │
├─────────────────────────────────────────────────────────────┤
│ Schemas: ApiProblem, LoginRequest, TokenResponse, ...        │ ← from our DTO records + @Schema
└─────────────────────────────────────────────────────────────┘
```

Click one endpoint and you see the request body example, all possible responses (200,
400, 401, 500) with their descriptions, and a **"Try it out"** button that sends a real
request to your running app.

**Every piece of text on that page comes from a specific line in our code.** The rest of
this note shows you which line.

---

## Part 3. The files we touched (the map)

| # | File | What it does in one sentence |
|---|---|---|
| 1 | `backend/pom.xml` | Adds the springdoc library. |
| 2 | `application.properties` and `application-dev.properties` | Switch the docs off by default and on in dev. |
| 3 | `common/security/SecurityConfig.java` | Lets people open the docs pages without a token, but only when docs are on. |
| 4 | `common/config/OpenApiConfig.java` | Sets the title, the "Authorize" button, and adds the common errors to every endpoint. |
| 5 | `common/web/ApiProblem.java` | Describes the shape of our error JSON, for the docs only. |
| 6 | `AuthController`, `UserController` | Annotations that describe each endpoint (summary, errors, public or not). |
| 7 | `LoginRequest`, `RegisterRequest`, `TokenResponse`, `UserResponse` | `@Schema` annotations that give examples and descriptions for each field. |

Now we walk through each one.

---

## Part 4. The code, line by line

### 4.1 `pom.xml`: adding the library

```xml
<properties>
    <java.version>17</java.version>
    <springdoc.version>3.1.1</springdoc.version>
</properties>
```
- `<properties>` is a place to store values used elsewhere in the pom. We store the
  springdoc version once, so we change it in one place later.

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>${springdoc.version}</version>
</dependency>
```
- `groupId` + `artifactId` together name the library, like "publisher + book title".
- `webmvc` means it's for Spring Web MVC (our kind of app, not the reactive kind).
- `ui` means it also brings Swagger UI (the web page). Without `-ui` you'd get only the JSON.
- `${springdoc.version}` reads the value we stored above (`3.1.1`).
- **Why do we write a version here, when other dependencies have none?** Spring Boot's
  parent pom already knows the right version of every *Spring* library. springdoc is not
  made by the Spring team, so Boot doesn't know it, and we must say which version.
- **Why 3.1.1?** springdoc's version follows Spring Boot's: 2.x is for Boot 3, 3.x is for
  Boot 4. Inside 3.x, 3.1 was built for Boot 4.1 (ours), and 3.2 for Boot 4.2. Using the
  wrong line usually breaks at startup.

What "adding a dependency" really does: Maven downloads the jar files, and when the app
starts, Spring Boot finds springdoc's **auto-configuration** inside them (the same idea as
in note 001). That auto-configuration creates the beans that serve `/v3/api-docs` and
`/swagger-ui.html`. We don't write those endpoints ourselves.

---

### 4.2 `application.properties`: the on/off switches

In `application.properties` (used in **every** environment):
```properties
springdoc.api-docs.enabled=false
springdoc.swagger-ui.enabled=false
springdoc.swagger-ui.operations-sorter=method
springdoc.swagger-ui.tags-sorter=alpha
springdoc.swagger-ui.persist-authorization=true
springdoc.default-produces-media-type=application/json
```
Line by line:
- `api-docs.enabled=false`: **don't** serve the JSON spec at `/v3/api-docs`.
- `swagger-ui.enabled=false`: **don't** serve the web page either.
  *Why off by default?* The spec is a full map of our API. In production we don't want to
  hand that map to strangers.
- `operations-sorter=method`: on the page, group endpoints by HTTP method (GET, POST...).
- `tags-sorter=alpha`: show the groups ("Auth", "Users") in A to Z order.
- `persist-authorization=true`: after you paste a token into "Authorize", the browser
  remembers it when you reload the page. Saves a lot of copy-pasting.
- `default-produces-media-type=application/json`: tells the docs that our normal answers
  are JSON.

In `application-dev.properties` (used **only** when you run with `-Dspring-boot.run.profiles=dev`):
```properties
springdoc.api-docs.enabled=true
springdoc.swagger-ui.enabled=true
```
- A profile file **overrides** the base file. So in dev the switches become `true`.
- Result: docs on your machine, no docs anywhere else, and no code change needed.

---

### 4.3 `SecurityConfig.java`: letting people see the docs without a token

Remember from step 005: **every URL needs a token**, except the few we list as `permitAll`.
A browser opening Swagger UI has no token, so without a change we'd get 401 and an empty page.

```java
private static final String[] DOCS_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**" };
```
- A list of the URLs the docs use:
  - `/v3/api-docs`: the JSON spec.
  - `/v3/api-docs/**`: anything under it (`**` means "any path below this").
  - `/swagger-ui.html`: the page you type in. It redirects to `/swagger-ui/index.html`.
  - `/swagger-ui/**`: the page's JavaScript, CSS and images.
- `static final`: one shared constant; it never changes.

```java
private final boolean apiDocsEnabled;

public SecurityConfig(@Value("${springdoc.api-docs.enabled:false}") boolean apiDocsEnabled) {
    this.apiDocsEnabled = apiDocsEnabled;
}
```
- `@Value("${...}")` asks Spring: "give me the value of this property".
- `springdoc.api-docs.enabled` is **the same switch** springdoc reads. One switch controls both.
- `:false` after the name is a **default**: if the property isn't set at all, use `false`.
- Spring converts the text `"true"` or `"false"` into a Java `boolean` for us.
- We take it in the **constructor** (constructor injection), the same as every other dependency.

```java
if (apiDocsEnabled) {
    http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, DOCS_PATHS).permitAll());
}
```
- Only when docs are on, add one rule: "GET requests to the docs URLs are allowed without a token".
- `HttpMethod.GET`: only reading is allowed, not POST/PUT/DELETE.
- **Order matters.** Spring Security checks rules **top to bottom** and stops at the first
  match. This rule is added *before* the existing `.anyRequest().authenticated()` (the
  "everything else needs a token" rule). If it came after, it would never be reached.
- When docs are **off**, this rule doesn't exist, so `/v3/api-docs` falls into "everything
  else needs a token" and gets 401. An outsider can't even tell the docs exist.

---

### 4.4 `OpenApiConfig.java`: the parts the scanner can't guess

springdoc can find our URLs by itself. But it can't guess the API's **title**, that we use
**JWT tokens**, or which errors **every** endpoint can return. We tell it here.

```java
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {
```
- `@Configuration`: "this class creates beans" (objects Spring manages). Same as `JwtConfig` in step 005.
- `proxyBeanMethods = false`: a small speed-up. Our `@Bean` methods don't call each other,
  so Spring needn't wrap the class in a proxy.

#### The `OpenAPI` bean: title, description, and the Authorize button

```java
@Bean
OpenAPI spendlyOpenApi() {
    return new OpenAPI()
```
- `@Bean`: Spring calls this method once at startup and keeps the returned object.
- springdoc looks for a bean of type `OpenAPI`. If it finds one, it uses it as the
  **starting point** of the document, then adds all the URLs it finds.

```java
        .info(new Info()
            .title("Spendly API")
            .version("v1")
            .description("""
                    Personal finance API ...
                    **Auth:** call `POST /api/v1/auth/login`, then send ...
                    """))
```
- `Info` is the header of the page: big title, version badge, and the text below it.
- The `"""` ... `"""` is a Java **text block**: a multi-line string.
- The description is **Markdown**: `**bold**` and `` `code` `` render nicely in Swagger UI.
- A `\` at the end of a line inside the text block means "continue on the same line"
  (no line break in the result).

```java
        .servers(List.of(new Server().url("/").description("This server")))
```
- Tells "Try it out" where to send requests. `/` means "the same server this page came
  from", so it works on `localhost:8080` or anywhere else without changes.

```java
        .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH))
```
- A **global** rule: "every endpoint needs the security scheme called `bearerAuth`".
- This is what puts the 🔒 lock icon next to every endpoint.
- Public endpoints switch it off with `@SecurityRequirements` (section 4.6).

```java
        .components(new Components()
            .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Access token from /api/v1/auth/login...")));
}
```
- `Components` is the document's "shared parts" drawer (schemes, schemas).
- This **defines** what `bearerAuth` means:
  - `type HTTP` + `scheme "bearer"` means "send `Authorization: Bearer <token>`";
  - `bearerFormat "JWT"` is just a label for humans.
- This definition is what makes the green **Authorize** button appear. When you paste a
  token there, Swagger UI adds the `Authorization` header to every "Try it out" request.

In the generated JSON (`docs/api/openapi.json`) these lines become:
```json
"security": [ { "bearerAuth": [] } ],
"components": { "securitySchemes": { "bearerAuth": { "type": "http", "scheme": "bearer", "bearerFormat": "JWT" } } }
```

#### The customizer: adding 401 and 500 to every endpoint automatically

Every endpoint can fail with `500 INTERNAL_ERROR`, and every protected endpoint can fail
with `401 UNAUTHENTICATED`. Writing those two on **every** method would be boring and easy
to forget. So we add them in one place.

```java
@Bean
OpenApiCustomizer commonErrorResponses() {
    return openApi -> {
```
- `OpenApiCustomizer` is a springdoc interface with one method: "here is the finished
  document, change it if you like". springdoc calls every such bean **after** it has scanned
  all controllers.
- `openApi -> { ... }` is a **lambda**: a short way to write that one method.
  `openApi` is the whole document.

```java
        ModelConverters.getInstance().readAll(ApiProblem.class).forEach(openApi.getComponents()::addSchemas);
```
- Turns our `ApiProblem` record (section 4.5) into a JSON schema and puts it in the shared
  `schemas` drawer.
- `readAll` also converts the types it uses, so `ValidationError` gets added too.
- `::addSchemas` is a **method reference**: "for each (name, schema) pair, call `addSchemas(name, schema)`".

```java
        if (openApi.getPaths() == null) {
            return;
        }
```
- A safety check: if there were no endpoints at all, there's nothing to do.

```java
        openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
```
- `getPaths()` holds every URL. `values()` gives each URL's entry.
- `readOperations()` gives the operations on that URL (GET, POST, ...).
- So the two loops visit **every endpoint** in the API.

```java
            ApiResponses responses = operation.getResponses();
            if (isSecured(operation)) {
                responses.putIfAbsent("401", problem("Missing, invalid or expired access token. code: `UNAUTHENTICATED`."));
            }
            responses.putIfAbsent("500", problem("Unexpected server error. code: `INTERNAL_ERROR`."));
```
- `putIfAbsent`: add it **only if** the endpoint doesn't already have one. Login has its
  own 401 (`INVALID_CREDENTIALS`), so we must not overwrite it.
- 401 only on secured endpoints: register can never answer "you need a token", so the
  docs must not claim it can.

```java
private static boolean isSecured(Operation operation) {
    return operation.getSecurity() == null || !operation.getSecurity().isEmpty();
}
```
- How we tell public from protected:
  - `getSecurity() == null`: the endpoint said nothing, so it **inherits** the global
    "needs bearerAuth" rule. Protected.
  - `getSecurity()` is an **empty list** `[]`: the endpoint has `@SecurityRequirements` with
    nothing inside, meaning "no security needed". Public.
- In the JSON, login shows `"security": []`. That's the empty list.

```java
static ApiResponse problem(String description) {
    Schema<?> ref = new Schema<>().$ref("#/components/schemas/ApiProblem");
    return new ApiResponse()
        .description(description)
        .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(ref)));
}
```
- Builds one response entry: a description, plus "the body is `application/problem+json`
  and looks like `ApiProblem`".
- `$ref` means **"see the shared schema over there"**, so the error shape is written once
  and every response points to it.

---

### 4.5 `ApiProblem.java`: describing our error JSON

When something goes wrong, `GlobalExceptionHandler` (step 002) returns JSON like this:
```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 400,
  "detail": "Request has invalid fields.",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "requestId": "7ae20fcd-...",
  "errors": [ { "field": "email", "message": "Email must be a valid email address." } ]
}
```
Spring builds that with its `ProblemDetail` class, and we add `code`, `requestId` and
`errors` through a free-form `properties` map. springdoc can't look inside a free-form map,
so on its own it would document an error without `code`, the most important field for the app.

So we write a record that has **exactly** the same fields, only for the docs:

```java
@Schema(name = "ApiProblem", description = "RFC 9457 Problem Details. Switch on `code`, never on `title` or `detail`.")
public record ApiProblem(
        @Schema(example = "about:blank") String type,
        @Schema(example = "Validation failed") String title,
        @Schema(example = "400") int status,
        @Schema(description = "Human-readable, safe to show the user.", example = "Request has invalid fields.") String detail,
        @Schema(example = "/api/v1/auth/register") String instance,
        @Schema(description = "Stable machine-readable error code.", example = "VALIDATION_FAILED") String code,
        @Schema(description = "Same id as the X-Request-Id response header; ...", example = "7ae2...") String requestId,
        @Schema(description = "Only for VALIDATION_FAILED: one entry per invalid field.", nullable = true)
        List<ValidationError> errors) {
}
```
- **No code ever creates an `ApiProblem` object.** It's a drawing of the error, not the error itself.
- `@Schema` on the record: the schema's name in the docs and a description shown at the top.
- `@Schema` on each field: an `example` value and/or a `description` for the docs.
- `nullable = true` on `errors`: tells the app developer "this can be missing or null".
- **Rule to remember:** if `GlobalExceptionHandler` ever gets a new field, add it here
  too, or the docs will be wrong.

---

### 4.6 Controller annotations: describing each endpoint

Here is `login` exactly as it is in `AuthController`, then each annotation explained.

```java
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth", description = "Register, log in, refresh tokens, log out. Only logout-all needs an access token.")
public class AuthController {

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in",
            description = "Returns a short-lived access token. Send it as `Authorization: Bearer <accessToken>`.")
    @ApiResponse(responseCode = "200", description = "Logged in")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "401",
            description = "`INVALID_CREDENTIALS`. Same answer for an unknown email or a wrong password, on purpose.",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }
}
```

**Important:** these Swagger annotations **change nothing about how the app behaves**. They
only add text to the docs. Delete them all and the API still works exactly the same; only
the docs get poorer.

| Annotation | What it means | Where you see it in Swagger UI |
|---|---|---|
| `@RestController`, `@RequestMapping`, `@PostMapping` | Spring's own (step 001/004). springdoc reads them to learn the URL and method. | `POST /api/v1/auth/login` |
| `@Tag(name, description)` on the class | "All endpoints in this class belong to the group *Auth*." | The "Auth" section header and its grey text |
| `@SecurityRequirements` (nothing inside) | "This endpoint needs **no** security." It cancels the global bearer rule. | No 🔒 icon. In the JSON: `"security": []` |
| `@Operation(summary, description)` | A short title and a longer explanation of the endpoint. | Summary on the collapsed row; description when you open it |
| `@ApiResponse(responseCode, description)` | "This endpoint can answer with this status code, and here is what it means." | A row in the "Responses" table |
| `content = @Content(mediaType = ...)` | "The body of that response has this content type." | The content-type label on that response |
| `schema = @Schema(implementation = ApiProblem.class)` | "...and looks like the `ApiProblem` record." | The example and model of the error body |

Some details:
- **Why `@ApiResponse` for 200 has no `content`:** springdoc already knows the method
  returns `TokenResponse`, so it fills the 200 body in by itself. We only add the description.
- **Why errors need `content = ...`:** the method's return type says nothing about errors,
  so we must say what an error body looks like.
- **Why `application/problem+json`:** that's the official content type of RFC 9457 errors,
  and it's what our handler really sends. The app can tell errors from normal answers by it.
- **Why we list the `code` values in `description`:** the app switches on `code`, so the
  developer needs the exact list of codes each endpoint can return.
- **You may repeat `@ApiResponse`** on one method. Java allows "repeatable" annotations.
- On **register**, `@ResponseStatus(HttpStatus.CREATED)` is Spring's own annotation (it
  really makes the answer 201). springdoc reads it too, so the docs say 201, not 200.

And on `UserController.me()` there is **no** `@SecurityRequirements`, so the endpoint
inherits the global bearer rule: it gets a lock icon, and our customizer adds its 401
automatically.

---

### 4.7 DTO annotations: describing each field (and "messages")

This is where people mix things up, because a field can have **two kinds of annotations**
that look alike but do **completely different jobs**.

```java
public record LoginRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "Email must be a valid email address.")
        @Size(max = 254, message = "Email must be at most 254 characters.")
        @Schema(example = "ravi@mail.com", description = "Case-insensitive; spaces around it are ignored.")
        String email,

        @NotBlank(message = "Password is required.")
        @Schema(example = "correct-horse-42", format = "password")
        String password,

        @Size(max = 100, message = "Device label must be at most 100 characters.")
        @Schema(description = "Optional name of this device, shown later in the device list.", example = "Pixel 8",
                nullable = true)
        String deviceLabel) { ... }
```

#### Kind 1: validation annotations (`@NotBlank`, `@Email`, `@Size`), the real rules
- Package `jakarta.validation`. They come from Bean Validation (step 004).
- They **run on every request** because the controller says `@Valid`. If a rule fails, the
  request is rejected with 400 before our service code runs.
- Their `message = "..."` is the text that **goes back to the app at runtime**, in `errors[]`:
  ```json
  "errors": [ { "field": "email", "message": "Email must be a valid email address." } ]
  ```
  The app can show this text under the email input field.
- **Bonus:** springdoc **reads these too** and turns them into rules in the docs, so you
  don't write the rules twice:

  | Java annotation | Becomes in the docs (JSON) |
  |---|---|
  | `@NotBlank` | the field is listed in `"required"`, plus `"minLength": 1` |
  | `@Email` | `"format": "email"` |
  | `@Size(max = 254)` | `"maxLength": 254` |
  | no `@NotBlank`/`@NotNull` (deviceLabel) | not in `"required"`, so optional |

#### Kind 2: `@Schema`, text for the docs only
- Package `io.swagger.v3.oas.annotations.media`. **It never runs any check.**
- It only adds information to the docs:
  - `example = "ravi@mail.com"`: the value Swagger UI pre-fills in "Try it out", and shows in the example.
  - `description = "..."`: an explanation shown under the field name.
  - `format = "password"`: Swagger UI shows the value as dots (••••) and code generators know it's a secret.
  - `nullable = true`: "this field may be null". In JSON it becomes `"type": ["string", "null"]`.

#### Side by side: the same field, two jobs

| | `@Size(max = 254, message = "Email must be at most 254 characters.")` | `@Schema(description = "Case-insensitive; ...")` |
|---|---|---|
| From | Bean Validation (`jakarta.validation`) | Swagger (`io.swagger.v3`) |
| Runs on every request? | **Yes**, rejects bad input with 400 | **No**, never runs |
| Who sees the text? | The **app user**, in the `errors[]` of a 400 response | The **developer**, reading the docs |
| Remove it and... | bad input gets through (a real bug) | the docs get poorer, nothing else changes |

#### This is the real result in `docs/api/openapi.json`
```json
"LoginRequest": {
  "type": "object",
  "properties": {
    "email":       { "type": "string", "format": "email", "maxLength": 254,
                     "description": "Case-insensitive; spaces around it are ignored.", "example": "ravi@mail.com" },
    "password":    { "type": "string", "format": "password", "minLength": 1, "example": "correct-horse-42" },
    "deviceLabel": { "type": ["string", "null"], "maxLength": 100, "example": "Pixel 8",
                     "description": "Optional name of this device, shown later in the device list." }
  },
  "required": ["email", "password"]
}
```
Find each annotation from the Java code in this JSON. That's the whole trick.

#### Response DTOs (`TokenResponse`, `UserResponse`)
These only have `@Schema`, because nobody validates what *we* send back. For example:
```java
@Schema(description = "Seconds until the access token expires.", example = "900") long expiresIn
```
This tells the app developer the unit (seconds) and a realistic value. Without it they'd
have to guess whether `900` means seconds or minutes.

---

## Part 5. What happens, step by step, when you open Swagger UI

1. **You type** `http://localhost:8080/swagger-ui.html`. The request passes our
   `RequestIdFilter`, then the security chain. The docs rule says "public", so there's no
   401. springdoc redirects to `/swagger-ui/index.html`.
2. **The browser loads the page**: HTML, JavaScript and CSS from `/swagger-ui/**`, also public.
3. **The page's JavaScript asks for the spec**: `GET /v3/api-docs`.
4. **The first time only**, springdoc builds the document:
   - a. starts from our `OpenAPI` bean (title, the bearer scheme, the global lock rule);
   - b. asks Spring MVC for its list of all endpoints, the same list
     `DispatcherServlet` uses to route requests;
   - c. for each endpoint, reads `@PostMapping`/`@GetMapping` (URL), the parameter and
     return types (`LoginRequest`, `TokenResponse`), the validation annotations (rules),
     and the Swagger annotations (text);
   - d. turns each DTO record into a schema;
   - e. calls our `OpenApiCustomizer`, which adds `ApiProblem`, the 401s and the 500s;
   - f. keeps the result in memory, so later requests are instant.
5. **The page draws everything** from that JSON.
6. **You click "Authorize"** and paste a token. The page remembers it
   (`persist-authorization=true`).
7. **You click "Try it out" → "Execute"** on `/users/me`. The page sends a real request
   with `Authorization: Bearer <token>`, and you see the real answer from our server.

---

## Part 6. Cheat sheet: which annotation do I use?

| I want to... | Use | Example |
|---|---|---|
| Group endpoints under a heading | `@Tag` on the controller class | `@Tag(name = "Accounts", description = "...")` |
| Give an endpoint a title and explanation | `@Operation` on the method | `@Operation(summary = "Create account", description = "...")` |
| List an error the endpoint can return | `@ApiResponse` + `@Content` + `@Schema(implementation = ApiProblem.class)` | see 4.6 |
| Mark an endpoint as public (no token) | `@SecurityRequirements` (empty) | on register, login, refresh, logout |
| Show an example value for a field | `@Schema(example = "...")` on the record field | `@Schema(example = "INR")` |
| Explain a field | `@Schema(description = "...")` | units, allowed values, when it's null |
| Say a field can be null | `@Schema(nullable = true)` | `deviceLabel` |
| **Enforce** a rule (reject bad input) | Bean Validation: `@NotBlank`, `@Size`, `@Email`... with `message` | the docs pick these up by themselves |
| Add 401 or 500 | **Nothing**. The customizer adds them automatically. | |

---

## Part 7. Common mistakes

1. **Docs turned on, but no `permitAll`.** The page loads, then shows "Failed to load API
   definition", because `/v3/api-docs` answered 401.
2. **Forgetting `@SecurityRequirements` on a public endpoint.** Swagger shows a lock, and
   the app developer thinks login needs a token.
3. **Thinking `@Schema` validates.** It doesn't. `@Schema(maxLength = 10)` alone lets
   1000-character input through. Use `@Size` for the rule.
4. **Changing the error JSON but not `ApiProblem`.** The docs then lie about the error shape.
5. **Returning an entity instead of a DTO.** springdoc would document every column,
   including `passwordHash`. Our rule "controllers return DTOs" prevents this.
6. **Using springdoc 2.x with Spring Boot 4.** It fails at startup. The major versions must match.

---

## Part 8. Try it yourself

1. Start the app with `dev` and open Swagger UI. Find these on the page:
   - the title from `Info`;
   - the "Auth" group from `@Tag`;
   - the "Log in" summary from `@Operation`;
   - the 401 description from `@ApiResponse`;
   - the `ravi@mail.com` example from `@Schema`.
2. In `LoginRequest`, change `@Size(max = 254)` to `@Size(max = 100)`. Restart, open
   `/v3/api-docs`, and look at `email`: `maxLength` is now 100. You changed a **rule**, and
   the docs followed.
3. Change only the `@Schema(description = ...)` of `email`. Restart, and send a bad email
   in "Try it out". The **error message didn't change**, because `@Schema` is docs only.
   Then change the `message` of `@Email` and try again: now the error text changes.
4. Remove `@SecurityRequirements` from `login`. Restart: login gets a lock icon and a
   generic 401. Run `OpenApiDocsIntegrationTest`: `docsEnabled_loginIsPublic_meIsSecured`
   fails. That test protects you from this mistake.
