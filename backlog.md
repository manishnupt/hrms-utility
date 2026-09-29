# hrms-utility — Security & Production-Readiness Backlog

Scope: every tracked file in the repo as of commit `0414743` (Java sources, `application*.properties`,
`pom.xml`, Helm chart under `charts/`, Jenkinsfile).
Review date: 2026-09-27.

Severity scale: **Critical** = exploitable now with real data/integrity impact · **High** = serious,
exploitable with modest effort · **Medium** = weakens defenses or causes incidents under load/failure ·
**Low** = hygiene.

---

## Deployment assumption: Kong handles authentication

This service runs behind **Kong**. Authentication is done by the custom plugin **`hrms-auth` v1.5.0**
(`~/hrms-auth/kong/plugins/hrms-auth/handler.lua`, reviewed 2026-09-27). The plugin reads
`X-Tenant-Id` and `Authorization: Bearer …`, fetches the JWKS for that tenant's Keycloak realm
(cached for 5 minutes), verifies the RS256 signature, and checks `iss`, `exp` and `azp`. That makes
Kong part of this service's security boundary, so the following have to be true.

Status legend: ✅ handled by the plugin · ⚠️ partially handled · ❌ not handled · ❔ outside the
plugin's code (Kong or K8s config), can't be verified from either repo · ➖ not something a gateway
plugin can fix (app-level)

| # | Requirement | Why it matters here | Status (`hrms-auth` v1.5.0) |
|---|---|---|---|
| K-1 | **The app reads identity from the same token Kong validated.** | Kong validates `Authorization: Bearer …`. The app used to read a *separate* token (`?token=`, then a custom `token` header), so the two could differ (see V-01). | ✅ **Handled in the app** (uncommitted controller change): the app now reads the `Authorization` header, which is the same one the plugin verifies. The plugin itself still doesn't pass the verified `sub` upstream (P-6), so the app re-decodes the token. That's safe only while K-2 holds. |
| K-2 | **The pod is reachable only through Kong.** | Anything that reaches the pod directly (another pod in the cluster, a port-forward, a misrouted ingress) skips auth entirely. Enforce this with a K8s `NetworkPolicy` that allows ingress only from the Kong namespace/pods. | ⚠️ **Handled from outside the cluster; still open inside it.** ✅ From outside: confirmed by the team (2026-09-27) that the only route to this service is a private Kong route. The chart agrees: the Service is `ClusterIP` (the `common-chart` default, since pp/prod values set no `type`), and there's no Ingress, LoadBalancer, NodePort or `externalIPs`. ❌ From inside: there's no NetworkPolicy, so any pod in the cluster can call `hrms-utility.<ns>.svc` directly and skip Kong. Exploiting that needs a foothold in the cluster (a compromised pod, or an SSRF in another service). |
| K-3 | **Every route to this service has the auth plugin**, with no anonymous-consumer fallback. | One new route without the JWT/OIDC plugin exposes the whole API. Keep Kong config in git (decK) and review it with this service. | ❔ Depends on route/service config, which isn't in either repo. ⚠️ Also, the plugin forwards **every** `OPTIONS` request unauthenticated, not just CORS preflights (P-3). |
| K-4 | **Kong checks `exp`** (and `iss`/`aud` where supported). | Kong's `jwt` plugin **does not verify `exp` unless `claims_to_verify: [exp]` is set**. The `openid-connect` plugin does check it. | ⚠️ **Mostly handled.** ✅ RS256 signature (with `alg` pinned), ✅ `exp`, ✅ `iss`, ✅ `azp == hrms-client`. ❌ `aud` isn't checked: the block is an empty comment and its error branch is dead code (P-1). ❌ `nbf` and token `typ` aren't checked, so a Keycloak **ID token** is accepted as an access token (P-2). |
| K-5 | **Kong ties the tenant to the token.** | Kong checks that the token is valid, but nothing checks that `X-Tenant-Id` matches the token's realm (see V-05). | ✅ **Handled.** JWKS is fetched from `/realms/<X-Tenant-Id>`, and `iss` must equal `…/realms/<X-Tenant-Id>`, so a tenant-A token with `X-Tenant-Id: B` is rejected. A missing tenant header gets a 401. The tenant format is restricted to `[A-Za-z0-9._-]`. |
| K-6 | **Kong strips client-supplied identity headers** (`X-Userinfo`, `X-Authenticated-Userid`, `X-Consumer-*`, etc.) before setting its own. | Only needed if the app switches to reading a Kong-injected header. | ❌ **Not handled**, but not needed yet: the plugin sets no identity headers, and the app reads none. It becomes required once P-6 is done. |

### Issues found in the `hrms-auth` plugin itself

