---
name: postman-api-testing
description: Build, maintain, and run Postman collections and tests for the Pet Care backend API. Use when creating or updating Postman requests, writing pre-request or test scripts, designing environments or variables, deciding what to assert on a response, adding negative/error test cases, wiring API tests into CI, or reviewing an existing Postman collection for this project. Encodes this project's real response envelope, error catalog, auth flow, and canonical-collection workflow — do not apply generic Postman advice without checking this skill first.
---

# Postman API Testing

Postman requests in this repo are a contract test against a specific backend, not a generic HTTP client script. Every request, script, and assertion must match what `com.petcare` actually returns — not what a typical REST API returns, and not what `docs/api/openapi/*.yaml` says it will return once every module ships.

Postman does not replace unit and integration tests in the source tree. It verifies the deployed contract: request shape, response envelope, status codes, auth flow, and business-rule errors, end to end, across environments.

## Core Principle

Two facts override every generic Postman convention in this repo:

1. **The success envelope wraps the payload.** Every 2xx response is `{ "data": ..., "message": ..., "code": ... }`. The thing you actually want is always one level deeper than a generic tutorial expects: `pm.response.json().data`, never `pm.response.json()` directly.
2. **The error envelope is a different shape entirely**, not a variant of the success envelope. It carries no `data` field at all. Treat "assert success" and "assert error" as two different helper functions from the start — see `references/pm-scripts.md`.

Skipping either fact produces scripts that look correct, run against nothing, and pass by accident (`undefined === undefined`).

## Phase 0 — Bind to This Project

Before writing any request, confirm these against the running backend, not against `docs/`:

- **Base path**: controllers are mounted at `/api/...` with no `server.servlet.context-path` (`BE/src/main/resources/application.yml`). Local default port is `8080`.
- **Two OpenAPI sources exist and disagree** — know which one you're using:
  - `docs/api/openapi/*.yaml` — 25 hand-written contract files, the **target design**, one per business module. Paths in these files omit the `/api` prefix and have no `servers:` block. Importing them into Postman as-is produces a collection where every request 404s.
  - `/v3/api-docs` (springdoc, live at runtime) — reflects **only the controllers that exist today**. This is the source to generate a runnable collection from.
  - Use the design-time yaml files to see where the API is *headed* (backlog, module coverage) and the runtime spec to build requests that *work now*. Never treat one as a drop-in replacement for the other.
- **What actually exists right now**: two controllers. `POST/GET/PATCH /api/pets*` and `POST /api/auth/*`. Every other module in the 25-file contract set (`orders`, `invoices`, `appointments`, ...) has no controller yet — don't build placeholder requests for endpoints that return 404 by design; track them as backlog instead (`references/collection-structure.md`).
- **Success envelope**: `ApiResponse<T>{ data: T, message: string, code: number }` (`platform/model/ApiResponse.java`).
- **Error envelope**: `ErrorResponse{ success: false, errorCode: string, message: string, statusCode: number, timestamp: string, traceId: string }` (`platform/model/ErrorResponse.java`). All 6 fields are always present.
- **List envelope**: paginated results nest inside `data` as `PageResponse<T>{ content, page, size, totalElements, totalPages, first, last }` (`platform/model/PageResponse.java`), driven by Spring `Pageable` query params (`page`, `size`, `sort`).

## Phase 1 — Workspace and Canonical Source

Canonical source of truth for the team collection is the **Postman cloud workspace `pet-care-team`**, not a file in the repo and not anyone's personal workspace.

