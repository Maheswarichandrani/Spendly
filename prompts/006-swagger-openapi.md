# 006: Swagger / OpenAPI docs

Part of the approved roadmap `prompts/ROADMAP-2026-11-26.md` (one approval for the day).

## Goal
Serve a complete, accurate OpenAPI 3.1 description of the API plus Swagger UI, so the
frontend developer can read every endpoint, its request and response shape, its error
codes, and try it with a JWT.

## Code inspected
- `pom.xml`: Spring Boot 4.1.2, Java 17.
- `SecurityConfig`: public = register, login, health. Everything else is authenticated.
- `GlobalExceptionHandler`: Problem Details plus `code`, `requestId`, and `errors[]` (field, message).
- `AuthController`, `UserController`, `RegisterRequest`, `LoginRequest`, `TokenResponse`, `UserResponse`.

## Decisions
- **springdoc-openapi-starter-webmvc-ui 3.1.1**. The 3.x line is for Boot 4; 3.1.1 is built
  against Boot 4.1.0, which matches us. 3.2.x targets Boot 4.2, so we don't use it.
  (Checked on Maven Central.)
- **Docs are off by default** (`springdoc.api-docs.enabled=false` and
  `springdoc.swagger-ui.enabled=false` in `application.properties`) and **on in `dev`**.
  A prod build doesn't publish our API map.
- **Security:** `/v3/api-docs/**`, `/swagger-ui/**` and `/swagger-ui.html` are `permitAll`
  **only when docs are enabled**. When docs are off, those URLs stay behind auth like any
  unknown URL (401).
- **One security scheme** `bearerAuth` (HTTP bearer, JWT), applied globally. Public
  endpoints opt out with `@SecurityRequirements()`.
- **Error schema:** a documentation-only record `ApiProblem` (type, title, status, detail,
  instance, code, requestId, errors[]). It mirrors exactly what `GlobalExceptionHandler`
  writes. Content type `application/problem+json`.
- **Common responses are added automatically** by an `OpenApiCustomizer`: every operation
  gets `500 INTERNAL_ERROR`, and every secured operation gets `401 UNAUTHENTICATED`. Only
  endpoint-specific errors (400, 409, ...) are written on the controller.
- Controllers get `@Tag`, `@Operation(summary, description)` and `@ApiResponse` entries
  listing the `code` values. DTOs get `@Schema` examples.
- Money docs (BigDecimal as a decimal string) are set up in 009, together with the Jackson
  config, so the docs and the JSON change together.

## Files (all CLAUDE WRITES)
- `backend/pom.xml`: add springdoc.
- `main/resources/application.properties`, `application-dev.properties`: on/off switches.
- `common/config/OpenApiConfig.java`: `OpenAPI` bean (info, bearer scheme) and the customizer.
- `common/web/ApiProblem.java`: the error schema.
- `common/security/SecurityConfig.java`: docs permit, conditional.
- `auth/controller/AuthController.java`, `user/controller/UserController.java`: annotations.
- `auth/dto/*`, `user/dto/UserResponse.java`: `@Schema` examples.
- Test: `common/config/OpenApiDocsIntegrationTest.java`.
- `docs/learn/006-openapi-springdoc.md`.

## API contract
- `GET /v3/api-docs` (JSON) and `GET /swagger-ui.html` (UI) when docs are enabled. 404/401 when disabled.

## Security
- No secrets in the docs. The test key and the env values are never included.
- When docs are disabled, none of the docs URLs are public.

## Spring concepts this step teaches
- How springdoc builds the spec at runtime from Spring MVC's handler mappings plus annotations.
- `OpenApiCustomizer`: changing the generated model in code.
- Conditional config from properties (`@Value` with a default) inside the filter chain.

## Tests
- `docsEnabled_apiDocsArePublicAndListEndpoints`: `/v3/api-docs` returns 200 without a
  token; it contains `/api/v1/auth/login` and `/api/v1/users/me`, plus the `bearerAuth` scheme.
- `docsEnabled_loginIsPublic_meIsSecured`: login has `security: []`; `/users/me` has a 401 response.
- `docsEnabled_everyOperationHas500`.
- `docsDisabled_apiDocsNeedsAuth`: with the default properties, `/v3/api-docs` without a token returns 401.

## Logging
Nothing new.

## Transactions
None.

## Acceptance
- `./mvnw -q verify` passes.
- With `dev`, `http://localhost:8080/swagger-ui.html` shows Auth and Users. The Authorize
  button accepts a token, and "Try it out" on `/users/me` returns 200.

## Manual test
1. `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
2. Open `http://localhost:8080/swagger-ui.html`.
3. Register, then log in through "Try it out". Copy `accessToken`.
4. Click Authorize, paste the token, then call `GET /api/v1/users/me`: 200.