| # | Severity | Issue | Fix |
|---|---|---|---|
| P-1 | Medium | **`aud` is never validated.** `validate_claims` has an "AUDIENCE" comment with no code under it. The `"Token audience does not contain client"` branch in `access()` can never fire. | Accept `aud` as a string or an array and require it to contain the expected audience. Configure a Keycloak audience mapper if needed. |
| P-2 | Medium | **Any RS256 token from the realm with `azp=hrms-client` is accepted**, including ID tokens. `typ` isn't checked, and neither is `nbf`. | Require `claims.typ == "Bearer"`. Reject when `nbf > now` (allow a small leeway for clock skew). |
| P-3 | Low–Medium | **All `OPTIONS` requests skip auth** and reach the upstream, not just CORS preflights. | Pass through only when `Access-Control-Request-Method` is present, or answer preflights in Kong's `cors` plugin (runs earlier, priority 2000) and don't forward them. |
| P-4 | Medium (DoS) | **Unauthenticated callers can make Kong call Keycloak on demand.** (a) A token with a random `kid` makes `refresh_jwks` drop the cache and refetch on *every* request. Signature checking happens after this step, so any junk token works. (b) Every random but well-formed `X-Tenant-Id` is a cache miss, and failures aren't cached, so each request also goes out to Keycloak. | Allow at most one forced refresh per tenant per ~30–60 s. Cache failed or unknown-tenant lookups for a short time. Put Kong `rate-limiting` in front. Optionally, check the tenant against a known list. |
| P-5 | Low | `KEYCLOAK_BASE_URL` (`accounts.pp…`) and `CLIENT_ID` are hardcoded, so the same build can't be used in prod. `azp` pinning will also reject service-account tokens, which V-03/V-17 fixes need. | Move both to `schema.lua` config (`client_ids` as an array). |
| P-6 | Low (hardening) | The plugin doesn't pass the verified identity upstream. | After verifying: clear any client-supplied `X-User-Id` (and `X-Userinfo` etc.), then `kong.service.request.set_header("X-User-Id", claims.sub)`. Also strip `token` from the query string. The app then reads `X-User-Id` without decoding tokens itself. V-01 and V-07 are already fixed in the app; this adds defense in depth and a cleaner contract. |
| P-7 | Low | Returns **401** when JWKS/Keycloak is unavailable, so clients will probably log the user out during a Keycloak outage. | Return 503 for `AUTH_JWKS_UNAVAILABLE`. |

---

## 0. TL;DR

1. ~~**Kong's token check can be bypassed at the app level.**~~ ✅ **Fixed (uncommitted controller
   change).** The app now takes the user id from the same `Authorization` header that `hrms-auth`
   verifies. It still decodes the token without verifying it. The service is reachable from outside
   only through a private Kong route, so that path is covered, but other pods in the cluster can
   still call it directly until a NetworkPolicy is added (K-2, V-01).
2. **Tenant isolation is broken in the app.** On its own, the app lets a valid token for tenant A
   plus `X-Tenant-Id: B` read and write tenant B's database, and a missing or unknown header falls
   back to the `tomato` tenant's database. **Through Kong, the `hrms-auth` plugin now blocks this**
   (K-5), but the app still fails open for anything that doesn't go through Kong (V-05).
3. **Whoever can reach `POST /action-item` can approve anyone's leave/WFH/timesheet.** They create an
   action item naming themselves as assignee, then approve it (V-03). The endpoint is meant to be
   called only by another service, not the UI, so how bad this is depends on Kong routing, which is
   **unverified**: if the Kong route exposes the POST, any logged-in user can do it; if not, only
   callers inside the cluster can (no NetworkPolicy, K-2). Either way it's an authorization bug in
   the app, and the plugin's token check doesn't stop it.
4. **Most endpoints have no authorization.** Kong confirms *who* you are, but nothing checks *what*
   you may access, so any logged-in user can read or change any item (V-02).
5. **Production DB credentials are committed** in `application-prod.properties`, and they stay in
   git history.
6. **API responses return employee `password` fields** (`EmployeeDto.password`).
7. **There is no global exception handling.** Not-found, forbidden and bad-input errors all come back
   as HTTP 500.
8. **There are no tests, no Dockerfile, no health checks and no DB migrations,** and the prod Helm
   values are copied from another service (`meta`).

> ⚠️ **`security.md` is out of date.** It describes `SecurityConfig`, `MultiTenantJwtDecoder`,
> `JwtAuthenticationEntryPoint` and Spring Security/OAuth2 dependencies. **None of these exist in
> this repo or its git history.** Since Kong does authentication, rewrite `security.md` to describe
> the real model (Kong authenticates; the app authorizes and enforces tenant isolation) so nobody
> relies on the old version.

---

## 1. Vulnerabilities

### Critical

#### V-01 — The app reads identity from a token Kong never checked (bypasses Kong's auth) — ✅ fixed in the app (uncommitted)
- **Status (2026-09-27):** ✅ **Fixed in the app.** `getAssignedItems`, `getInitiatedItems` and
  `updateActionItemStatus` (`ActionItemController.java:36,52,76`) now take
  `@RequestHeader(required = true) String authorization`. Spring uses the parameter name as the
  header name, and header names are case-insensitive, so this binds to `Authorization`, which is the
  header `hrms-auth` verifies. `JwtUtil` strips the `Bearer ` prefix, and
  `ActionItemService.updateStatus` receives the same value. The forged-`token` exploit no longer
  works.
- **Kong plugin (`hrms-auth` v1.5.0):** verifies `Authorization` and forwards it unchanged, which is
  what this fix relies on.
- **What was wrong:** the app decoded the user id from a separate token (`?token=`, briefly a custom
  `token` header). Kong never checked that token, so a caller could send their own valid token in
  `Authorization` and a forged `sub` in `token`.
