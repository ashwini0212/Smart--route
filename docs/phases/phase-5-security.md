# Phase 5: Authentication, roles and API security

## STEP 1: What we built

| Piece | Purpose |
|---|---|
| `auth` module | Users (`app_user`), login, refresh-token rotation, logout, password change, admin user management |
| `V2__auth.sql` | `app_user` and `refresh_token` tables with CHECK constraints and partial index |
| `SecurityConfig` | Stateless filter chain: JWT validation, URL rules, CORS allow-list, security headers |
| `common.security` | `Role`, `CurrentUser` (read from the verified token), `Access` (role constants and ownership checks) |
| `common.ratelimit` | Token-bucket rate limiter, used for login attempts |
| `@PreAuthorize` on every existing endpoint | The role matrix below |
| Seed logins | One demo login per role, password from `DEMO_USER_PASSWORD` (never in code) |

## STEP 2: How a request is authenticated

```
Browser                         nginx (frontend)              Spring Boot
  │ POST /api/auth/login  ───────────►  sets X-Forwarded-For ──► rate limit (IP + email)
  │                                                              BCrypt check
  │ ◄── 200 {accessToken, user}                                  issue JWT (15 min)
  │     Set-Cookie: smartroute_refresh=…; HttpOnly; Secure;      issue refresh token (7 days, hash stored)
  │                 SameSite=Strict; Path=/api/auth
  │
  │ GET /api/orders  Authorization: Bearer <JWT>  ─────────────► verify signature, issuer, expiry
  │                                                              role claim → ROLE_VIEWER
  │                                                              @PreAuthorize(STAFF_OR_VIEWER) ✓
  │
  │ (token expired or page reloaded)
  │ POST /api/auth/refresh  (cookie sent automatically) ───────► revoke old refresh token, issue new one,
  │ ◄── 200 {new accessToken} + new cookie                       role re-read from the database
```

No server session exists. Every API request is verified from the token alone, so any number of app instances can serve it.

## STEP 3: Role matrix (enforced on the server)

| Endpoint | ADMIN | DISPATCHER | VIEWER | DRIVER |
|---|---|---|---|---|
| Read orders, history | ✓ | ✓ | ✓ | ✗ (their own deliveries: `/api/deliveries/mine`, Phase 7) |
| Create / cancel orders | ✓ | ✓ | ✗ | ✗ |
| List drivers, vehicles | ✓ | ✓ | ✓ | ✗ |
| Read a driver | ✓ | ✓ | ✓ | own record only |
| Change driver availability | ✓ | ✓ | ✗ | own record only |
| Create / edit drivers, vehicles, warehouses | ✓ | ✗ | ✗ | ✗ |
| Read warehouses | ✓ | ✓ | ✓ | ✓ |
| `/api/admin/users` | ✓ | ✗ | ✗ | ✗ |
| `/api/auth/me`, change own password | ✓ | ✓ | ✓ | ✓ |

**Where the role comes from:** only from the signed token, which only the server can produce, and the server copies it from the database at login and at every refresh. The client never sends a role. `AuthorizationTest.tokenWithEditedRoleClaimIsRejected` edits `"VIEWER"` to `"ADMIN"` inside a real token and gets 401, because the signature no longer matches.

**Ownership:** `@PreAuthorize(Access.STAFF + " or @access.isDriver(#id)")`. `Access.isDriver` compares the path id with the `driverId` claim; a driver login is linked to exactly one driver record (a database CHECK enforces "DRIVER role ⇔ driver_id set").

**Guard against forgetting:** an ArchUnit rule fails the build if any POST/PUT/PATCH/DELETE endpoint has no `@PreAuthorize`. I checked that it works by deleting one annotation: both the architecture test and the role-matrix test failed.

## STEP 4: Threats and what stops them

