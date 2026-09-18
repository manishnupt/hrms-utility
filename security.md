# Security Review — `security` package

Scope: `src/main/java/com/hrms/hrms_utility/security/` (`SecurityConfig`, `MultiTenantJwtDecoder`,
`JwtAuthenticationEntryPoint`) plus the multi-tenancy plumbing it depends on
(`utility/TenantFilter`, `utility/TenantContext`, `utility/MultitenantDataSource`,
`config/MultiTenantConfiguration`) and `ActionItemController`, since that's the only consumer
today and it shows what the security layer actually protects.

This is a brand-new addition to the codebase (`pom.xml` diff adds `spring-boot-starter-security`,
`spring-boot-starter-oauth2-resource-server`, `nimbus-jose-jwt` in this same change set) — treat
this as a first security pass, not a hardening of something previously reviewed.

## 1. How it works today

### Request flow

```
Request
  → TenantFilter (@Order(1))              sets TenantContext from "X-Tenant-Id" header
  → Spring Security filter chain
      → CORS (wildcard, see §3.1)
      → authorizeHttpRequests               OPTIONS/**, /actuator/health, /api/public/** open;
                                             everything else requires authentication
      → OAuth2 resource server (JWT)
          → MultiTenantJwtDecoder.decode()
              1. reads realm name from TenantContext (set by TenantFilter)
              2. parses the JWT *without verifying its signature* to read `iss`
              3. compares that `iss` to `{keycloakBaseUrl}/realms/{realm}`
              4. gets-or-creates a cached NimbusJwtDecoder for that realm
                 (fetches JWKS from Keycloak, validates signature + standard claims + issuer)
          → JwtAuthenticationConverter + KeycloakRoleConverter
              maps `realm_access.roles` and `resource_access.*.roles` → `ROLE_*` GrantedAuthority
      → controller method runs as an authenticated principal with ROLE_* authorities
  → TenantContext cleared in TenantFilter's `finally`
```

Failures anywhere in that chain (missing token, bad signature, tenant/issuer mismatch, expired
token) land in `JwtAuthenticationEntryPoint`, which classifies the underlying `JwtException`
message by substring match and returns a JSON body with `errorCode` (`TOKEN_EXPIRED`,
`INVALID_TOKEN`, `INVALID_TENANT`, `INVALID_SIGNATURE`, `TOKEN_VALIDATION_FAILED`, …) and
`401`.

### Multi-tenancy model

Tenancy is realm-per-tenant in Keycloak, and DB-per-tenant in Postgres:

- `TenantFilter` trusts the client-supplied `X-Tenant-Id` header verbatim and stores it in a
  `ThreadLocal` (`TenantContext`).
- `MultiTenantJwtDecoder` uses that same value both to pick/build the Keycloak realm decoder
  **and** as the expected issuer segment.
- `MultitenantDataSource` (an `AbstractRoutingDataSource`) uses the *same* `TenantContext` value
  again, independently, to route JDBC connections to the tenant's Postgres database.
- The pool of per-tenant `DataSource`s is built **once, at application startup**, by
  `MultiTenantConfiguration.fetchTenantConfigsFromApi()`, which calls an external
  tenant-management HTTP API (`tenant.config.api.url`) and gets back DB host/user/password per
  tenant.

So the same client header (`X-Tenant-Id`) is the sole key for both "which Keycloak realm do I
validate this JWT against" and "which Postgres database do I run this query against." There is
no second, server-derived source of truth (e.g. a tenant claim baked into the token, checked
against the header) tying the two together — they're linked only implicitly, by the issuer-string
comparison in step 3 above.

### Authorization

`@EnableMethodSecurity` is turned on, but nothing in the codebase uses `@PreAuthorize` /
`@Secured` / `@RolesAllowed`. `KeycloakRoleConverter` does the work of extracting roles, but no
controller checks them — see §3.2.

## 2. What each file does

| File | Responsibility |
|---|---|
| `SecurityConfig` | Wires the filter chain: CORS, stateless sessions, path-level `authorizeHttpRequests`, OAuth2 resource-server JWT auth wired to `MultiTenantJwtDecoder`, role extraction via `KeycloakRoleConverter`. |
| `MultiTenantJwtDecoder` | Per-realm JWT validation: reads tenant from `TenantContext`, checks issuer, lazily builds and caches a `NimbusJwtDecoder` (JWKS-based) per realm. |
| `JwtAuthenticationEntryPoint` | Turns any `AuthenticationException`/`JwtException` into a structured 401 JSON response, with fairly detailed error classification and extensive logging. |