- **Remaining follow-ups:**
  - **This fix depends on K-2.** `JwtUtil` still decodes the token **without verifying** it. From
    outside the cluster this is covered, because the only route is a private Kong route and the
    Service is `ClusterIP`. **Inside the cluster**, any pod can still reach the Service directly with
    `Authorization: Bearer <forged>` and act as any user. Close this with a NetworkPolicy that allows
    ingress only from Kong's pods, or with in-app signature verification (P1).
  - **Name the header explicitly:** `@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization`.
    The current binding depends on the parameter name surviving compilation. `spring-boot-starter-parent`
    enables `-parameters` today, but renaming the variable would silently change which header is read.
  - **Move it into one place:** use a `CurrentUser` resolver (`HandlerMethodArgumentResolver`) so
    controllers don't parse tokens themselves. The other endpoints (V-02) will need the caller id too.
  - Optional defense in depth: verify the signature in-app with
    `spring-boot-starter-oauth2-resource-server` (JWKS is cached, so it's cheap), or have Kong inject
    `X-User-Id` (P-6). Either one protects you if K-2 or K-3 slips.

#### V-02 — Most endpoints have no authorization at all (IDOR)
- **Kong plugin (`hrms-auth` v1.5.0):** ➖ Out of scope. This is authorization, so it has to be fixed in the app.
- **Where:** `ActionItemController.java`
- **Unprotected endpoints:**

  | Endpoint | Impact |
  |---|---|
  | `POST /action-item` | Anyone can create items with arbitrary `initiatorUserId`/`assigneeUserId` |
  | `GET /action-item/{id}` | Read any action item by enumerating sequential IDs |
  | `PUT /action-item/{id}/mark-seen` | Modify anyone's item |
  | `GET /action-item/assignee/{userId}/count` | Probe any user's pending counts |
  | `GET /action-item/reference/{referenceId}[/type\|/status]` | Read items by enumerating reference IDs |
- **Why Kong doesn't cover this:** Kong answers "is this a valid user?". It doesn't know who owns
  action item 42. Any employee with a valid token can call all of these.
- **Fix:** Take the caller id from the Kong-validated token (V-01), never from the path, request
  body or query. Add ownership checks in the service layer: the caller must be the initiator or
  assignee, or hold a role (e.g. `realm_access.roles` contains `HR_ADMIN`). Replace `/assignee/{userId}`
  and similar paths with `/assignee/me`-style endpoints.

#### V-03 — Self-approval and privilege escalation on leave, WFH and timesheets
- **Kong plugin (`hrms-auth` v1.5.0):** ➖ Out of scope. It's an app authorization bug. (P-5: the `azp` pin would currently block the service-account tokens this fix needs.)
- **Where:** `ActionItemService.createActionItem` (line 40) plus `updateStatus` (line 81) and
  `changeExternalStatus` (line 113)
- **Who can exploit it (depends on Kong routing, unverified):** `POST /action-item` is not used by
  the UI; only an upstream service calls it. But "the UI doesn't call it" doesn't make it
  unreachable. Kong has to route `PUT /action-item/{id}/status` to end users so the UI can approve
  items, and Kong routes are usually a path prefix with no `methods`/`paths` limits. The user already
  has a valid token from their UI session, and the plugin only checks that the token is valid, not
  which endpoint it's used on.
  - **If the hrms-utility Kong route exposes `POST /action-item`:** any logged-in user can run the
    exploit below. **Critical.**
  - **If it doesn't** (the route limits methods/paths, and the upstream service calls hrms-utility
    at its cluster address): only something already inside the cluster can, because there's no
    NetworkPolicy (K-2). **High.** The fix is still needed: at the moment "only services create
    action items" depends on routing config that someone could widen later, not on the code.
  - **To verify:** check the hrms-utility route in Kong (deck file / Admin API `GET /routes` /
    Konnect) for `methods`/`paths` limits, and check whether the upstream service calls
    hrms-utility through Kong or at its cluster address. The route definitions aren't in this repo
    or in `~/hrms-auth`.
- **Exploit (works even after V-01 is fixed):**
  1. `POST /action-item` with `assigneeUserId=<me>`, `initiatorUserId=<victim or me>`, `type=LEAVE`,
     `referenceId=<any leave id>`.
  2. `PUT /action-item/{id}/status?status=APPROVED` with my own token. The only check is
     "caller == assignee", which the attacker controlled in step 1.
  3. The service calls `PUT {employee}/employee/{initiator}/leave-tracker/{ref}/status?status=APPROVED`,
     so the employee service approves the leave.
- **Fix:**
  - **Primary:** since only upstream services create action items, enforce that in the app.
    `POST /action-item` should require a service-account (client-credentials) token and reject
    user tokens. That holds whatever Kong's route allows. Blocked by P-5: the plugin's `azp` pin
    currently rejects service-account tokens.
  - **Defense in depth:** leave `POST /action-item` off the Kong route (use `methods`/`paths`
    limits), and have the upstream service call hrms-utility at its cluster address.
  - Validate what the calling service sends: `assigneeUserId` is the initiator's actual
    manager/approver according to the employee service, and `referenceId` belongs to the initiator.
  - `PUT /{id}/status` only checks "caller == assignee", so it's only as safe as the create step.

#### V-04 — Production DB password committed to the repo
- **Where:** `src/main/resources/application-prod.properties:5-7` (Railway Postgres user and
  password in plaintext). It is also in git history (`1d18246`, `65666ad`, …).
- **Also:** `application.properties:6` (`admin`) and `charts/hrms-utility/values/pp.yaml:36-38`
  (`SPRING_DATASOURCE_PASSWORD: "admin"` in plaintext).
