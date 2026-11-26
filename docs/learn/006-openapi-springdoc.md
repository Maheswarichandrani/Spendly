# 006: API docs with OpenAPI and springdoc

## 1. The concept
**OpenAPI** is a standard JSON (or YAML) file that describes an HTTP API: every URL, its
method, the request body, each possible response, and the auth it needs. Tools read the
file. **Swagger UI** turns it into a web page with a "Try it out" button. Code generators
can turn it into a Dart client.

The problem it solves: the frontend developer needs to know exactly what to send and what
comes back, including the error codes. Hand-written docs go stale the day someone changes
a DTO. A spec **generated from the running code** can't go stale.

Think of the spec as a **menu printed by the kitchen itself**. If the chef changes a dish,
the menu changes too.

## 2. How springdoc does it under the hood
1. At startup, springdoc's auto-configuration registers a few beans and two endpoints:
   `/v3/api-docs` (the JSON) and `/swagger-ui.html` (static UI files from the
   `swagger-ui` webjar). It does nothing else until someone asks for the spec.
2. On the **first** `GET /v3/api-docs`, it asks Spring MVC's
   `RequestMappingHandlerMapping` for the list of handler methods. That's the same table
   `DispatcherServlet` uses to route requests (see note 001).
3. For each method it reads:
   - `@PostMapping`, `@GetMapping`, ... for the path and HTTP method;
   - the parameter types (`@RequestBody LoginRequest`) and the return type (`TokenResponse`);
   - Bean Validation annotations (`@NotBlank`, `@Size(max = 254)`), which become
     `required`, `maxLength` and so on;
   - Swagger annotations (`@Operation`, `@ApiResponse`, `@Schema`) for the human text.
4. Java types become JSON schemas through the swagger-core `ModelConverters`. A record's
   components become properties.
5. It starts from our `OpenAPI` bean (title, the `bearerAuth` scheme), fills in the paths,
   then runs every `OpenApiCustomizer` bean on the result. Ours adds 401 and 500.
6. The result is cached, so later requests are instant.

**Conditional security:** `SecurityConfig` reads `springdoc.api-docs.enabled` with
`@Value("${springdoc.api-docs.enabled:false}")`. The `:false` is the default if the property
is missing. The filter chain is built once at startup, so the docs paths are either in the
`permitAll` list or not, for the life of the app.

## 3. Request walk-through: `GET /v3/api-docs` in dev
1. Tomcat → `RequestIdFilter` (adds the request id) → the Spring Security filter chain.
2. `AuthorizationFilter` checks the rules top to bottom. `GET /v3/api-docs` matches the
   docs `permitAll` rule that was added because `apiDocsEnabled == true`. No token needed.
3. `DispatcherServlet` → springdoc's `OpenApiWebMvcResource.openapiJson(...)`.
4. On the first call it builds the model (section 2) and runs
   `OpenApiConfig.commonErrorResponses()`. That adds `ApiProblem` to `components.schemas`,
   then walks every operation:
   - `security == null` (inherits the global bearer requirement) → add `401`;
   - `security == []` (`@SecurityRequirements` on the method) → public, no 401;
   - always add `500`.
5. The JSON goes back through Jackson.

With docs off (the default), step 2 finds no docs rule, hits `anyRequest().authenticated()`,
and returns 401.

## 4. Why this design
- **springdoc vs. writing `openapi.yaml` by hand:** a hand-written file drifts from the code.
  Generated docs stay correct for free.
- **springdoc 3.1.1, not 3.2.x:** springdoc's major version follows Spring Boot's, and the
  minor version follows Boot's minor. 3.2 is built against Boot 4.2; we run 4.1.
- **A customizer for 401/500 instead of annotations on every method:** a new endpoint gets
  them automatically, and nobody can forget.
- **`ApiProblem` record:** Spring's `ProblemDetail` class has a free-form `properties` map,
  so Swagger can't show `code` or `requestId`. A record that mirrors the real body gives the
  frontend an exact schema.
- **Off by default:** a public spec is a map of the API for attackers. Dev only for now.

## 5. Common mistakes
- **Turning docs on but forgetting the `permitAll`:** Swagger UI loads its JS, then
  `/v3/api-docs` returns 401, and the page shows "Failed to load API definition".
- **Putting the docs rule after `anyRequest()`:** Spring Security refuses to start, because
  `anyRequest` must be the last rule.
- **`@SecurityRequirements` forgotten on a public endpoint:** Swagger shows a lock icon,
  and the frontend thinks login needs a token.
- **The wrong springdoc line:** springdoc 2.x on Boot 4 fails at startup (Jackson 2 vs 3,
  moved classes).
- **Returning entities:** springdoc would document every JPA field, including
  `passwordHash`. Our rule "controllers return DTOs" prevents it.

## 6. Try it
1. Start with `dev` and open `http://localhost:8080/swagger-ui.html`. Find the
   `ApiProblem` schema at the bottom.
2. In `LoginRequest`, change `@Size(max = 254)` to `@Size(max = 100)`, restart, and look at
   the `email` property in the spec. `maxLength` changes by itself.
3. Remove `@SecurityRequirements` from `login`, restart, and see the lock icon and the 401
   response appear. Then run `OpenApiDocsIntegrationTest` and watch which test fails, and why.