## 3. Known gaps / risks

Ordered roughly by severity/impact.

### 3.1 Critical — hardcoded production DB credentials committed to the repo
`src/main/resources/application-prod.properties` contains a plaintext Postgres username and
password for `postgres.railway.internal`, and `application.properties` sets
`spring.profiles.active=prod`, so **this is the active configuration**, not a template. Anyone
with read access to the repo (or its git history, even after a later "fix") has the production
database password. This needs to move to a secret manager / env var immediately, and the
credential should be rotated since it has already been exposed in version control.

### 3.2 Critical — no object-level authorization (IDOR) on top of authentication
`SecurityConfig` only enforces *authentication* (`anyRequest().authenticated()`). Nothing checks
that the caller is *allowed* to act on the specific resource. In `ActionItemController`, every
endpoint takes an arbitrary `id` or `userId` from the URL with no ownership check against the JWT
principal and no role check:
- `GET /action-item/{id}` — any authenticated user in the tenant can read any action item by ID.
- `GET /action-item/assignee/{userId}` / `/initiator/{userId}` — any authenticated user can read
  any other user's action items by supplying their `userId`.
- `PUT /action-item/{id}/status` — any authenticated user can approve/reject any other user's
  action item.

`@EnableMethodSecurity` is enabled but unused — there's no `@PreAuthorize` anywhere in the
codebase. Practically, once someone has *any* valid token for a tenant, they have full read/write
access to every other user's data in that tenant.

### 3.3 High — CORS is wide open, and in two contradictory ways
`SecurityConfig.filterChain()` wires an **inline anonymous `CorsConfigurationSource`** with
`setAllowedOrigins(Arrays.asList("*"))` and exposes the `Authorization` header. The other,
properly scoped `corsConfigurationSource()` `@Bean` (allowlisting `localhost` + `*.pp.worksphere.works`,
`allowCredentials(true)`) is defined but **never referenced** — it's dead code, and the
commented-out line above it (`// .cors(cors -> cors.configurationSource(corsConfigurationSource()))`)
suggests this was accidentally left wired to the permissive version. On top of that,
`ActionItemController` has its own `@CrossOrigin(origins = "*")`, which is redundant with the
active config but reinforces the same problem at the controller level. Net effect: any website
can call this API cross-origin, using whatever JWT it can get its hands on. Recommend deleting
the inline wildcard CORS source and wiring the real `corsConfigurationSource()` bean instead, and
dropping the controller-level `@CrossOrigin`.

### 3.4 High — client-supplied tenant header is trusted with no allow-list, and drives both auth and DB routing
`X-Tenant-Id` is taken directly off the request (`TenantFilter`) with no validation that it's a
real, known tenant before being used to:
- build a JWKS fetch URL (`{keycloakBaseUrl}/realms/{tenant}/protocol/openid-connect/certs`), and
- look up a JDBC `DataSource` (`MultitenantDataSource`).

The issuer-equality check in `MultiTenantJwtDecoder` (comparing the *unverified* `iss` claim,
read via `JWTParser.parse` before any signature check, to `keycloakBaseUrl + "/realms/" + tenant`)
does stop cross-tenant token replay (a real token from realm A won't pass for tenant B, because
signature validation still happens afterward via the JWKS fetched for the claimed realm).
But because that early issuer check compares two attacker-influenced values (the header and the
unverified `iss` claim of a self-crafted, unsigned JWT), an attacker can make the service attempt
a JWKS fetch for **any realm name they choose**, simply by setting the header and the `iss` claim
to match — the request only gets rejected later, at signature verification. This is a minor SSRF/
probing primitive against the Keycloak host and a way to force decoder-creation attempts for
made-up realms. Combined with the fact that `jwtDecoders` (the per-realm decoder cache) is an
unbounded `ConcurrentHashMap` with no eviction, this is also a memory-growth DoS vector: keep
sending distinct bogus tenant names and the cache keeps growing (each successful decoder build
also holds a JWK set client). Recommend validating `X-Tenant-Id` against the known-tenant list
(the one `MultiTenantConfiguration` already fetches) before it's used for anything, and bounding/
evicting the decoder cache.