- **Fix:** **Rotate the Railway password now.** Remove all credentials from properties and Helm
  values, and inject them from Vault. The prod chart already uses banzaicloud vault annotations, so
  pp should too. Consider purging history (`git filter-repo`), but treat the secret as burned
  either way. Add a secret scanner (gitleaks/trufflehog) to CI.

#### V-05 — Tenant comes from a client header, isn't tied to the token, and falls back to `tomato`
- **Kong plugin (`hrms-auth` v1.5.0):** ⚠️ **Mostly handled at the edge.** ✅ Cross-tenant: `iss` must match `X-Tenant-Id` (K-5). ✅ A missing tenant gets 401 at Kong. ✅ An unknown tenant fails the JWKS fetch and gets 401. ❌ The app itself still fails open to `tomato`, so any path that skips Kong (K-2, P-3 `OPTIONS`) still hits the default DB. Keep the app-side fixes as defense in depth.
- **Where:** `utility/TenantFilter.java:19-25`, `config/MultiTenantConfiguration.java:49`,
  `utility/MultitenantDataSource.java`
- **Cross-tenant access:** `X-Tenant-Id` is a plain client header. Kong verifies the token's
  signature, but unless Kong is specifically configured to (K-5), nothing checks that the header
  matches the realm the token was issued by. A user with a valid token from tenant A sends
  `X-Tenant-Id: B` and reads/writes tenant B's database. In a multi-realm setup, Kong has to trust
  every realm's keys, so this is very likely reachable.
- **Fail-open fallback:** `AbstractRoutingDataSource` falls back to the default target when the
  lookup key is missing or unknown. This happens when:
  - `X-Tenant-Id` is absent (`null`),
  - the filter resets the context to `""` (see V-10), or
  - the value is any unknown string.

  In each case the request reads and writes the **`tomato` tenant's database**.
- **Fix:**
  - Derive the tenant from the Kong-validated token: take the realm from `iss`
    (`…/realms/<tenant>`) or a dedicated `tenant` claim. Reject the request if `X-Tenant-Id` is
    present and differs. Or have Kong set `X-Tenant-Id` from the token and overwrite any client value.
  - Call `setLenientFallback(false)` and do not set a default target (or override
    `determineTargetDataSource` to throw).
  - In `TenantFilter`, reject requests with a missing or unknown tenant with 400/403 before they
    reach any handler.

#### V-06 — Employee passwords leak in API responses
- **Where:** `response/EmployeeDto.java:23-25` (`password`, `temporaryPassword`, `kcReferenceId`),
  embedded in `ActionItemResponse.initiatorUser`/`assigneeUser`
- **What:** `EmployeeDto` deserializes whatever the employee service returns and re-serializes all of
  it to the client. If the employee service ever populates `password` (hash or temp password), it
  goes straight to the browser. The DTO also exposes address, phone and email of other employees
  (PII) to anyone who can list items (see V-02).
- **Fix:** Delete `password`, `temporaryPassword` and `kcReferenceId` from this DTO. Build a minimal
  `EmployeeSummaryDto` (id, name, job title, maybe email) for the response. Also raise this with the
  employee service team: it should never return passwords.

### High

#### V-07 — Token passed as a query parameter — ✅ fixed in the app (uncommitted)
- **Kong plugin (`hrms-auth` v1.5.0):** ❌ Not handled. The plugin doesn't reject or strip `?token=`.
  This no longer matters for this service because the app doesn't read it, but old clients that
  still send it will keep leaking tokens into logs until they're updated.
- **Controller change (uncommitted, 2026-09-27):** ✅ **Fixed.** All three endpoints now read the
  token from the `Authorization` header, so it's no longer in URLs, access logs, browser history or
  APM traces. Remaining work: the frontend has to stop sending `?token=` and send
  `Authorization: Bearer …` instead.
- **Where (before the fix):** `ActionItemController.java:36,52,76` (`@RequestParam String token`)
- **What:** Besides the bypass in V-01, query strings end up in Kong access logs, LB logs, browser
  history, `Referer` headers and APM traces (OpenTelemetry is injected via Helm annotations and
  records `http.url`). Valid bearer tokens will leak and can be replayed through Kong until they
  expire.
- **Fix:** Use only the `Authorization: Bearer` header Kong validates. This is the same change as V-01.
  Frontend callers must stop sending `?token=`.

#### V-08 — Wildcard CORS (Medium if Kong handles CORS)
- **Kong plugin (`hrms-auth` v1.5.0):** ❌ Not handled. The plugin forwards all `OPTIONS` requests to the app, so the app's `*` policy is still what applies (see P-3).
- **Where:** `ActionItemController.java:17` (`@CrossOrigin(origins = "*")`)
- **What:** If Kong's `cors` plugin is enabled, the app's wildcard headers can still be added to or
  override Kong's, which leads to duplicate `Access-Control-Allow-Origin` headers (browsers reject
  them) or a wider policy than intended.
- **Fix:** Handle CORS in one place. Since traffic goes through Kong, use Kong's `cors` plugin with an
  explicit per-environment origin allow-list and remove `@CrossOrigin` from the app.

#### V-09 — Path injection / SSRF-lite into the employee service
- **Where:** `ActionItemService.java:116-126, 211-225, 241-267`
- **What:** `initiatorUserId`, `assigneeUserId` (free-form strings from `POST` body) and `referenceId`
  are concatenated into URLs. A value like `../../admin/something` or `x?status=APPROVED&foo=` changes
  the path or query sent to the employee service. That service trusts this one: it forwards
  `X-Tenant-Id` and no user auth.