- **Workflow**: fork → edit → pull request → merge, on the canonical collection. Nobody edits the canonical collection directly. This is what makes "one collection" (see Anti-Patterns) actually enforceable instead of aspirational.
- **Export a snapshot into `postman/` at each release tag** so the collection has a diffable, restorable backup outside Postman's own history. This is a periodic export, not the live editing surface.
- **Free-tier constraints that shape every other decision here**: the team plan caps collaborators at 3 (a 3-person backend team has no spare seats — check before inviting anyone else) and has **no private workspace tier** (`personal` / `team` / `public` only). Every environment in a `team`-visibility workspace is visible to every collaborator — this is why secrets live in **Postman Vault** (local to each person's client, never synced) and never in a shared environment. Confirm the workspace's `visibility` is `team`, not `personal` — a `personal`-visibility workspace under `type: team` silently blocks every other collaborator from seeing it.

## Phase 2 — Collection Structure

Organize folders by business module, matching `docs/INDEX.md`'s module numbering — see `references/collection-structure.md` for the full skeleton and the current real-vs-placeholder split.

Name requests by action, then disambiguate cases with a suffix — never leave multiple requests with the identical name:

```
Create Pet
Create Pet — 400 VALIDATION_FAILED
Create Pet — 401 No Token
Login
Login — 401 INVALID_CREDENTIALS
Login — 423 ACCOUNT_LOCKED
```

A bare action name (`Create Pet`) is always the happy path. Every case suffix names the HTTP status and the `errorCode` it expects — not a vague label like "invalid input".

## Environments and Variable Scope

Three scopes, three different lifetimes — do not collapse them:

| Scope | Holds | Example |
|---|---|---|
| Environment | Static config, one value per deploy target | `base_url`, `env_type` |
| Collection variables | Data generated during a run | `access_token`, `refresh_token`, `pet_id` |
| Postman Vault (local, per-person) | Secrets | admin credentials, mail app password |

`pm.environment.set(...)` for a token is wrong twice over: it writes into a config object that's supposed to be static per deploy target, and — because environments in a `team`-visibility workspace are shared — it means one person's login overwrites the token every other collaborator's requests are using. Use `pm.collectionVariables.set(...)` for anything produced by running the collection.

Environments to define: `Local` (`base_url = http://localhost:8080`), `Development`, `Staging`, `Production`. Every environment carries `env_type` (`local`/`development`/`staging`/`production`) — this is the field the production-safety guard checks (see below). No request body or URL should ever change between environments; only variable values change.

## Authentication

Configure auth once, at the collection level: Bearer token, `{{access_token}}`. Folders/requests inherit it (`Inherit auth from parent`). Override to `No Auth` only for the handful of endpoints that are genuinely public: `register`, `verify-otp`, `otp/resend`, `login`, `refresh`.

**Access tokens expire in 15 minutes** (`jwt.access-token-ttl-min: 15`); refresh tokens last 30 days. A flat "login once, save the token" flow (the naive version of automated token capture) works for a quick manual click-through and fails on any run that takes longer than 15 minutes or on any folder run in isolation after the token has aged out.

Put a collection-level **pre-request script** that:
1. Decodes the JWT in `{{access_token}}` (base64-decode the payload, no library needed) and checks `exp`.
2. If absent or expiring within ~120 seconds, calls `/api/auth/refresh` with `{{refresh_token}}`; if that also fails, falls back to `/api/auth/login`.
3. Writes the result into collection variables via `pm.collectionVariables.set`.

See `references/pm-scripts.md` for the full script. Treat this bootstrap as a defensive nicety for a single request send, **not** a substitute for correct collection ordering — nested `pm.sendRequest` calls inside a pre-request script are not reliably awaited before the main request fires on every Postman client (confirmed in practice: a request was sent with a stale token before the fallback login's callback had run). The structural fix, not a scripting one: **anything that kills the current token (`Logout`) must run after everything that needs one** — see `references/collection-structure.md`'s `_Cleanup` folder. Don't rely on the bootstrap to recover from a mid-run logout.

## Test Data Isolation

Every write endpoint here enforces a uniqueness or ownership constraint at the database level (e.g. `RULE-01-10` — duplicate email on register throws `BusinessRuleViolationException` from a `DataIntegrityViolationException` race guard). A collection that reuses the same literal email or pet name across runs is not idempotent — it is guaranteed to fail on the second run.

Rules:
- Generate unique data per run: `{{$randomEmail}}`, `{{$guid}}`, or a timestamp-suffixed value — never a hardcoded literal for anything with a uniqueness constraint.
- Every folder must be runnable **on its own**, not only as part of a full top-to-bottom collection run. A folder that assumes a prior folder already created `pet_id` breaks the moment someone runs it alone, or two people run the suite against the same environment concurrently.
- Add a cleanup step (or a dedicated cleanup folder run last) for anything the run creates, where the API supports deletion. Pet Care's `PetController` currently exposes no `DELETE` — track that as backlog, don't invent a workaround endpoint.

## Assertions

Two response shapes need two different assertion helpers — see `references/pm-scripts.md` for both.

**Define these inline in every single request's own Tests script — do not rely on a collection-level Tests script to share them.** A collection-level Tests script that defines `assertSuccess`/`assertError` is not guaranteed to share that scope with a request's own Tests script across Postman clients/runtimes; confirmed in practice (`ReferenceError: assertSuccess is not defined` when sent from a request's own Tests tab, even though the same functions were defined one level up at the collection). Duplicating ~15 lines into all 21 requests is more code but it is the version that actually runs everywhere, including Newman/CI. Keep only self-contained, request-independent checks (like the "never a 5xx" guard below) at the collection level.

```javascript
// Success — value is under .data
function assertSuccess(expectedStatus) {
    pm.test(`Status is ${expectedStatus}`, () => pm.response.to.have.status(expectedStatus));
    const body = pm.response.json();
    pm.test("Envelope has data", () => pm.expect(body).to.have.property("data"));
    return body.data;
}

// Error — no .data, 6 fields always present, errorCode is the real assertion
function assertError(expectedStatus, expectedErrorCode) {
    pm.test(`Status is ${expectedStatus}`, () => pm.response.to.have.status(expectedStatus));
    const body = pm.response.json();
    pm.test("Error envelope shape", () => {
        pm.expect(body).to.include.keys("success", "errorCode", "message", "statusCode", "timestamp", "traceId");
        pm.expect(body.success).to.equal(false);
    });
    pm.test(`errorCode is ${expectedErrorCode}`, () => pm.expect(body.errorCode).to.equal(expectedErrorCode));
}
```

Never assert only the HTTP status on an error case. `409` alone doesn't tell you whether you hit `INVALID_STATE_TRANSITION` or `CONCURRENCY_CONFLICT` — two unrelated bugs that happen to share a status code. The `errorCode` is the real assertion; the status is a sanity check on top of it.

## Positive and Negative Cases

Use `references/error-catalog.md` as the single source for every `errorCode` → HTTP status pair — it's generated from `GlobalExceptionHandler`, `RestAuthenticationEntryPoint`, and `RestAccessDeniedHandler` directly, not guessed from REST convention. Two rules this project enforces that differ from generic REST advice:

- **422 is banned.** Every semantic validation failure in this codebase is `400 VALIDATION_FAILED` or `400 BUSINESS_RULE_VIOLATION`. Don't write a test that expects 422 anywhere.
- **423, not 401 or 403, for a locked account.** `AccountLockedException` → `ACCOUNT_LOCKED` → `423 Locked`. This is easy to get wrong because 423 is rare in generic API tutorials.
- **No test may treat a 5xx as an expected outcome.** A 5xx is a server bug by definition in this codebase (`GlobalExceptionHandler`'s catch-all maps any uncaught `Exception` to `500 INTERNAL_SERVER_ERROR`) — there is no scenario where 500 is the "correct" response to assert. Add a collection-level test that fails on any response `>= 500`, regardless of which request produced it.

For every write endpoint, cover: valid request, missing required field, invalid format, missing/invalid token, insufficient permission, resource not found, and the specific business-rule violations that endpoint can trigger (pull the `RULE-ID` list for that module from `docs/02-business-rules.md`).

## Production Safety

Don't rely on discipline alone — a wrong environment selection in a dropdown is a one-second mistake with an unrecoverable outcome (real data deleted). Enforce it in script:

```javascript
const destructive = /delete|reset|bulk|force/i;
if (pm.environment.get("env_type") === "production" && destructive.test(pm.info.requestName)) {
    throw new Error(`Blocked: "${pm.info.requestName}" looks destructive and env_type is production.`);
}
```

Put this in the collection-level pre-request script, after the auth bootstrap. Every environment must declare `env_type` for this guard to work — an environment missing it should be treated as unsafe, not as "probably fine."

Production runs should default to read-only smoke tests (`GET /actuator/health`, `GET /actuator/info`, a handful of safe `GET` requests) unless a write test has a dedicated test account, documented cleanup, and team sign-off.

## Secrets

Never store in a shared environment: production tokens, the Gmail app password (`MAIL_PASSWORD`), the JWT signing secret, any real customer credential. Postman Vault is local per person and never syncs — that's where these belong. A shared `team`-visibility environment is not a safe place for any of them, regardless of who's on the team.

## CI/CD Direction

`.github/workflows/ci.yml` already has an `integration-tests` job with a Postgres service — that is the insertion point for automated Postman runs, using `newman` (already available; run via `npx newman` if not globally installed on the runner). This project has not wired that step yet. When it is:

- Run after the app is confirmed up (health check), not in parallel with startup.
- Inject environment values via `--env-var`, sourced from GitHub Secrets — never commit an environment file with real values.
- Export a JUnit reporter (`--reporters cli,junit`) so failures surface in the CI UI, not just in raw newman output.
- Treat a `newman` exit code of non-zero as a merge blocker, the same as a failing unit test.

## Backend Requirements for Testability

See `references/test-auth-requirement.md` in full before assuming the auth flow can be automated end to end today.

Short version: `register → verify-otp → login` cannot currently be automated, because the only `EmailGateway` implementation sends real email over Gmail SMTP with no dev/test alternative and no way to read the OTP back programmatically. This is a backend gap, not a Postman scripting problem — do not try to work around it by scraping a mailbox or hardcoding a real inbox in the collection. The reference file lays out the proposed backend fix (a test-only, profile-gated OTP source) and why the alternatives (a dev-only OTP-read endpoint, log-scraping, DB-seeding around the flow entirely) are each weaker.

## Anti-Patterns

### Bare Response Assertion

Reading `pm.response.json().accessToken` instead of `.data.accessToken`. Fails silently (`undefined`), not loudly — the bug surfaces two requests later as an unrelated 401.

### One Assertion Helper for Both Envelopes

Success and error responses do not share a shape. A helper written against one and reused for the other either throws inside the test script (masking the real failure in a wall of JS errors) or passes when it shouldn't.

### Status-Only Error Assertions

`pm.response.to.have.status(409)` alone doesn't distinguish `INVALID_STATE_TRANSITION` from `CONCURRENCY_CONFLICT`. Always pair status with `errorCode`.

### Shared-State Flow Chains

A folder that only works if a specific earlier folder already ran and left `pet_id` behind. Breaks under concurrent runs and isolated folder runs alike.

### Environment as Token Storage

`pm.environment.set("access_token", ...)` in a `team`-visibility workspace overwrites the token every collaborator's requests rely on. Tokens are run-scoped; use collection variables.

### Expecting 5xx

Any test that treats a 500 as a valid outcome for some input. There is no such input in this codebase — a 500 is always a bug.

### Copy-Pasted Design-Time Paths

Building requests straight from `docs/api/openapi/*.yaml` paths without adding the `/api` prefix or checking whether the controller exists yet. Produces a collection that's mostly 404s.

### Duplicate Request Names

Nine requests all named "Create Order" because positive and negative cases weren't given distinct names. Makes a Newman failure report and a collection diff both unreadable.

### Canonical Collection With No Enforcement Mechanism

Declaring "we have one shared collection" without a fork → PR → merge workflow behind it. Without the mechanism, everyone keeps a personal copy and you're back to `API-final-v2`.

## Verification Checklist

### Envelope correctness
- [ ] Every success assertion reads through `.data`
- [ ] Every error assertion checks `errorCode`, not just HTTP status
- [ ] No request asserts a field removed from the current DTO (check `references/error-catalog.md` and the actual DTO, not the design-time yaml)

### Status codes
- [ ] No test expects `422`
- [ ] Locked-account case expects `423`, not `401`/`403`
- [ ] No test treats `5xx` as expected
- [ ] A collection-level test fails any response `>= 500`

### Data and isolation
- [ ] Every field with a uniqueness constraint uses generated/unique data per run
- [ ] Every folder runs independently, without depending on another folder having run first
- [ ] Writes that create data have a corresponding cleanup step, where the API supports it

### Auth
- [ ] Auth is set at collection level, `Inherit auth from parent` on child requests
- [ ] Public endpoints (register/verify-otp/otp/resend/login/refresh) explicitly override to `No Auth`
- [ ] A pre-request script refreshes or re-logs-in when the access token is missing or near expiry

### Security
- [ ] No secret lives in a shared (`team`-visibility) environment
- [ ] A production-safety guard script exists and checks `env_type` before any destructive-looking request
- [ ] Workspace `visibility` is `team`, confirmed, not left at `personal`

### Process
- [ ] Canonical collection lives in the `pet-care-team` Postman workspace, edited via fork → PR → merge
- [ ] No two requests share a name without a case suffix distinguishing them
- [ ] New endpoints get a Postman request in the same PR that adds the controller, not as a follow-up

## Final Rule

A Postman test that passes because it never reached the real assertion is worse than no test — it reads as coverage in a report and hides the exact failure it exists to catch. Every assertion in this project must be checkable against a specific line in `com.petcare` source (`ApiResponse`, `ErrorResponse`, `GlobalExceptionHandler`, the DTO in question) before it's trusted, not against what a generic REST API, or an aspirational OpenAPI file, would return.