| Threat | Mitigation | Test |
|---|---|---|
| Password database leak | BCrypt (cost 12 in production), per-password salt | `passwordsAreStoredAsBcryptHashes` |
| Refresh token database leak | Only SHA-256 hashes stored | `refreshTokensAreStoredOnlyAsHashes` |
| Stolen refresh token | Rotation on every use; replaying an old token revokes the whole family | `reusingARotatedRefreshTokenRevokesTheWholeFamily` |
| XSS reading tokens | Refresh token is HttpOnly; access token lives in memory, not localStorage | cookie flags asserted |
| CSRF on the cookie endpoint | SameSite=Strict, path-scoped cookie; API calls use a header, not cookies | |
| Forged / unsigned / foreign tokens | Signature, algorithm pinned to HS256, issuer and expiry checked | `alg: none`, other key, other issuer, expired, edited role |
| Password guessing | Token bucket: 5/min per email, 20/min per client IP; `429` with `Retry-After` | `loginIsRateLimited*`, `ForwardedClientIpTest` |
| Account enumeration | Same message for unknown email, wrong password, disabled account; BCrypt runs against a dummy hash for unknown emails so timing matches | `wrongPasswordUnknownEmailAndDisabledAccountLookTheSame` |
| Admin lockout | An admin can't disable or demote themselves; the last enabled admin can't be removed | `adminCannotDisableOrDemoteThemselves` |
| Silent BCrypt truncation | Passwords over 72 UTF-8 bytes are rejected, not truncated | `passwordRulesAreEnforced` |
| Clickjacking, MIME sniffing, referrer leaks | `X-Frame-Options: DENY`, CSP `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer` | `securityHeadersAreSet` |
| Other websites calling the API from a browser | CORS allow-list (dev origins only by default) | `corsAllowsOnlyConfiguredOrigins` |
| Secret in Git | `JWT_SECRET` has no default; the app refuses to start without ≥ 32 bytes and warns while the example value is used | startup check |

**Known limits (stated, not hidden):**
- An access token stays valid for up to 15 minutes after a user is disabled or their role changes. Their refresh tokens are revoked immediately.
- The rate limiter is in memory: with several app instances each allows the full rate. A Redis implementation of the same `RateLimiter` interface fixes that.
- Two tabs refreshing in the same instant can trip reuse detection and log the user out. A short grace period for the previous token is the usual fix; not built.

## STEP 5: DSA: token bucket

Each key (an email, an IP) has a bucket of up to `capacity` tokens that refills continuously at `capacity / period`. An attempt takes one token; with none left the server answers 429 with the wait until the next token.

- **Why not a fixed window** ("5 per calendar minute")? It allows 10 attempts in two seconds around a minute boundary. The bucket caps the long-run rate exactly.
- **Complexity:** O(1) per attempt (a hash-map lookup plus arithmetic on two numbers). Memory is bounded: past `maxKeys` entries, buckets that have fully refilled carry no information and are dropped in one O(n) sweep, amortized over many calls. A bucket that is still empty is never evicted, because that would hand out free attempts (`doesNotEvictBucketsThatStillCarryState`).
- **Floating point:** the first version returned a wait of 1.001 s instead of 1 s because `0.0833… / 0.0000833…` came out as 1000.0000001 and was rounded up. A test caught it; the fix subtracts a tiny epsilon before rounding.

## STEP 6: Contracts

| Method | Path | Notes |
|---|---|---|
| POST | `/api/auth/login` | `{email, password}` → `{accessToken, tokenType, expiresAt, user}` + refresh cookie |
| POST | `/api/auth/refresh` | cookie only → new access token + rotated cookie |
| POST | `/api/auth/logout` | revokes this session's token family, clears cookie, always 204 |
| GET | `/api/auth/me` | current user |
| PUT | `/api/auth/password` | `{currentPassword, newPassword}`; ends all sessions |
| GET/POST | `/api/admin/users` | list (paged) / create |
| GET | `/api/admin/users/{id}` | |
| PUT | `/api/admin/users/{id}/role` | `{role, driverId}`; ends the user's sessions |
| PUT | `/api/admin/users/{id}/enabled?enabled=` | disabling ends the user's sessions |

