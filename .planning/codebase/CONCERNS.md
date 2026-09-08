# Codebase Concerns

**Analysis Date:** 2026-09-08

## Tech Debt

**Frontend is fully disconnected from the backend API:**
- Issue: Every page component under `FE/src/pages/` (HomePage, ShopPage, PetsPage, BookingPage, CheckoutPage, AccountPage, HotelPage, etc.) renders from static local mock data or local `useState`, with zero imports of `apiClient`, `axios`, or `fetch`. `grep` for `apiClient`/`fetch(` across `FE/src/pages` returns no matches.
- Files: `FE/src/pages/customer/HomePage.tsx`, `FE/src/pages/customer/home.mock.ts`, `FE/src/pages/customer/news.mock.ts`, `FE/src/pages/customer/PetsPage.tsx`, `FE/src/pages/customer/ShopPage.tsx`, `FE/src/pages/customer/BookingPage.tsx`, `FE/src/pages/customer/CheckoutPage.tsx`, `FE/src/pages/customer/AccountPage.tsx`, `FE/src/pages/hotel/HotelPage.tsx`
- Impact: The UI looks feature-complete but has no real data path to the backend. Any phase that "adds a feature" to these pages must also wire up real API calls — this is undone groundwork, not a missing feature.
- Fix approach: Replace mock-data reads with calls through one of the API client modules (see duplicate-clients issue below) and add loading/error states.

**Two duplicate, inconsistent API client implementations coexist:**
- Issue: `FE/src/shared/api/client.ts` (319 lines, fetch-based, class `ApiClient`) and `FE/src/shared/api/axios.ts` (61 lines, axios-based) both implement auth-token storage, refresh-token retry, and request wrapping — independently. They use different localStorage key names (`accessToken`/`refreshToken` in `client.ts` vs `access_token`/`refresh_token` in `axios.ts`), so tokens set by one path are invisible to the other.
- Files: `FE/src/shared/api/client.ts`, `FE/src/shared/api/axios.ts`, `FE/src/shared/api/product.api.ts`, `FE/src/shared/api/review.api.ts`, `FE/src/shared/stores/auth.store.ts`
- Impact: If a future feature mixes usage of both clients (e.g. login via `auth.store.ts`/`axios.ts` then a product call via `client.ts`), auth will silently fail because the token was stored under the other key.
- Fix approach: Pick one HTTP client strategy (axios or fetch) and delete the other; standardize on a single token storage key.

**API client modules and auth store are unused/orphaned:**
- Issue: `product.api.ts`, `review.api.ts`, and `auth.store.ts` are not imported by any page component (`grep -rln "product.api\|review.api\|auth.store" FE/src/pages` returns nothing).
- Files: `FE/src/shared/api/product.api.ts`, `FE/src/shared/api/review.api.ts`, `FE/src/shared/stores/auth.store.ts`
- Impact: Dead code that will bit-rot and drift from the real backend contract (`BE` only exposes a Pets module — see Missing Critical Features) unless actively maintained.
- Fix approach: Either wire these into pages as part of the next integration phase, or remove until the corresponding backend endpoints exist.

**Backend implements only the Pets module; everything else is scaffolding:**
- Issue: `BE/src/main/java/com/petcare/modules/` contains `pets` (full controller/service/repository/entity/DTOs), `auth` (entity only, no controller/service), and `users` (entity only). There is no `AuthController`, `OrderController`, `ProductController`, `BookingController`, `PaymentController`, etc., despite `docs/` and `plans/` describing a full pet-care platform (bookings, hotel, shop, payments).
- Files: `BE/src/main/java/com/petcare/modules/pets/*`, `BE/src/main/java/com/petcare/modules/auth/entity/Account.java`, `BE/src/main/java/com/petcare/modules/users/entity/User.java`
- Impact: `SecurityConfig` permits `/api/auth/**` unauthenticated (`BE/src/main/java/com/petcare/common/config/SecurityConfig.java:32`) but no controller exists to serve that path — login/register cannot work yet, which is consistent with the frontend being disconnected.
- Fix approach: Track module coverage against `docs/05-domain-model.md` / `plans/Master-Plan.md` and prioritize `auth` (login/register/refresh) since the FE already assumes a token-based auth flow.

## Known Bugs

**No CORS configuration in the backend:**
- Symptoms: Once the frontend does start calling the API cross-origin (FE on Vite dev server port, BE on `:8080`), all browser requests will be blocked by CORS policy since no `CorsConfigurationSource`/`@CrossOrigin` is registered anywhere.
- Files: `BE/src/main/java/com/petcare/common/config/SecurityConfig.java` (no `.cors(...)` call), confirmed via repo-wide search for `CorsConfiguration`/`@CrossOrigin`/`allowedOrigins` returning zero results.
- Trigger: Any FE fetch/axios call to `http://localhost:8080/api/**` from the Vite dev origin.
- Workaround: None currently in code.

**Inconsistent localStorage token keys between the two API clients (see Tech Debt above) will cause silent auth failures once both code paths are exercised in the same session.**

## Security Considerations

