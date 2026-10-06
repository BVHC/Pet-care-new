# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pet Care — multi-branch pet clinic management (exam/vaccination, grooming, boarding, counter sales, cashier, inventory, customer care). Monorepo: `BE/` (Java 21 + Spring Boot 3.5, Maven) and `FE/` (React 18 + Vite + TypeScript). PostgreSQL 17 + Redis 7 via `docker-compose.yml`. Documentation is Vietnamese, code and identifiers are English.

**State as of 2026-10-06 (commit `962fa6a`, branch `manh`):** the spec was redesigned (v16) and the backend was reset, then the shared `platform/` layer was rebuilt (see *What `platform/` already provides* below). Schema: `V1__init_schema.sql` (all 55 ERD tables) + `V2__seed_system_configs.sql` (52 [CFG] rows). `module/<m>/api/` holds the cross-module contracts of all 11 modules (interfaces/records/events only, `docs/06-module-contracts.md`); the only implemented module code is `module/identity` infrastructure — session authentication, principal, BR-TK-17 interceptor, `SystemConfigApi` (ADR-0003, ADR-0004), and the first scheduled job, `SessionCleanupJob` (ADR-0007, ADR-0008). There is still **no business controller**; login, OTP and staff management are not built. Team split: BE-1 = identity, appointment, visit, care; BE-2 = branch, customer, catalog, sales, inventory, boarding, content, report; dates in `docs/timeline_petcare_v15.xlsx`. `docs/architecture/system-overview.md` is the detailed, file-by-file snapshot of what exists — update it when adding a module or infrastructure.

## Commands

All Maven commands run from `BE/`; npm commands from `FE/`.

```bash
# Infra (from repo root) — Postgres + Redis + BE + FE
cp .env.example .env                     # once: compose has no JWT_SECRET default and refuses to run without it
docker compose up -d postgres redis      # just the dependencies for local dev

# Backend
mvn clean compile -DskipTests
mvn spring-boot:run                      # port 8081 by default (SERVER_PORT); Docker profile uses 8080
mvn test                                 # Surefire: *Test.java / *Tests.java
mvn verify                               # Failsafe: *IT.java (adds integration tests)
mvn test -Dtest=SomeServiceTest#someMethod                                         # one test
mvn verify -Dit.test=SomeFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false  # one IT only

# Frontend
npm ci && npm run dev                    # http://localhost:5173, proxies /api -> VITE_API_URL or :8080
npm run build                            # tsc && vite build
npm run lint                             # eslint, --max-warnings 0
npx tsc --noEmit                         # there is no `npm run typecheck` script
```

**Tests need Docker running.** Every `@SpringBootTest` that touches the DB uses the `test` profile and `@Import(TestcontainersConfiguration.class)` (`BE/src/test/java/com/petcare/`): a fresh `postgres:17` container per run via `@ServiceConnection`, Flyway applies the real migrations, `ddl-auto=validate`. No local `petcare_test` DB is needed. Surefire/Failsafe pin the JVM to `-Duser.timezone=Asia/Ho_Chi_Minh`. Existing tests: `PetcareApplicationTests` (context + Flyway), `SchemaMigrationIT` (`mvn verify`; checks the catalog against erd/03 — table set, timestamps, FK `RESTRICT`, identity, enum `VARCHAR(30)`, state sets, audit trigger, boarding EXCLUDE), `TraceIdFilterTest`, `GlobalExceptionHandlerTest`, `StateMachineBaseTest`, `SecurityConfigTest`, `AuditRecorderTest`, `AuditRecorderIT` (`audit_logs` can't be cleaned between tests — trigger — so ITs tag their rows with a random `reason`); auth: `JwtTokenServiceTest`, `JwtAuthenticationFilterTest`, `BranchScopeTest`, `AccountPrincipalTest`, `SessionAuthenticationServiceTest`, `SessionServiceTest`, `MustChangePasswordInterceptorTest`, `AuthenticationIT` (end-to-end over HTTP with a mutable `@Primary Clock` and test-only probe controllers; the template for testing future secured endpoints); [CFG]: `ConfigKeyTest`, `SystemConfigServiceTest`, `SystemConfigIT`; session cleanup job: `SessionCleanupJobTest`, `SessionCleanupPropertiesTest`, `SessionCleanupIT` (template for job ITs: exact counts on a pre-2010 `expires_at` era cleaned in `@BeforeEach`, failing-batch subclass, `SKIP LOCKED` with a second thread), `SessionCleanupScheduleIT` (cron set → `CronTask` registered; the `test` profile sets every job's cron to `"-"`); sync events: `DomainEventTransactionIT` (test-only publisher/listener/`MANDATORY` service: same thread and transaction, listener failure rolls back the publisher, publishing outside a transaction fails). To get a token in an IT, insert the account with `JdbcTemplate` and call `SessionService.open(...)` inside a `TransactionTemplate`.