### 3.5 Medium — DB routing fails open to the default tenant instead of failing closed
`MultiTenantConfiguration.dataSource()` sets `setDefaultTargetDataSource(resolvedDataSources.get(defaultTenant))`.
`AbstractRoutingDataSource`'s default behavior is: if `TenantContext`'s value isn't a key in the
resolved map, it silently falls back to the *default* tenant's database rather than throwing. A
request whose tenant realm exists in Keycloak (so it passes JWT/issuer validation) but isn't
present in the DB-config list (e.g. newly created, mid-provisioning, or since removed) will
silently read/write the **default tenant's data** instead of failing. This should fail closed
(throw/403) on an unresolved tenant key, not fall back.

### 3.6 Medium — tenant DB config is fetched once, unauthenticated, at startup
`fetchTenantConfigsFromApi()` calls `tenant.config.api.url` with a bare `RestTemplate` — no
API key, bearer token, or mTLS on that outbound call. It runs once, inside `@Bean` construction,
so:
- a new tenant added after the service starts is invisible until restart;
- if the call throws, `response` stays `null` and the very next line (`response.getStatusCode()`)
  NPEs, taking the app down at startup instead of a clear error;
- whoever operates that tenant-management endpoint is implicitly trusted to hand back every
  tenant's DB username/password over an unauthenticated call — worth confirming that endpoint has
  its own access control, since this service does nothing to authenticate the request or verify
  the response.

### 3.7 Medium — verbose security logging, and DEBUG security logging enabled in the active prod profile
`application-prod.properties` (the active profile, see §3.1) sets
`logging.level.org.springframework.security=DEBUG`. Combined with the extensive `log.info`/
`log.debug` calls in `MultiTenantJwtDecoder` and `JwtAuthenticationEntryPoint` — token subject,
issuer, tenant, first-20-chars token preview, full exception messages and (at DEBUG) stack traces
— production logs will carry more authentication detail than they should. None of this is
catastrophic on its own (no full token or credential is logged), but it's more surface area than
a prod environment needs, and DEBUG-level Spring Security logging is generally not something to
ship to production.

### 3.8 Low — error responses are a validation oracle
`JwtAuthenticationEntryPoint` distinguishes `TOKEN_EXPIRED` / `INVALID_TOKEN` / `INVALID_TENANT` /
`INVALID_SIGNATURE` / `TOKEN_VALIDATION_FAILED` by string-matching the exception message, and
echoes the tenant header back in the response body. This is a nice DX feature for legitimate
clients, but it also hands an attacker a precise oracle for iterating on tenant names and forged
tokens (e.g., distinguishing "wrong realm" from "bad signature" from "expired"). Consider
collapsing these to a generic message for unauthenticated callers and keeping the detail only in
logs.

### 3.9 Low — dead/confusing config
- `authenticationEntryPoint` is set twice in `SecurityConfig` (once via
  `oauth2ResourceServer(...).authenticationEntryPoint(...)`, once via a separate
  `.exceptionHandling(...)` block) — harmless (the second wins) but the leftover `// ← ADD THIS`
  comments suggest this was patched in ad hoc and should be cleaned up to one place.
- `.requestMatchers("/actuator/health", ...)` references actuator, but
  `spring-boot-starter-actuator` isn't a dependency in `pom.xml` — dead rule, not currently a real
  exposure, but worth removing or adding the dependency intentionally.
- `/api/public/**` is permit-all but nothing currently maps under that path — fine today, but
  it's an easy trap for a future controller to land in unauthenticated by accident.

## 4. Suggested priority order

1. Rotate the leaked prod DB password and move all credentials (DB, Keycloak base URL if
   sensitive, tenant-config API) out of properties files into env vars / a secrets manager
   (§3.1).
2. Add object-level authorization to `ActionItemController` — at minimum, verify the JWT
   principal owns/is-permitted-for the `userId`/`id` being accessed, and start using
   `@PreAuthorize` with the roles `KeycloakRoleConverter` already extracts (§3.2).
3. Fix CORS: delete the inline wildcard `CorsConfigurationSource`, wire the existing restrictive
   `corsConfigurationSource()` bean, drop `@CrossOrigin(origins = "*")` on the controller (§3.3).
4. Validate `X-Tenant-Id` against the known-tenant set before using it for JWKS/DB routing, and
   bound the JWT decoder cache (§3.4).
5. Make tenant DB routing fail closed on an unknown tenant instead of defaulting (§3.5).
6. Turn off `DEBUG` Spring Security logging in the prod profile (§3.7).