**Global exception handler leaks internal error details to clients:**
- Risk: `handleGenericException` returns `"Internal server error: " + ex.getMessage()` directly in the HTTP response body for any unhandled exception, which can leak stack-trace-adjacent details (SQL fragments, class names, null-pointer targets) to any API caller.
- Files: `BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java`
- Current mitigation: None — the message is passed through unfiltered.
- Recommendations: Log `ex` server-side with full detail, return a generic message (e.g. `"Internal server error"`) plus a correlation/trace ID to the client.

**Hardcoded default JWT secret and DB credentials in a committed config file:**
- Risk: `application.yml` embeds `password: 123456` for the Postgres datasource and a fallback JWT signing secret (`petcare-256-bit-secret-key-for-jwt-signing-min-32-chars`) used whenever the `JWT_SECRET` env var is absent.
- Files: `BE/src/main/resources/application.yml`
- Current mitigation: The JWT secret is overridable via `${JWT_SECRET:...}`, but the fallback is committed to source control and readable by anyone with repo access; the DB password has no override at all.
- Recommendations: Move both values to environment variables / secrets manager with no committed default for production profiles; keep the current defaults only under a `local`/`dev` profile that is clearly not used in `application-docker.yml` or production.

**Access/refresh tokens stored in `localStorage`:**
- Risk: Tokens in `localStorage` are readable by any JavaScript executing on the page, making them vulnerable to exfiltration via XSS (no `httpOnly` cookie protection).
- Files: `FE/src/shared/api/client.ts`, `FE/src/shared/api/axios.ts`
- Current mitigation: None observed (no CSP references found in `FE/index.html`).
- Recommendations: Consider httpOnly, secure, sameSite cookies for refresh tokens once the auth backend exists, or at minimum add a Content-Security-Policy to reduce XSS surface.

## Performance Bottlenecks

Not applicable yet — no real request path exists between FE and BE to profile (see Tech Debt: frontend disconnected). Revisit once integration work lands.

## Fragile Areas

**`PetServiceImpl` is the only substantially implemented service and carries the sole business logic surface:**
- Files: `BE/src/main/java/com/petcare/modules/pets/service/impl/PetServiceImpl.java` (166 lines), `BE/src/main/java/com/petcare/modules/pets/controller/PetController.java` (108 lines)
- Why fragile: It is the only end-to-end vertical slice (entity → repository → service → controller → DTO) in the codebase; any schema or contract change here has no sibling module to cross-check patterns against, so conventions established here will be copy-pasted everywhere else.
- Safe modification: Treat `pets` module structure as the de-facto template for new modules; verify DTO validation annotations and `GlobalExceptionHandler` mapping stay consistent when adding new modules.
- Test coverage: No test files exist for `pets` (see Test Coverage Gaps).

## Scaling Limits

Not assessed — the system has a single implemented module and no production traffic data available.

## Dependencies at Risk

Not detected — dependency manifests (`BE/pom.xml`, `FE/package.json`) were not found to reference any deprecated or end-of-life packages during this pass; revisit once module count grows.

## Missing Critical Features

**No authentication endpoints despite auth-aware infrastructure:**
- Problem: `JwtTokenProvider`, `JwtAuthenticationFilter`, `UserPrincipal`, and `SecurityConfig` (`BE/src/main/java/com/petcare/common/security/`) are all present and permit `/api/auth/**`, but there is no controller/service to issue tokens (register, login, refresh, logout).
- Blocks: The entire frontend auth flow (`FE/src/pages/auth/LoginPage.tsx`, `RegisterPage.tsx`, `FE/src/shared/stores/auth.store.ts`) and every other module that depends on an authenticated `UserPrincipal`.

**No `users` module service/controller layer:**
- Problem: `BE/src/main/java/com/petcare/modules/users/entity/User.java` exists but has no repository, service, or controller.
- Blocks: Any feature requiring user profile management, and blocks completing the `auth` module cleanly since `Account`/`User` relationship logic has nowhere to live yet.

## Test Coverage Gaps

**Backend has effectively zero test coverage:**
- What's not tested: `PetServiceImpl`, `PetController`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `GlobalExceptionHandler` — none have corresponding test classes. The only test in the repo is a context-load smoke test.
- Files: `BE/src/test/java/com/petcare/PetcareApplicationTests.java` (single `@Test contextLoads()` with no assertions beyond context startup), `BE/src/test/resources/application-test.yml`
- Risk: Any regression in the one implemented business module (`pets`) or in JWT parsing/validation would go undetected until manual QA or production.
- Priority: High — `pets` module and JWT security logic should get unit/integration tests before further modules are layered on top of the same patterns.

**Frontend has no test files at all:**
- What's not tested: No `*.test.*` or `*.spec.*` files were found anywhere under `FE/src`.
- Files: N/A (absence confirmed via repo-wide glob for test file naming patterns)
- Risk: Given the scale of the mock-data pages (`HomePage.tsx` at 581 lines, `AccountPage.tsx` at 449 lines), any refactor toward real API integration has no safety net to catch UI regressions.
- Priority: Medium — prioritize once pages start being wired to real API calls, since that's when regressions become likely.

---

*Concerns audit: 2026-09-08*