- **Fix:** Validate IDs, e.g. UUID format for Keycloak `sub`, via `@Pattern` on the request DTO.
  Build URLs with `UriComponentsBuilder` and path variables (`restTemplate.exchange("{base}/employee/{id}/...", ..., uriVars)`),
  which encodes each segment.

#### V-10 — ThreadLocal tenant is never removed
- **Where:** `utility/TenantFilter.java:25`, `utility/TenantContext.java`
- **What:** `setCurrentTenant("")` leaves a value on pooled Tomcat threads instead of `remove()`. If
  any code path runs outside the filter (async, scheduled, error dispatch), it uses `""`, which then
  falls back to the default tenant (V-05).
- **Fix:** Add `TenantContext.clear()` → `CURRENT_TENANT.remove()` and call it in `finally`. Make
  `TenantFilter` a `OncePerRequestFilter`.

#### V-11 — Unauthenticated tenant-config API returns every tenant's DB credentials
- **Kong plugin (`hrms-auth` v1.5.0):** ➖ Not applicable. This is an outbound call from the app and doesn't go through the plugin.
- **Where:** `config/MultiTenantConfiguration.java:56-73`
- **What:** A plain `new RestTemplate().getForEntity(url)` fetches `{tenantId, dbUrl, username, password}`
  for all tenants with no auth, mTLS or response integrity check. If that endpoint is reachable
  without auth (it defaults to a public `*.up.railway.app` URL), every tenant's DB credentials are
  public.
- **Fix:** Confirm the tenant-management endpoint requires auth. Call it with a client-credentials
  token or mTLS, and prefer internal-only networking. Longer term, store per-tenant DB credentials in
  Vault instead of serving them over HTTP.

#### V-12 — Status transitions are not validated (double-processing)
- **Where:** `ActionItemService.updateStatus` (line 81)
- **What:** Any status can be set from any status: `APPROVED → REJECTED → APPROVED`, or back to
  `PENDING`. Each call re-invokes the employee service, so leave/WFH deduction logic can run
  repeatedly. There is also no optimistic locking, so two concurrent approvals both go through.
- **Fix:** Allow only `PENDING → APPROVED|REJECTED`. Reject `PENDING` as a target. Add `@Version` to
  `ActionItem`. Make the external call idempotent, keyed on action-item id.

### Medium

#### V-13 — Debug and SQL logging in the active prod profile
- `application.properties:3` hardcodes `spring.profiles.active=prod`. Local runs use prod config,
  and prod always gets `spring.jpa.show-sql=true` from the base file.
- `application-prod.properties:12` sets `logging.level.org.springframework.security=DEBUG`.
- `ActionItemController.java:27` logs the full request (`log.info("Creating action item: {}", req)`),
  including free-text descriptions. User IDs and full downstream URLs are logged at INFO everywhere.
- **Fix:** Remove `spring.profiles.active` from the base file and set it via env. Turn off
  `show-sql`. Use INFO for app logs and WARN for frameworks in prod. Don't log request bodies.

#### V-14 — No input validation
- `ActionItemRequest` has `@NotBlank`/`@NotNull`, but:
  - the controller has no `@Valid`, and
  - `pom.xml` only has `jakarta.validation-api`. There is no implementation
    (`spring-boot-starter-validation` / Hibernate Validator), so the annotations do nothing.
- There are no length limits on `title`, `description` or `remarks`, and no format check on user IDs.
- **Fix:** Add `spring-boot-starter-validation` and `@Valid @RequestBody`. Add `@Size` and
  `@Pattern` constraints and matching DB column lengths.

#### V-15 — No rate limiting or pagination (DoS amplification)
- **Kong plugin (`hrms-auth` v1.5.0):** ❌ Not handled. The plugin does no rate limiting, and it adds its own amplification risk towards Keycloak (P-4).
- `GET /assignee/{userId}` and `/initiator/{userId}` return unbounded lists, and each item triggers
  **3 synchronous HTTP calls** (initiator, assignee, reference). A user with 500 items costs 1,500
  downstream calls per request.
- **Fix:** Paginate (`Pageable`). Batch-fetch employees, or cache them (Caffeine, short TTL). Add
  per-consumer rate limiting with Kong's `rate-limiting` plugin and a request-size limit with
  `request-size-limiting`. No in-app limiter is needed.

#### V-16 — Outbound HTTP has no timeouts
- **Where:** `config/RestTemplateConfig.java`, `MultiTenantConfiguration.java:57`
- **What:** Default `RestTemplate` timeouts are infinite. A slow employee service will pin all Tomcat
  threads.
- **Fix:** `builder.connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(5))`, then add
  a circuit breaker/retry (Resilience4j) around employee-service calls.

#### V-17 — Service-to-service calls carry no identity
- **Kong plugin (`hrms-auth` v1.5.0):** ❔ This app calls `employee.pp.hrms.work` with only `X-Tenant-Id` and no `Authorization` header. If `hrms-auth` were on those employee-service routes, those calls would get a 401. So if the calls are succeeding, the routes are almost certainly **not** covered by the plugin. Confirm in the Kong config.
- Calls to the employee service send only `X-Tenant-Id`, with no `Authorization` header. So either:
  - `employee.pp.hrms.work` is routed through Kong **without** the auth plugin on the status routes,
    and anyone on the internet can approve leave directly, or
  - it's called internally, bypassing Kong, and any pod in the cluster can.
