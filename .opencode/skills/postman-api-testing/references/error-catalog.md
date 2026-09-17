# Error Catalog

Source of truth for every `errorCode` this API can return. Generated directly from `GlobalExceptionHandler.java`, `RestAuthenticationEntryPoint.java`, and `RestAccessDeniedHandler.java` — not from `docs/api/*.md`, which can drift. When they disagree, the code wins; file a doc-fix, don't test against the doc.

Every error response is the same 6-field envelope regardless of which path produced it:

```json
{
  "success": false,
  "errorCode": "RESOURCE_NOT_FOUND",
  "message": "Pet not found: 3f2c...",
  "statusCode": 404,
  "timestamp": "2026-09-15T10:00:00Z",
  "traceId": "a1b2c3d4"
}
```

## From `GlobalExceptionHandler` (thrown inside Controller/Service, past the security filter chain)

| errorCode | HTTP status | Thrown when |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | Login email doesn't exist, or password doesn't match |
| `ACCOUNT_LOCKED` | **423** | Account is `LOCKED` (5 failed logins, or admin lock) |
| `ACCOUNT_NOT_ACTIVE` | 403 | Account exists but isn't `ACTIVE` (e.g. still `PENDING_VERIFICATION`) |
| `INVALID_REFRESH_TOKEN` | 401 | Refresh token fails validation, wrong type, or reuse-after-rotation |
| `BUSINESS_RULE_VIOLATION` | 400 | Any `RULE-XX-YY` guard fails (wrong OTP, duplicate email, password too short, ...) |
| `INVALID_STATE_TRANSITION` | 409 | An FSM transition isn't allowed from the current state |
| `RESOURCE_NOT_FOUND` | 404 | Entity lookup by id/email fails |
| `ACCESS_DENIED_SCOPE_MISMATCH` | 403 | Caller's tenancy scope doesn't cover the target resource (RULE-02-01) |
| `CONCURRENCY_CONFLICT` | 409 | Optimistic lock failure on save (`ObjectOptimisticLockingFailureException`) |
| `UNAUTHORIZED` | 401 | `AuthenticationException` thrown inside app code (rare — most 401s come from the filter chain, see below) |
| `ACCESS_DENIED` | 403 | `@PreAuthorize` denies inside a running controller method |
| `VALIDATION_FAILED` | 400 | Bean Validation (`@Valid`) fails on a request body |
| `UNAUTHORIZED_DELEGATED_ACTION` | 403 | Caregiver delegation (M04): caller is neither the pet's Primary Owner nor an `ACTIVE` caregiver with an unexpired delegation (RULE-04-04 / RULE-04-09), or someone other than the invited caregiver tried to accept/reject an invitation |
| `CAREGIVER_INVITATION_CONFLICT` | **409** | Caregiver delegation (M04): an `INVITED` or `ACTIVE` delegation already exists for this `(pet, caregiverEmail)` pair — enforced by the partial unique index `uq_pcd_outstanding`, not by a service-level pre-check |
| `INTERNAL_SERVER_ERROR` | 500 | Anything uncaught. **Never an expected test outcome** — always a bug if seen |

The two M04 codes above are subclasses registered ahead of their parent handler:
`UnauthorizedDelegatedActionException extends AccessDeniedScopeException` (same 403, different
`errorCode` — the contract pins this code to RULE-04-09) and
`CaregiverInvitationConflictException extends BusinessRuleViolationException` (409 rather than the
parent's 400, because a duplicate outstanding invitation is a conflict). Asserting the parent's
`ACCESS_DENIED_SCOPE_MISMATCH` / `BUSINESS_RULE_VIOLATION` on a caregiver endpoint will fail.

`INVALID_STATE_TRANSITION` and `CONCURRENCY_CONFLICT` share HTTP 409 but are different bugs — always assert `errorCode`, never status alone, on any 409 case.

## From the security filter chain (before `DispatcherServlet` — bypasses `GlobalExceptionHandler`, writes JSON directly)

`RestAuthenticationEntryPoint` — request lacks a valid access token on an authenticated endpoint:

| errorCode | HTTP status | Meaning |
|---|---|---|
| `TOKEN_EXPIRED` | 401 | Access token's `exp` has passed |
| `TOKEN_REVOKED` | 401 | Token is blacklisted (logout, admin action) |
| `INVALID_TOKEN_TYPE` | 401 | A refresh token was sent as if it were an access token |
| `INVALID_TOKEN` | 401 | Token is malformed or fails signature check |
| `UNAUTHORIZED` | 401 | No token / no recognizable reason (default case) |

`RestAccessDeniedHandler` — request has a valid token but `authorizeHttpRequests` URL-pattern rules deny it:

| errorCode | HTTP status | Meaning |
|---|---|---|
| `ACCESS_DENIED` | 403 | Authenticated, but the URL pattern itself is off-limits for this principal |

Note: this is a **different code path** from the `ACCESS_DENIED` thrown by `@PreAuthorize` inside `GlobalExceptionHandler` above — same `errorCode` string, different layer, both intentional per the codebase's own comments. A negative test doesn't need to distinguish them; both are legitimately `403 ACCESS_DENIED`.

## Rules for every negative test in this project

1. **422 never appears anywhere in this table — because it's banned.** If you're about to write `pm.response.to.have.status(422)`, stop; the real code is `400 VALIDATION_FAILED` or `400 BUSINESS_RULE_VIOLATION`.
2. **423, not 401/403, for a locked account.** Easy to get wrong by habit — most REST APIs don't use 423.
3. **5xx is never a target status.** `INTERNAL_SERVER_ERROR` exists in this table only so a collection-wide "no response is ever ≥ 500" test has something to name; don't write a test that expects it.
4. **`traceId` is opaque** — assert it's present and non-empty, never assert its value.
5. When two errorCodes share an HTTP status (`INVALID_STATE_TRANSITION` / `CONCURRENCY_CONFLICT` / `CAREGIVER_INVITATION_CONFLICT` at 409; `ACCESS_DENIED` / `ACCESS_DENIED_SCOPE_MISMATCH` / `UNAUTHORIZED_DELEGATED_ACTION` at 403; every 401 code above), the status alone is not a valid test — pair it with `errorCode`.

## Known API-wide quirk that affects assertions on timestamps

Date-time fields serialized from `LocalDateTime` (`expiresAt`, `validUntil`, `createdAt`, ...) carry
**no timezone offset** — e.g. `"2026-09-24T08:04:50.3000598"`. A client parsing that string as local
time (which `new Date(...)` does in JS) misreads it by its own UTC offset: on a UTC+7 machine a
7-day expiry measures as 6.71 days. Don't "tighten" a loose tolerance on a duration assertion
without fixing the serialization first — a tight window just encodes a guess about the server's
timezone. Note `ErrorResponse.timestamp` is an `Instant` and *does* serialize with `Z`; the
inconsistency is between the two types, not random.