Errors keep the Phase 4 format. 401 responses also carry `WWW-Authenticate: Bearer` (or `Bearer error="invalid_token"` for a rejected token), as RFC 6750 asks.

## STEP 7: Tests and verification

**134 app tests** (78 new), all against PostgreSQL in Testcontainers, plus 71 algorithm tests:
- `AuthApiTest` (16): login, cookie flags, case-insensitive email, enumeration, hashing, rotation, reuse detection, other sessions unaffected, disabled user, role change picked up on refresh, logout, password change, rate limits.
- `AuthorizationTest` (39): 30-row role matrix with real tokens, error format, 401 challenge, public endpoints, and five forged-token cases. One positive control (a hand-made token signed with the real key is accepted) proves the negative cases fail for the right reason.
- `UserAdminApiTest` (8), `TokenBucketRateLimiterTest` (8), `ForwardedClientIpTest` (real Tomcat), header/CORS/OpenAPI tests, seeded logins, the endpoint-annotation build rule.

**Full stack check** (`docker compose up`, requests through the frontend's nginx at :3000):
```
anonymous GET /api/orders                       → 401 {"code":"UNAUTHORIZED", ...traceId}
viewer login                                    → 200, cookie HttpOnly, Secure, Path=/api/auth
viewer GET /api/orders                          → 200
viewer POST /api/warehouses                     → 403 {"code":"FORBIDDEN"}
POST /api/auth/refresh with cookie              → 200
8 wrong logins, 8 different emails, same host   → 401 ×8
6 wrong logins, same email                      → 401 ×5, then 429
dispatcher login right after                    → 200
```
Swagger UI was loaded in headless Chromium to check that the Content-Security-Policy doesn't break it: all five API groups render, the Authorize button is there, no console errors.

## STEP 8: Review notes (bugs found by running it)
- **Every user shared one IP.** The first full-stack run locked out *all* logins after five failures, because behind nginx the backend saw every request coming from the nginx container. Fix: nginx overwrites `X-Forwarded-For` with the real client address, and Spring uses it only when the direct peer is on a private network (`server.forward-headers-strategy: native`). The per-IP limit was also raised to 20/min so an office behind one NAT address isn't blocked by a few typos. `ForwardedClientIpTest` runs a real Tomcat (MockMvc skips the valve) to cover it.
- **`@PreAuthorize` failures would have been 500s.** They are thrown inside the controller call, so the catch-all `Exception` handler would have turned them into "unexpected error". Added explicit handlers for `AccessDeniedException` (403) and `AuthenticationException` (401).
- **Reuse detection must commit while failing.** `rotate()` revokes the token family and then throws 401; a normal rollback would undo the revocation. It uses `@Transactional(noRollbackFor = ApiException.class)`, and the test checks no live tokens remain afterwards.

## Interview questions
1. Why is the access token short-lived while the refresh token lasts days? What would you lose by making the access token last a week?
2. Why store a SHA-256 hash of the refresh token but a BCrypt hash of the password?
3. Explain refresh-token rotation with reuse detection. What does the server conclude when an already-rotated token comes back?
4. Why put the refresh token in an HttpOnly SameSite=Strict cookie but return the access token in JSON? Why is CSRF protection then unnecessary?
5. How does the server know the caller's role? What happens if someone edits the role inside their JWT?
6. What is the `alg: none` attack, and what line of configuration prevents it here?
7. Why does login run BCrypt even when the email doesn't exist?
8. Token bucket vs fixed window vs sliding log: trade-offs in accuracy and memory.
9. Why did the rate limiter lock everyone out behind nginx, and why is reading `X-Forwarded-For` directly in the controller dangerous?
10. A dispatcher is fired. Walk through what happens to their sessions when an admin disables the account, and for how long they can still act.
11. Why does BCrypt need a 72-byte limit check?
12. Why check authorization both by URL pattern and with `@PreAuthorize`?