- **Fix:** Forward the caller's `Authorization` header, or use a Keycloak client-credentials
  (service-account) token. Call the employee service at its internal cluster address, and keep its
  status-change routes off the public Kong routes.

#### V-18 — Outdated framework
- Spring Boot `3.4.5`: the 3.4.x line is past OSS support, so it no longer gets CVE fixes.
- **Fix:** Upgrade to a supported Boot line. Add OWASP dependency-check or Snyk/Dependabot to CI.

### Low

- **V-19** JPA entity returned directly (`GET /{id}`, `/reference/*`, `PUT /{id}/status`). This leaks
  internal schema and risks mass assignment if it's ever bound as input. Return DTOs.
- **V-20** Sequential `IDENTITY` IDs make enumeration trivial (see V-02). Authorization is the real
  fix; UUIDs are a bonus.
- **V-21** `prod.yaml` has `vault.security.banzaicloud.io/vault-skip-verify: "true"`, which disables
  TLS verification to Vault.
- **V-22** `ServiceAccount.automountServiceAccountToken: true` is not needed by this app. Set it to `false`.
- **V-23** Error messages include internal IDs ("Action item not found with id …"). That's fine
  once a proper handler controls the response body.

---

## 2. Exception handling assessment

**Overall: poor.** There is no `@RestControllerAdvice`, no custom exception types and no consistent
error body. Almost every failure becomes a generic **HTTP 500**.

| Situation | Thrown | What client gets today | What it should be |
|---|---|---|---|
| Item id not found (`getActionItemById`, `updateStatus`, `markAsSeen`) | `jakarta.persistence.EntityNotFoundException` | 500 | 404 |
| Caller isn't assignee (`updateStatus:97`) | `java.lang.SecurityException` | 500 | 403 |
| Token missing/malformed (`JwtUtil`) | raw `RuntimeException` | 500 | 401 (should be rare since Kong rejects these first) |
| `Authorization` header absent (`/assignee`, `/initiator`, `/{id}/status`; only reachable when bypassing Kong) | `MissingRequestHeaderException` | 400 (Spring default) | 401 with clear message |
| `extractUserId` → `.equals(userId)` | — | correct 403 (controller only) | 403 via common handler |
| Unsupported type in `callExternalService` | `IllegalArgumentException` | 500 | 400 / or skip |
| Employee service 4xx/5xx during listing | `HttpClientErrorException` etc. (uncaught) | 500, **whole list fails** | Degrade: return item with `initiatorUser=null` + log |
| Employee service fails during status change | wrapped `RuntimeException("Unable to change status")` | 500 | 502/503 with error code |
| Employee service slow | — | hangs forever (no timeout) | 504 after timeout |
| Bad enum in `?status=` | `MethodArgumentTypeMismatchException` | 400 (Spring default) | 400 with clear message |
| Validation failure | nothing (validation inert, V-14) | request accepted | 400 with field errors |
| DB constraint violation (null `title` etc.) | `DataIntegrityViolationException` | 500 | 400/409 |

**Specific defects:**

1. **`MultiTenantConfiguration.fetchTenantConfigsFromApi` (lines 58-72):** The exception is swallowed
   with `log.info(ex)`, leaving `response == null`, and then `response.getStatusCode()` throws an
   **NPE**. Startup fails with a misleading stack trace, and the real cause is logged at INFO.
   Fix: log at ERROR and rethrow a descriptive `IllegalStateException`. Consider retry with backoff at
   startup.
2. **Default datasource may be `null`** (line 49). If `defaultTenant` isn't in the API response,
   `setDefaultTargetDataSource(null)` fails later with an unclear error. Validate it and fail fast.
3. **`JwtUtil`:** Catches `RuntimeException` only to rethrow it, and throws a generic
   `RuntimeException` that drops the cause (line 51). Use a dedicated `InvalidTokenException` and keep
   the cause.
4. **`updateStatus` is not transactional and orders side effects badly.** The external status change
   happens *before* the local `save`. If the save fails, the employee service says APPROVED and the
   action item still says PENDING, with no compensation. Fix: annotate `@Transactional`. Persist
   first, then call downstream, or use an outbox pattern. At minimum, catch and reconcile.
5. **`changeExternalStatus`:** The `!is2xxSuccessful()` branch (line 139) is effectively dead code,
   because `RestTemplate` throws on 4xx/5xx by default. It also throws a raw `RuntimeException`.
   Introduce `DownstreamServiceException`.
6. **`mapToResponse` has no fault isolation.** One missing employee or reference fails the entire
   list for the user.
7. **`TenantContext.getCurrentTenant()` can be `null`,** and `headers.set("X-Tenant-Id", null)` then
   silently sends an empty header downstream.
8. **Log level misuse:** Stack traces are logged and then rethrown, so they get logged again by the
   container. There are no correlation or request IDs in logs.

**Recommended design:**
- Custom exceptions: `ResourceNotFoundException`, `ForbiddenException`, `InvalidRequestException`,
  `DownstreamServiceException`, `TenantNotFoundException`.
- One `@RestControllerAdvice` that maps these, plus `MethodArgumentNotValidException`,
  `MethodArgumentTypeMismatchException`, `DataIntegrityViolationException` and a fallback
  `Exception`, to **RFC 7807 `ProblemDetail`** (built into Spring 6), with an `errorCode` and a
  `traceId`. Never return stack traces or internal messages for 5xx.
- An MDC filter that adds `traceId`, `tenant` and `userId` to every log line.