## Documentation is the source of truth — read before coding

Start at `docs/INDEX.md`: it maps every module to the exact line ranges in each spec file, so read only the slice you need. Never invent a rule, transition, model, column or enum value that isn't in these files; when information is missing, record it as an assumption/TBD.

| Need | File | Priority on conflict |
|---|---|---|
| Business rules `BR-<MODULE>-<n>` (139 codes, 134 active) | `docs/02-business-rules.md` | 1 (highest) |
| State machines — 10 objects, numbered transition tables (`Visit#5`) | `docs/03-state-machines.md` | 2 |
| Domain model — 52 models, ROOT/PART/REF/LOG, 10 cross-cutting principles | `docs/04-domain-model.md` | 3 |
| ERD — 55 tables, types, keys, indexes, enum ASCII codes | `docs/05-erd.md` | 4 |
| Actors A01–A08, use cases UC01–UC89, system tasks ST01–ST21 | `docs/01-business-operations.md` | must match rules |
| Backend code conventions (9 files) | `docs/convention/backend/` | |

Module codes: TK, QT, CN, CK, KH, SP, NS, LH, TN, KB, LT, BH, TG, KO, BV, DG, TB, BC. Each has a **tier**: 1 full, 2 thin, 3 diagram-only (no rules/models — don't build). The tier-3 UC/ST list is in `docs/INDEX.md`.

`docs/architecture/system-overview.md` describes the actual codebase state (runtime, what exists, what is missing, config debt). `docs/06-module-contracts.md` is the BE↔BE contract (who calls which `module/<m>/api` interface, sync events, delivery dates). `docs/api/` holds the FE↔BE HTTP contracts v1 (12 modules, 230 endpoints): `.md`/`.yaml` are **generated** from `docs/api/generator/m_<module>.py` — edit the generator, run `python docs/api/generator/generate.py` then `node docs/api/check-contracts.mjs`; never hand-edit the output. `docs/diagrams/INDEX.md` is still empty. `docs/adr/INDEX.md` lists the technical decisions not covered by `docs/01`–`05` (ADR-0001 audit recording, 0002 client IP behind proxy, 0003 JWT + DB session auth / RBAC / branch scope, 0004 reading [CFG], 0005 public paths anonymous, 0006 customer owner scope, 0007 scheduled jobs, 0008 session cleanup); record every newly agreed architecture decision there as `docs/adr/000n-<slug>.md` (immutable once Accepted; supersede instead of editing).

### Backend conventions

`docs/convention/backend/` (index: `INDEX.md`) was rewritten for v16 on 2026-10-03 and matches `platform/`. Still open there, listed in its *Còn TBD* table — don't decide these silently:
- Command / FSM transition method names: no Actor↔Command table exists; propose English names (from the *Sự kiện* column of 03) in the module plan and ask (03-naming).
- How "cảnh báo, không chặn" rules return warnings to the client (06) and the locking strategy for quota / kennel capacity / stock (07 §7.3): both need an ADR first. (`docs/api` contracts currently assume a named field in `data`, e.g. `stockWarning` — `00-method.md` §3.8.)
- Package names follow `docs/06-module-contracts.md` §2 (`identity`, `branch`, `customer`, `catalog`, `appointment`, `visit`, `boarding`, `sales`, `inventory`, `content`, `care`, `report`). [CFG] reading is decided (ADR-0004).

## Backend architecture (target, per conventions)

### Package layout

```
com.petcare/
├── platform/          # cross-cutting infra; must NOT depend on any module/*
│                      # security (JWT/RBAC/branch scope), exception, audit, fsm, config, model (envelopes)
└── module/<feature>/  # api/ controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/]
```

One package per bounded context. **A module may import only another module's `api/` package** (interfaces, records, enums, events — `docs/06-module-contracts.md` §1); never its `entity`, `repository` or internal `service`. 1–1 consequences are interface calls inside the caller's transaction (implementation `@Transactional(MANDATORY)`); fan-out consequences are synchronous events (`ApplicationEventPublisher` + `@EventListener`, not `AFTER_COMMIT`; rules in convention 07 §7.2: listener in `module/<m>/listener/{Event}Listener` calls its own service, which is `@Transactional(MANDATORY)`; no `@Async`, `REQUIRES_NEW` or swallowed exceptions). Until the owner implements an interface, callers mock it in unit tests or the owner commits an `<Interface>Placeholder` that throws `UnsupportedOperationException` (never a "safe" default).

### What `platform/` already provides (reuse, don't re-create)

| Need | Use |
|---|---|
| Rule / state / not-found / scope / concurrency errors | `platform/exception`: `BusinessRuleViolationException(ruleId, msg)` (appends `" (BR-…)"` itself), `InvalidStateTransitionException`, `ResourceNotFoundException`, `AccessDeniedScopeException`, `ConcurrencyConflictException`, all extending `PlatformException` with an `ErrorCode` (12 codes, HTTP status built in) |
| Error envelope | `GlobalExceptionHandler` already maps every `PlatformException`, Bean Validation, malformed request, Spring Security 401/403 and DB lock/constraint errors (→ 409, generic message). Don't add handlers per module |
| Success envelope | `platform/model`: `ApiResponse.ok(data)`, `ok(data, msg)`, `created(data, msg)`; `PageResponse.of(Page)` (0-based `page`) |
| FSM | `platform/fsm`: `Transitionable<S>` + `StateMachineBase<S>` (`validateTransition`, `validateInitial`, `canTransition`). Tests extend `src/test/.../platform/fsm/FsmTransitionTestBase<S>` and declare an independent copy of the 03 table |
| Timestamps on entities | `TimestampedEntity` (ROOT/PART/REF: `created_at`, `updated_at`) or `CreatedAtEntity` (LOG). Neither declares `id` — each entity declares its own PK |
| Current time | Inject the `Clock` bean (`TimeConfig`, zone `TimeConfig.BUSINESS_ZONE` = Asia/Ho_Chi_Minh): `Instant.now(clock)` for instants, `LocalDate.now(clock)` for business dates. Never call `now()` without the clock; tests use `Clock.fixed` |
| Trace id | `TraceIdFilter` (first filter) sets MDC `traceId` and header `X-Trace-Id`; read it via `TraceContext.current()` |
| API docs | springdoc at `/v3/api-docs`, `/swagger-ui.html`; bearer scheme name `OpenApiConfig.BEARER_SCHEME` |
| Authentication (ADR-0003) | `SecurityConfig` + `JwtAuthenticationFilter`: JWT HS256 (`JWT_SECRET` ≥ 32 bytes) carrying only `sub`, `sid`, `jti`; every request re-checks the `sessions` row via `SessionAuthenticator` (identity) — revoked/expired/locked/disabled → 401 on the next request. Role, branch and `mustChangePassword` come from the DB each request, never from the token. Public: health/info, API docs, `/api/auth/register[/**]`, `/api/auth/login`, `/api/auth/password/**`, `/api/public/**`; everything else needs a token. Public paths are **always anonymous** — one `SecurityConfig.PUBLIC_REQUESTS` matcher drives both `permitAll` and the filter skip, so a token sent there is ignored (ADR-0005); audit on login must set the actor with `AuditEntry.actor(...)`. Open/revoke sessions with identity `SessionService` (`MANDATORY`). 401/403 from the filter chain go to `GlobalExceptionHandler` via `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` |
| Authorization | `@EnableMethodSecurity`: put `@PreAuthorize("hasRole('VET')")` on controller methods (authority `ROLE_<role>`). A wrong-role 403 is **not** audited by platform; BR-QT-01/07 hierarchy denials are audited by the QT service with `recordIndependently` |
| Current user / branch scope | `platform/security/BranchScope`: `current()` (a `SecurityPrincipal`: `accountId`, `email`, `sessionId`, `role`, `branchId`, `accessScope` = `CHAIN`/`BRANCH`/`OWNER`), `resolve(requestedBranchId)` for list queries (A05–A08 → own branch, other → 403; ADMIN/SUPER_MANAGER → as requested, `null` = whole chain), `check(resourceBranchId)` for a loaded record (A05–A08: other or `null` branch → 403). CUSTOMER (`OWNER`) always gets 403 from both — filter customer data by owner and branch on `accessScope()` in endpoints shared with staff (ADR-0006, convention 02). Call it only for data that has `branch_id` — Customer, Pet, catalogs, suppliers are chain-wide |
| [CFG] parameters (ADR-0004) | Inject `identity.api.SystemConfigApi`: `getInt/getDecimal/getBool/getTime(ConfigKey.X)`. Never hard-code a [CFG] number. Values must apply only to transactions created after a change (BR-QT-13) — snapshot them into the row (e.g. `sessions.expires_at`). New parameter = new `ConfigKey` constant + new seed migration (the app refuses to start if a key is missing, mistyped or out of min–max) |
| BR-TK-17 | `identity/controller/MustChangePasswordInterceptor` blocks `/api/**` with 400 `BR-TK-17` while `must_change_password` is set, except `GET /api/me`, `POST /api/me/password`, `POST /api/auth/logout` (public paths never carry a principal, ADR-0005) |
| Audit (BR-QT-15, 16) | `platform/audit/AuditRecorder` — call it explicitly from the service, no AOP: `record(AuditEntry.of("ACTION").entity("table", id).before(snap).after(snap).reason(r))` inside the use-case `@Transactional` (`MANDATORY`); `recordIndependently(...)` (`REQUIRES_NEW`) only for failure events that must survive rollback (`LOGIN_FAILED`, BR-QT-01 403). Snapshots are records/Maps, never entities; keys like password/otp/token/secret/cccd/pin are rejected. Actor comes from a principal implementing `AuditPrincipal` (TK's principal must). What to audit: convention 08 §8.3; design: ADR-0001 |
| Client IP | `server.forward-headers-strategy: native` → `request.getRemoteAddr()` is the client IP behind nginx (ADR-0002) |
| Scheduled jobs (ADR-0007) | `platform/config/SchedulingConfig` enables `@Scheduled` (default 1-thread scheduler, one instance assumed, jobs must be idempotent). A job is `module/<m>/job/{Name}Job.run()`: `@Scheduled(cron = "${app.jobs.<name>.cron}", zone = TimeConfig.BUSINESS_ZONE_ID)`, not `@Transactional` — the service commits per record/batch; catch and log `<NAME>_FAILED`, never rethrow; MDC `traceId` per run. Add `cron: "-"` for it in `application-test.yml` (no job runs in tests; ITs call it directly). Business numbers still come from [CFG]. Existing job: `identity/job/SessionCleanupJob` (ADR-0008: deletes `sessions` expired > 30 days, batches with `FOR UPDATE SKIP LOCKED`) |

Not built yet: every TK/QT endpoint (register/OTP, login/logout, passwords, staff management, lock/unlock, UC10 config editing — their obligations are listed in ADR-0003 *Hệ quả* and ADR-0004 *TBD*), any `notification_templates` row (seeded incrementally: each template ships in a migration in the same PR as the first `NotificationApi.enqueue` that uses it — 06 §8 Q4), `notification_outbox` sender, every ST01–ST20 job (the scheduling infrastructure exists, see the table above), locking strategy for quota/capacity/stock (needs an ADR first), all `module/*/api` implementations except `SystemConfigApi`. Redis and SMTP are configured but unused.

### Request flow

`Controller (@RequestMapping("/api/…"), @Valid, @PreAuthorize)` → `Service (@Transactional, business rules, BranchScope)` → `[TransitionHandler]` → `Repository`. Every business controller writes the `/api` prefix itself, matching `docs/api` paths (`/api` + contract path); actuator and swagger stay outside `/api`. Controllers hold no business logic and never see a rule ID. Entities never leave a controller; Entity↔DTO mapping goes through MapStruct (`@Mapper(componentModel = "spring")`), DTOs are Java records.

### Response envelopes

Success: `ApiResponse<T>{ data, message, code }` — payload one level deep in `data`; lists nest `PageResponse<T>` inside `data`.

Errors: `ErrorResponse{ success:false, errorCode, message, statusCode, timestamp, traceId }`, all 6 fields always present, produced only by `GlobalExceptionHandler`; `traceId` from MDC.

Exception → errorCode → HTTP mapping is fixed by `docs/convention/backend/04-exception-handling.md`: `BusinessRuleViolationException` (400), `InvalidStateTransitionException` (409), `ResourceNotFoundException` (404), `AccessDeniedScopeException` (403), `ConcurrencyConflictException` (409). **No 422 — conflicts are 409.** Only subclass a base exception when it carries ≥2 extra fields, needs a different HTTP status, or has special FSM/TTL retry semantics; state which criterion in the PR.

### FSM pattern

Hand-rolled enum + transition map, not Spring StateMachine. A `{Entity}TransitionHandler extends StateMachineBase<S>` declares `allowedTransitions()` copied **exactly** from the state-machine table — never add an edge by inference. Business guards (rule ID → `BusinessRuleViolationException`) run *before* `validateTransition()` (→ `InvalidStateTransitionException`). Some lifecycles are rule-only, not in 03: Article (BR-BV-02), Feedback (BR-DG-04). Account lock is a separate `is_locked` flag, independent of `status`.

### Persistence

Schema follows `docs/05-erd.md` §0, not the removed code:
- PK `id BIGINT GENERATED ALWAYS AS IDENTITY` (1–1 tables reuse the FK as PK); FK named `<singular>_id`, default `ON DELETE RESTRICT`.
- ROOT/PART/REF tables get `created_at`, `updated_at` (`TIMESTAMPTZ`); LOG tables only `created_at` and are insert-only. `created_by`/`updated_by` only where the ERD lists them.
- Money `BIGINT` (VND), business dates `DATE` (Asia/Ho_Chi_Minh), weight `NUMERIC(6,2)`, status/type `VARCHAR(30)` + `CHECK`.
- Vietnamese enum values in the spec (`TÁI_CHỦNG`, `KHÁM`, `ĐÃ_XEM`…) map to ASCII codes (`VACCINE_DUE`, `EXAM`, `SEEN`…) per the table in erd §0 — use the ASCII codes in DB and code.
- No hard delete of business data except the cases the rules allow (BR-KH-06, BR-TK-08, BR-TK-19, BR-BV-02, BR-KB-04); the app deletes children in the same transaction, no `CASCADE`.

Flyway owns the schema (`BE/src/main/resources/db/migration/`, `ddl-auto=validate` in the default profile; `baseline-on-migrate` is off on purpose). `V1__init_schema.sql` is the whole v16 schema: constraint names `ck_`/`uq_`/`ix_`/`ex_<table>_…`, FKs auto-named. Add `V{n}__*.sql` for changes; never edit an applied migration. Deliberate deviations from the ERD, recorded in erd §13 items 6–10: `audit_logs` is insert-only via trigger (UPDATE/DELETE/TRUNCATE raise `BR-QT-16`), every enum column is `VARCHAR(30)`, `btree_gist` extension for the boarding-overlap `EXCLUDE`, `audit_logs.actor_account_id` has **no FK** (always also write `actor_email`), `payments.amount >= 0`. Hard-delete order under `RESTRICT` (children first, same transaction) is listed in `docs/architecture/system-overview.md` §4b — e.g. BR-KB-04 deletes `vaccinations` before its `order_lines`. A dev volume left from the pre-reset schema makes the backend refuse to start; reset with `docker compose down -v` (ask first — it wipes dev data).

Cross-aggregate consequences (state-machine *Phụ lục*) run in **one transaction**, coordinated by the service of the aggregate that emits the event (domain-model principle 6). Outgoing email/notifications go through the `notification_outbox` table with retry (ST20).

`@Transactional` belongs on Service/TransitionHandler and spans one complete use case — not on controllers or repositories.

### Auth (spec)

Email is the only account identifier (OTP-verified); phone is not unique anywhere and CCCD is never stored (BR-TK-01, 16, BR-KH-10). Customer is the root entity; Account is optional and linked via `customers.account_id` (BR-TK-19). Roles are a fixed enum: `CUSTOMER`, `ADMIN`, `SUPER_MANAGER`, `BRANCH_MANAGER`, `RECEPTIONIST`, `VET`, `CARETAKER`; A05–A08 are scoped to one branch. Only `jwt.secret` remains in `application*.yml` (read by `JwtProperties`); session lifetime is the [CFG] `session.ttl_hours` (default 12 h, absolute, no refresh token — identity contract A1, ADR-0003). `app.cors.allowed-origins` is live (`CorsProperties`). Config reads env vars `DB_*`, `REDIS_*`, `JWT_SECRET`, `MAIL_*`, `CORS_ALLOWED_ORIGINS`, `SERVER_PORT` (template: root `.env.example`, which docker compose reads as `.env`; Spring itself does not read `.env`). `system_configs` is seeded by V2; `notification_templates` is seeded per task (06 §8 Q4), none yet. The first ADMIN is never seeded: developers create it themselves with SQL on their DB — don't add a seed migration or bootstrap code for it.

## Testing conventions

Unit tests (`*Test`) are mandatory for any Service/TransitionHandler carrying a business rule or guard — JUnit 5 + Mockito. Integration tests (`*IT`) are mandatory for every FSM and for complex business APIs — `@SpringBootTest` on real Postgres (no H2), exercising the real Flyway schema. Plain repository CRUD needs no dedicated test.

FSM tests must enumerate **every** valid and invalid (from, to) pair from the state-machine table — no omissions.

## Frontend notes

`FE/src/` is `app/` (`App.tsx` router + providers, `GlobalModal.tsx`) · `pages/<domain>/<Name>Page.tsx` (one file per route; `admin/`, `auth/`, `customer/`, `staff/`, `hotel/`, `news/`…) · `components/{admin,customer,staff,ui}/` · `shared/` (api, stores, components, hooks, types, utils, services, models). Server state → React Query; app state → Zustand store per domain. New page ⇒ add file + register the route in `src/app/App.tsx`. Tests: Vitest (`npm run test`, files `src/**/*.test.{ts,tsx}`), Playwright (`npm run test:e2e`).

The FE was built against the old spec — many routes (shop/cart/checkout/payment, membership, vouchers, caregivers, and admin tenants, promotions, refunds, incidents, workforce, AI…) cover features that are out of scope or tier 3 in v16. Check `docs/INDEX.md` before wiring a page to an API.

Known gaps:
- One HTTP client: `shared/api/axios.ts` (base URL `VITE_API_URL`, default `http://localhost:8081`; bearer token interceptor, one refresh retry on 401). Token keys `access_token` / `refresh_token`. `auth.api.ts`, `product.api.ts`, `review.api.ts` call endpoints of the removed BE — none of them exist now.
- Staff workspaces (`pages/staff/*Workspace`) use `shared/api/clinic.api.ts`, which answers from **`clinic-db.ts`, an in-browser mock store in `localStorage`** (rule ids from spec v13). Replace function by function with axios calls once the BE endpoint exists; the mock keeps the BE error envelope shape.
- Two `QueryClient` instances are constructed (`main.tsx` and `app/App.tsx`); the one in `App.tsx` wins.
- No auth route guard — every page mounts unconditionally.

## Gotchas

- **Port mismatch.** `mvn spring-boot:run` listens on 8081 (`SERVER_PORT` default) and axios defaults to 8081, but the Vite `/api` proxy defaults to 8080 (Docker; nginx in the FE image proxies `/api/` → `backend:8080/api/`). Set `VITE_API_URL` consistently when running BE locally.
- **CI is broken for BE** (`.github/workflows/ci.yml`): the `mvn flyway:migrate` step has no Flyway Maven plugin in `pom.xml`; its Postgres service and `-Dflyway.*` flags are now unnecessary (tests use Testcontainers). It triggers on `main, develop`, but the working branch is `dev`. FE lint runs with `|| true`.
- **Flyway version numbers are shared** between BE-1 and BE-2: V1 (schema) and V2 (seed `system_configs`) are taken; agree on the next `V{n}` before creating a migration.
- **FE still has a refresh-token flow** (`FE/src/shared/api/axios.ts` calls `/api/auth/refresh` on 401) although the backend has no refresh token (ADR-0003); fix it when wiring FE login.
- Stale file from the pre-reset codebase: `BE/BE-TIMELINE.md` (25 modules, `docs/00-requirements.md`) contradicts v16 — don't use it as a source.
- Skills live in `.opencode/skills/` (`designing-apis`, `postman-api-testing`); there is no `.claude/skills/` copy at the moment.
- `hs_err_pid*.log` / `replay_pid*.log` in the repo root and `BE/` are JVM crash dumps, not source. `.ua/` is gitignored tooling scratch.