---

## 3. Code quality issues (non-security)

- `ActionItemService.updateStatus` sets `updatedAt` manually although `@PreUpdate` already does it.
  `markAsSeen` does the same.
- **Duplicate enums:** `ActionItemRequest.ActionType`, `ActionItemResponse.ActionType` and
  `ActionItemResponse.ActionStatus` duplicate `ActionItem`'s enums. The response ones are unused.
  Keep one set in a shared package.
- `callExternalService(String type, String userId)` builds `base + "/" + userId` when
  `type != "employee"`, which is an unintended URL. The `type` parameter is effectively always
  `"employee"`; remove it.
- **URL inconsistency:** Status updates use `/employee/...` for LEAVE/WFH and `/employees/...` for
  TIMESHEET. Reads use `/employees/{id}/wfh/{ref}` but writes use `/employee/{id}/wfh-tracker/{ref}`.
  Verify these against the employee service contract. Contract tests would catch this.
- **Dead code:** `deleteActionItem` controller method has its mapping commented out.
  `@ConfigurationProperties(prefix = "tenants")` on the `dataSource()` bean does nothing.
  `spring.data.elasticsearch.repositories.enabled` has no Elasticsearch dependency.
  `spring.datasource.*` in both property files is unused, because the custom `DataSource` bean replaces it.
- Unused imports: `RequestBody` in `ActionItemRepo`, `EqualsAndHashCode` in DTOs, `Optional` style.
- Field injection with `@Autowired`. Switch to constructor injection (`@RequiredArgsConstructor`)
  for testability.
- `@Log4j2` with Spring Boot's default Logback works via the bridge, but use `@Slf4j` to match the
  backend.
- Every per-tenant `DataSource` gets default Hikari settings (10 connections each) with no pool name,
  max lifetime or leak detection. With N tenants × 2 replicas × 10, check against Postgres
  `max_connections`.
- New tenants need an app restart because tenant DataSources are built once at startup. Add a
  refresh mechanism, such as a lazy lookup with cache, or a scheduled refresh.
- `LocalDateTime.now()` uses the JVM default timezone. Use `Instant`/`OffsetDateTime` or `timestamptz`
  and pin `-Duser.timezone=UTC`.
- `description`, `remarks` and `title` have no column length definitions.
- The `pom.xml` metadata is template boilerplate (empty `<url/>`, `<licenses>`, `<scm>`).

---

## 4. Production-readiness gaps

### Testing — *none exist*
- There is no `src/test` directory, and the test dependency was removed in commit `71a03d8`.
- **Needed:**
  - Unit tests for `ActionItemService`, especially authorization, status transitions and external
    failure paths.
  - `@WebMvcTest` for controllers, covering authz, validation and error mapping.
  - Testcontainers Postgres for repository and multi-tenant routing tests.
  - WireMock for employee-service and tenant-API contracts.
  - Security tests: forged JWT is rejected, cross-user access gets 403, missing tenant gets 400.
- Add JaCoCo with a coverage gate in CI.

### Build & deploy
- **The Dockerfile was deleted** (`2e47287`). Add a multi-stage Dockerfile: non-root user, distroless
  or `eclipse-temurin:17-jre` base, `-XX:MaxRAMPercentage`.
- **`charts/hrms-utility/values/prod.yaml` belongs to another service (`meta`):** wrong image repo,
  port `8086` vs app `9099`, `SPRING_JPA_HIBERNATE_DDL_AUTO: update` (dangerous in prod), and
  unrelated env vars (Redis, CSV paths, `TENANT_DB_CONFIG_URL` with an empty key). Rewrite it for
  hrms-utility.
- **`jenkins/prod/Jenkinsfile`** points to `charts/meta/`. Fix the path. The Jenkinsfile should
  also build, test and scan (SAST, dependency, secrets, container image) before deploying.
- Helm env var `DEFAULT_TENANT` doesn't bind to `@Value("${defaultTenant}")`: relaxed binding maps it
  to `default.tenant`. Rename the property to `tenant.default` and read it as `${tenant.default}`.
- `EMPLOYEE_URL` is not set in `pp.yaml`, so the hardcoded `employee.pp.hrms.work` default is used.
  Remove hardcoded defaults for environment-specific URLs so a misconfiguration fails fast.
- Image tags are static (`v1.0.0`). Use immutable per-build tags or digests.
- The chart has no liveness/readiness/startup probes, PodDisruptionBudget, HPA, securityContext
  (`runAsNonRoot`, `readOnlyRootFilesystem`) or NetworkPolicy, unless `common-chart` provides them.
  Verify.

### Observability & operations
- Add `spring-boot-starter-actuator`. Expose only `health` (liveness/readiness groups), `info` and
  `prometheus` on a separate management port.
- Add a custom health indicator per tenant DataSource and one for employee-service reachability.
- Use structured JSON logging with traceId/tenant/userId in MDC. The OTel agent is already injected,
  so make sure log correlation is wired.
- Add metrics for downstream call latency/errors and action items by status.
- Enable graceful shutdown (`server.shutdown=graceful`).

### Data
- Add Flyway or Liquibase. `ddl-auto=none` with no migrations means schema changes are applied by
  hand across N tenant databases.
- Index `assignee_user_id`, `initiator_user_id`, `reference_id` and `(assignee_user_id, status)`.
  These are all queried with no evidence of indexes.
- Consider a unique constraint on `(type, reference_id)` to stop duplicate action items for the same
  leave or timesheet.
- Soft delete and an audit trail for approvals: who approved, when and from where. For HR data
  this is usually a compliance requirement.

### API
- Version the API (`/api/v1/action-items`) and use plural resource naming.
- Publish an OpenAPI spec (springdoc).
- Use `PATCH` or a dedicated action endpoint (`POST /{id}/approve`), with remarks in a JSON body
  rather than query params.
- `POST` should return `201 Created` with a `Location` header.

---

## 5. Prioritized backlog

### P0 — do before next prod deploy
- [ ] **Rotate the Railway prod DB password.** Move all secrets to Vault/env and scrub the
      properties files and `pp.yaml` (V-04)
- [x] ~~Read the user id only from the Kong-validated `Authorization` header (V-01, V-07)~~: done
      (uncommitted controller change). Commit it, and update the frontend to send
      `Authorization` instead of `?token=`
- [ ] Follow-up: give the header name explicitly (`HttpHeaders.AUTHORIZATION`) and move token
      parsing into one `CurrentUser` resolver (V-01)
- [x] ~~Derive the tenant from the token's realm and reject a mismatched `X-Tenant-Id`~~: done at
      Kong by `hrms-auth` (K-5)
- [ ] App side: fail closed on missing/unknown tenant, and use `ThreadLocal.remove()` (V-05, V-10)
- [ ] Add ownership/role checks on **every** endpoint (V-02)
- [ ] Lock down action-item creation: set the initiator from the token, validate the assignee and
      reference, and restrict creation to service accounts (V-03)
- [x] ~~No public exposure: only reachable through a private Kong route~~: confirmed by the team, and
      the chart has a `ClusterIP` Service with no Ingress (K-2, external part)
- [ ] Kong: `hrms-auth` on every route to this service, and Kong config in git (K-3). `exp`
      verification is ✅ done in the plugin (K-4)
- [ ] `hrms-auth`: inject the verified `X-User-Id` and strip client-supplied identity headers (P-6).
      No longer needed for V-01, but it's a cleaner contract and defense in depth
- [ ] `hrms-auth`: validate `aud` and `typ`/`nbf`, restrict the `OPTIONS` bypass to real preflights,
      and throttle forced JWKS refresh and unknown-tenant fetches (P-1, P-2, P-3, P-4)
- [ ] Confirm the employee service's status-change endpoints aren't publicly reachable without auth
      (V-17)
- [ ] Strip `password`/`temporaryPassword`/`kcReferenceId` from `EmployeeDto` and return a minimal
      employee summary (V-06)
- [ ] Update or remove the stale `security.md`

### P1 — next sprint
- [ ] NetworkPolicy allowing ingress to this pod only from Kong's pods/namespace, so other pods in
      the cluster can't bypass Kong with a forged token (K-2 in-cluster part, V-01)
- [ ] Global `@RestControllerAdvice` with `ProblemDetail`, custom exceptions and correct status codes
      (§2)
- [ ] Fix startup NPE in `fetchTenantConfigsFromApi` and validate the default tenant (§2.1, §2.2)
- [ ] Enforce the status state machine, `@Version` optimistic locking and idempotent downstream call
      (V-12)
- [ ] Make `updateStatus` `@Transactional` and fix side-effect ordering (§2.4)
- [ ] Remove `@CrossOrigin("*")` and handle CORS only in Kong, with an env-specific allow-list (V-08)
- [ ] Build downstream URLs with `UriComponentsBuilder` and validate ID formats (V-09)
- [ ] Add `spring-boot-starter-validation`, `@Valid` and `@Size`/`@Pattern` constraints (V-14)
- [ ] RestTemplate timeouts, plus Resilience4j circuit breaker/retry (V-16)
- [ ] Authenticate calls to the tenant-config API and the employee service (V-11, V-17)
- [ ] Remove `spring.profiles.active=prod` from the base properties. Turn off `show-sql` and
      security DEBUG logging (V-13)
- [ ] Test suite: unit, WebMvc, Testcontainers and WireMock. Re-add `spring-boot-starter-test` and
      `spring-security-test`

### P2 — hardening
- [ ] Pagination on list endpoints and batching/caching of employee lookups (V-15)
- [ ] Return DTOs instead of entities, and remove duplicate enums and dead code (V-19, §3)
- [ ] Actuator health/readiness/prometheus, structured logs and MDC (§4)
- [ ] Flyway migrations and DB indexes (§4 Data)
- [ ] Dockerfile, a corrected prod Helm values/Jenkinsfile, probes, securityContext and
      NetworkPolicy (§4 Build)
- [ ] Upgrade Spring Boot to a supported line. Add dependency, secret and image scanning to CI (V-18)
- [ ] Hikari pool sizing per tenant, and a tenant refresh without restart (§3)
- [ ] Kong `rate-limiting` and `request-size-limiting` plugins (V-15)
- [ ] `hrms-auth`: move the Keycloak URL and client id into config, and return 503 when JWKS is down (P-5, P-7)
- [ ] Optional defense in depth: in-app JWT signature verification with the OAuth2 resource server (V-01)
- [ ] Audit log for approve/reject actions

### P3 — nice to have
- [ ] API versioning, OpenAPI docs and REST semantics cleanup (§4 API)
- [ ] Constructor injection, `@Slf4j`, UTC timestamps (§3)
- [ ] `vault-skip-verify: false`, `automountServiceAccountToken: false` (V-21, V-22)
