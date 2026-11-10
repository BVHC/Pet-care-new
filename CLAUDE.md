# CLAUDE.md

Guidance for Claude Code in this repository. Loaded every session, so it holds only **what is true across the whole system**. Detail lives in the files of the *Documentation map* — read them on demand, by section. Per-feature code map: `docs/architecture/system-overview.md` §3.2.

## Project

Pet Care — management system for a chain of pet clinics: exam / vaccination, grooming, boarding, counter sales, cashier, inventory, customer care. Monorepo: `BE/` (Java 21, Spring Boot 3.5, Maven, PostgreSQL 17 + pgvector + Flyway) and `FE/` (React 18 + Vite + TypeScript). Redis 7 only backs the IP rate limiter of the public identity / AI endpoints (ADR-0100, 0110); SMTP is used by the notification worker (dev: Mailpit). Docs are Vietnamese; code and identifiers are English.

Team: **BE-1** (branch `manh`) = identity, appointment, visit, care · **BE-2** (branch `dung`) = branch, customer, catalog, sales, inventory, boarding, content, report, payment, shop · **FE-1** (Hiến) = all UI + the AI subsystem. PRs go to `dev`; `main` is the default branch. Phase 1 (05–30/10/2026, tier 1–2) is done; **phase 2 (tier 3 + AI) runs Nov–Dec** and was approved at T54 (5/11/2026).

## Business in one page (spec v16)

**Actors:** A01 GUEST · A02 CUSTOMER · A03 ADMIN (technical only: SUPER_MANAGER accounts, lock / unlock, [CFG], templates, audit) · A04 SUPER_MANAGER (whole chain) · A05 BRANCH_MANAGER, A06 RECEPTIONIST (also cashier), A07 VET, A08 CARETAKER — each of A05–A08 belongs to exactly one branch.

**Main flow:** book appointment (LH) → check-in creates a **Visit** + opens an **Order** with one auto line (TN) → exam / vaccination / grooming (KB) → Visit completed → Order `PENDING` → cashier collects in an open shift (TG) → stock deducted FEFO (KO) → vaccination / follow-up reminders and Care Tasks (TB). Boarding (LT) runs in parallel and ends in a `BOARDING` Order that must be `PAID` before the pet is handed back.

**Cross-cutting principles (04 §Nguyên tắc):**
1. **Customer is the root, Account is optional.** Business data points to `customers`, never `accounts`; linking = setting `customers.account_id` (BR-TK-19).
2. **Identity = verified email only.** Phone is never unique. CCCD is never stored (BR-TK-01, 16, BR-KH-10).
3. **No hard delete** except where a rule allows it (BR-KH-06, BR-TK-08, 18, 19, BR-BV-02, BR-KB-03, BR-KB-04, BR-DG-05). The app deletes children itself (FKs are `RESTRICT`).
4. **Price snapshots:** an order line freezes its price when added; a boarding booking freezes its nightly rate.
5. **All stock is per lot.** Every quantity change writes a `stock_movements` row; FEFO never issues expired lots.
6. **Cross-aggregate consequences run in one transaction**, coordinated by the aggregate that emits the event (03 *Phụ lục*).
7. **Vaccines are counted by `VaccineType`**, not by brand.
8. **Only vaccinations recorded by the system are trusted.** External history is free text in the medical record.
9. **Branch scope** applies to data with `branch_id`. Customers, pets, catalogs and suppliers are chain-wide.
10. **[CFG] values apply only to work created after the change** (BR-QT-13): snapshot them into the row. Operational windows checked at run time (check-in window, NO_SHOW cutoff, reminder lead, login thresholds) apply from the next check (ADR-0057).

**Key cross-object chains (03 *Phụ lục*), each in one transaction:**

| Event | Consequences |
|---|---|
| Visit#1 check-in | Appointment → `CHECKED_IN`; Order (VISIT) `OPEN` + auto line |
| Visit#5 complete | Appointment → `COMPLETED`; Order → `PENDING`; medical record locked |
| Visit#6 cancel | Appointment → `CANCELLED` (not late); Order → `CANCELLED` |
| Vaccination recorded / deleted | FEFO deduct / restore to the same lot; vaccine order line added / removed; old Care Task cancelled |
| Order#8 paid | FEFO deduct; payment recorded in the shift |
| Pet deceased / owner transferred | `BOOKED` appointments and bookings → `CANCELLED` (not late); reminders / Care Tasks cancelled or moved |

**Scope tiers:** 1 = full, 2 = thin, 3 = specified at T54, being built in phase 2 (`docs/07`–`10`, `docs/api/*` phase-2 contracts). Phase-1 contracts and the list of tier-3 UC / ST are in `docs/INDEX.md`. Vietnamese enum values map to ASCII codes (erd §0, e.g. `TÁI_CHỦNG` → `VACCINE_DUE`, nhóm Khám/Tiêm → `MEDICAL`).

## Current state (2026-11-08)

Working tree: branch `manh` @ `5088cff` = `origin/dev` (merge of 2026-11-06, T55a/b/c done); Flyway V1–V48 + V50 (V49 spare for BE-2). `origin/manh` and `origin/dung` are behind (`dung` stops at ADR-0071 / V21). Phase 1 complete: 230 handler mappings (contracts list 232; open: visit #21 T28b, care #9–10 D025); `OpenApiContractParityIT` pins 187 documented operations (10 annotated modules; `branch` / `catalog` have no annotations, D026). 85 [CFG] keys (`ConfigKey`). Tests: 328 `*Test` + 196 `*IT` classes. V22 / V23 (and V24–V48) still await BE-2's confirmation of the numbers. Next for BE-1: phase 2 (T58+: Nhân sự, phẫu thuật, nội trú, khiếu nại; task list in `docs/time.md`).

**Phase 2 so far (T55, 6/11):** schema only (V34–V48, ADR-0110, 0113, 0116, 0117), 8 + 7 + 18 new [CFG] keys, 40 tier-3 notification templates, and **cross-module contract placeholders**: `default` methods in 14 `api` interfaces that throw `UnsupportedOperationException` ("Placeholder T55b / T55c") plus 3 `@Component` classes `CashierShiftApiPlaceholder` (sales), `PaymentGatewayClientPlaceholder` (new module `payment`, api only), `OnlineOrderApiPlaceholder` (new module `shop`, api only). List: system-overview §3.3. Rule when the real implementation lands: remove the `default` (the service overrides it) or delete the placeholder class + its test (`*Tier3PlaceholderTest`); exactly one bean per interface. No Java for workforce, inpatient, shop, payment or `ai` exists yet. **Surgery was dropped (ADR-0125, Accepted):** table `surgeries` (V37) and enum value `SURGERY` (V41) stay dead until V51, FE still has surgery screens (D063). **Complaints were dropped (ADR-0122, Accepted):** V39 tables, 6 `complaint.*` keys and 7 `COMPLAINT_*` templates stay dead until V51; feedback will lose its status and be deleted after 30 days (BR-DG-05, ST24) — code not changed yet (D061). FE-1 (T57-fe, `3a65850`) added the mock + screens for the 15 tier-3 modules; they run on the mock only.

| Module | Owner | Built | Missing |
|---|---|---|---|
| identity | BE-1 | identity-v1 #1–39 (UC22 #25–27, assignable staff #38, refresh #39), ST01, ST02, jobs, `StaffDirectoryApi`, `AccountContactApi`; ADR-0089, 0096, 0097 still Proposed | — (D055: FE refresh wired in `3a65850`, not yet run against a live BE) |
| care | BE-1 | care-v1 #1–8, ST20 outbox, `CareTask` + FSM + `CareTaskApi`, ST19 + owner-transfer listeners | #9–10 (D025); D050 |
| appointment | BE-1 | appointment-v1 #1–9, ST03, ST05, clinic-cancel listeners | — (D027 accepted, 0092) |
| visit | BE-1 | visit-v1 #1–20, `VisitQueryApi`, `VaccinationQueryApi`, `PrescriptionApi`, `FollowUpApi`, ST04, ST18, lock listener | #21 (T28b); D046 |
| branch, catalog | BE-2 | branch-v1 #1–21, catalog-v1 #1–22 | Swagger annotations (D026) |
| customer, inventory, sales, boarding, content | BE-2 | customer-v1 #1–21, inventory-v1 #1–17, sales-v1 #1–21, boarding-v1 #1–22, content-v1 #1–22; ST05, ST08, ST13, ST15 | — |
| report | BE-2 | report-v1 #1–7 (no tables; the 5 `*ReportApi` live in their owner modules) | — |

**Debts:** `docs/dept/INDEX.md` (62 entries, gitignored). Open on 2026-11-08: D045 (blocking: `SalesOrder` has no `@Version` — lock the Order before reading it), D058 (tier-3 placeholders), D059 (ADR / Flyway numbering unconfirmed), D020, D025, D026, D046, D050, D051, D057, D061 (complaints dropped in the spec, code not yet changed), D062 (workforce self-registration, ADR-0123: spec done, no code; V51 contested with D061), D063 (surgery dropped, ADR-0125: V37 / V41 / FE not yet changed), D064 (inpatient contract changes, ADR-0124: no Java, FE mock behind). D055 (FE refresh) is paid in code (`3a65850`). D056 (Redis without password) is accepted (ADR-0115).

**Tests baseline:** last full run (2026-11-05, merged tree `3163f22`, every unit test + every IT group in an isolated copy): 5550 unit + 2690 IT, 5 red. Re-checked 2026-11-07 on `5088cff`, all green when run alone: `VisitCheckInServiceTest` 26, `AuditLogViewIT` 31 (its `queriesUseExpectedIndex` red depends on table statistics when the whole suite writes `audit_logs`, D021), `PrescriptionIT` 53, `OpenApiContractParityIT` 11, `SchemaMigrationIT` 28, `ReminderScanIndexIT` 1 (known to go red only inside the visit group). T55 added `Tier3Be1SchemaIT`, `Tier3Be2SchemaIT`, `AiSchemaIT`, `Tier3Be2ContractWiringIT`, `*Tier3PlaceholderTest`: not part of the last full run. A full `mvn clean verify` gets killed by low host memory — run ITs in groups. Run history: `git log`, ADRs, `docs/testcases/`.

## Commands

All Maven commands run from `BE/`; npm commands from `FE/`. **Docker Desktop must be running** for any `*IT` and `PetcareApplicationTests` (Testcontainers `pgvector/pgvector:pg17`: Postgres 17 + extension `vector`, ADR-0110).

```bash
cp .env.example .env                              # once (repo root): compose has no JWT_SECRET default
docker compose up -d postgres redis mailpit       # local deps; Mailpit SMTP :1025, UI http://localhost:8025
docker compose up -d --build postgres redis mailpit backend   # BE smoke test (8080); plain `--build` also builds `frontend`, which fails on TS errors in FE/src/test/seed-dev.dry-run.test.ts
python BE/scripts/smoke/t41_main_flow.py [--down]   # main-flow smoke on a throwaway `petcare-smoke` stack (docs/testcases/T41-be.md §10); not 23:00–24:00 VN

mvn clean compile -DskipTests
mvn spring-boot:run                               # port 8081 (SERVER_PORT)
mvn test                                          # Surefire: *Test / *Tests
mvn verify                                        # + Failsafe: *IT
mvn test -Dtest=SomeServiceTest#someMethod
mvn verify -Dit.test=SomeFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
mvn verify -Dit.test='com/petcare/module/<m>/**/*IT' -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false   # one module's ITs

npm ci && npm run dev                             # http://localhost:5173, /api proxied to VITE_API_URL or :8080
npm run build                                     # tsc && vite build
npm run lint                                      # eslint --max-warnings 0
npx tsc --noEmit                                  # no typecheck script
npm run test / npm run test:e2e                   # Vitest / Playwright
npx vitest run <file…> --maxWorkers=2             # targeted run (machine is RAM-limited, shared by parallel sessions); full suite only in the final QA pass, several times (flaky GSAP intervals)
SEED_API_URL=http://localhost:8082 ./node_modules/.bin/vite-node scripts/seed-dev/cli-phase2.ts   # phase-2 demo data (after create-admin.sql + cli.ts); `--check` dry-runs; `FE/scripts/` is gitignored; docs/be-fe-integration.md §2

python docs/api/generator/generate.py && node docs/api/check-contracts.mjs   # after editing an API contract
git grep -n '^<<<<<<< \|^>>>>>>> '                # after every rebase / merge (see Gotchas)
```

Surefire and Failsafe pin `-Duser.timezone=Asia/Ho_Chi_Minh`; tests use real Flyway migrations with `ddl-auto=validate`. AI spike scripts: `BE/scripts/spike/` (FE-1, T56, not part of the build).

## Documentation map

Never invent a rule, transition, model, column or enum value. When something is missing, record it as an assumption or TBD (ADR, debt file, or contract §E). Spec files: start at `docs/INDEX.md`, which maps every module to exact line ranges.

| Need | File |
|---|---|
| Business rules `BR-<MODULE>-<n>` — **priority 1** on conflict | `docs/02-business-rules.md` |
| State machines, numbered transitions (`Visit#5`) — priority 2 | `docs/03-state-machines.md` |
| Domain model (ROOT / PART / REF / LOG) — priority 3 | `docs/04-domain-model.md` |
| ERD (55 tables in v16; 69 in `SchemaMigrationIT.ERD_TABLES` with the 4 AI tables and the 10 tier-3 BE-1 tables of §14), enum codes, decision log §13 — priority 4 | `docs/05-erd.md` |
| Actors, UC01–UC94, ST01–ST23 (must match the rules) | `docs/01-business-operations.md` |
| Phase 2 specs (approved T54): AI · BE-2 part · BE-1 part · T54 record + T57 screen inventory · payment sandbox guide | `docs/07-ai-subsystem.md` · `docs/08-tang3-be2-spec.md` · `docs/09-tang3-be1-spec.md` · `docs/10-t54-t57-checklists.md` · `docs/payment-gateway-setup.md` |
| BE ↔ BE contracts (`module/<m>/api`), who calls what, delivery dates; tier-3 interfaces §6b | `docs/06-module-contracts.md` |
| FE ↔ BE HTTP contracts (phase 1: 12 modules, 232 endpoints; phase 2: 12 modules, 113 endpoints after the home visit / ADR-0122 / ADR-0125 drops, not built) — **generated** from `docs/api/generator/m_<module>.py`, never hand-edit | `docs/api/*-v1.{md,yaml}` |
| What exists in code per feature (§3), placeholders (§3.3), migrations (§4b) | `docs/architecture/system-overview.md` |
| Which endpoints the BE has vs. which FE screens can be wired | `docs/be-fe-integration.md` |
| Why a technical decision was made | `docs/adr/INDEX.md` → `docs/adr/00nn-*.md` |
| Known code debt (gitignored, local only) | `docs/dept/INDEX.md` → `D<nnn>-*.md` |
| Task status / deadlines | `docs/time.md`, `docs/timeline_petcare_v15.xlsx` |
| Locking / transaction rules and lock orders · IT isolation registry · FE traps and mock rules (moved out of this file) | `docs/architecture/{concurrency-locking,testing-notes,frontend-notes}.md` |
| Backend conventions 01–10 (+ open TBDs) · manual FE test cases · task plans | `docs/convention/backend/` · `docs/testcases/` · `docs/plan/` |

Module codes: TK, QT, CN, CK, KH, SP, NS, LH, TN, KB, LT, BH, TG, KO, BV, DG, TB, BC (+ AI).

Open convention TBDs (don't decide silently — ask or write an ADR): command names when no Actor↔Command table exists; how "warn, don't block" rules return warnings (contracts assume a named field in `data`, e.g. `stockWarning`); locking for kennel capacity.

**ADRs** (`docs/adr/`, immutable once Accepted — supersede instead of editing; `docs/adr/INDEX.md` is the full list). **Next free: 0126.** BE-1's range is used up; reserved: 0118–0120 BE-2. Taken in phase 2 (by area: `docs/INDEX.md` §5): 0108 pet health record, 0109 AI LLM / embedding / pgvector, 0110 pgvector infra (V34–V35, chatbot rate limit), 0111 / 0112 `otp_tokens` / `notifications` cleanup, 0113 tier-3 BE-1 schema + placeholders (V36–V41), 0114 `refresh_tokens` cleanup job (D054), 0115 accepts Redis without password (D056), 0116 `ONLINE` order source (V42), 0117 tier-3 BE-2 contracts + schema (V43–V48), 0121 CI scope (D060), 0122 drop complaints / stateless feedback, 0123 workforce weekly self-registration, 0124 inpatient billing / kennel / orders, 0125 drop surgery. **ADR 0088 exists twice** (BE-2 `inventory-expiring-soon-flag`, BE-1 `n5-unlock-flag-follow-up-deferral-stopped-service`): renumbering the N5 one is BE-1's job.

## Backend architecture

```
com.petcare/
├── platform/         # cross-cutting infra; must NOT import module/*
│                     # audit, config, exception, fsm, model (envelopes), security, sequence
└── module/<m>/       # api/ controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/] [job/] [listener/]
```

Modules: identity, care, appointment, visit (BE-1) · branch, customer, catalog, sales, inventory, boarding, content, report, payment, shop (BE-2; `payment` and `shop` hold only an `api/` + a placeholder).

- **Module boundary:** a module imports only another module's `api/` package (interfaces, records, enums, events) — never its `entity`, `repository` or internal `service`.
- **1–1 consequence** = interface call inside the caller's transaction (implementation `@Transactional(MANDATORY)`; the caller never catches its exceptions).
- **Fan-out consequence** = synchronous event (`ApplicationEventPublisher` + `@EventListener` in `module/<m>/listener/`, delegating to a `MANDATORY` service). Not `AFTER_COMMIT`, no `@Async`, no `REQUIRES_NEW`, no swallowed exceptions. A listener failure rolls back the publisher. Events: `AccountLockedEvent` / `AccountUnlockedEvent`, `BranchClinicCancellationEvent`, `PetDeceasedEvent`, `PetOwnerTransferredEvent`, `StaffLeftBranchEvent` and `CustomerMergedEvent` (tier 3, no listener yet).
- **Unbuilt interface:** the owner commits a placeholder that throws `UnsupportedOperationException`, never a "safe" default — a `<Interface>Placeholder` `@Component`, or (T55b / T55c) a `default` method on the interface. Exactly one bean per interface; delete the placeholder (and its test) when the real one lands.
- **Request flow:** `Controller` (`@RequestMapping("/api/…")`, `@Valid`, `@PreAuthorize`, no logic, never sees a rule id) → `Service` (`@Transactional` spanning one use case, rules, `BranchScope`) → `[TransitionHandler]` → `Repository`. Entities never leave the service; MapStruct (`componentModel = "spring"`, `unmappedTargetPolicy = ERROR`); DTOs are records. Bean Validation runs before `@PreAuthorize`; every constraint carries a Vietnamese `message`.
- **Success envelope:** `ApiResponse<T>{data, message, code}` via `ok`, `created`, `accepted`; lists put `PageResponse<T>` inside `data`. A 204 is a `void` handler + `@ResponseStatus(NO_CONTENT)`.
- **Error envelope:** `ErrorResponse{success:false, errorCode, message, statusCode, timestamp, traceId}`, produced only by `GlobalExceptionHandler`:

  | Exception | Status |
  |---|---|
  | `BusinessRuleViolationException(ruleId, msg)` | 400 (appends ` (BR-…)` itself) |
  | `ResourceNotFoundException` | 404 |
  | `AccessDeniedScopeException` | 403 |
  | `InvalidStateTransitionException`, `ConcurrencyConflictException`, DB lock / constraint errors | 409 |
  | `TooManyRequestsException` | 429 |

  **No 422.** Subclass an exception only for ≥ 2 extra fields, a different status, or special retry semantics.
- **FSM:** enum + `{Entity}TransitionHandler extends StateMachineBase<S>`. `allowedTransitions()` is copied **exactly** from 03 — never add an edge by inference. Rule guards run before `validateTransition()`. Article (BR-BV-02) and Feedback (BR-DG-04) are rule-only lifecycles. The account lock is the `is_locked` flag, independent of `status`.
- **Error precedence:** 401 → 400 BR-TK-17 → 400 malformed / validation → 403 role → data authorization / 404. After that it is decided per feature in its ADR: staff admin puts 400 BR before 409 (ADR-0029); appointment puts 409 before any 400 (ADR-0049). Copy the neighbouring feature.

### Platform toolbox (reuse, don't re-create)

| Need | Use |
|---|---|
| Time | Inject `Clock` (`TimeConfig.BUSINESS_ZONE` = Asia/Ho_Chi_Minh): `Instant.now(clock)`, `LocalDate.now(clock)`. Never `now()` without the clock. `created_at` / `updated_at` come from the clock (`TimestampedEntity` / `CreatedAtEntity`, ADR-0015); bulk updates set `updated_at` themselves |
| Current user / scope | `BranchScope.current()` (accountId, role, branchId, `CHAIN` / `BRANCH` / `OWNER`), `resolve(branchId)` for lists, `check(branchId)` for a loaded row. CUSTOMER always gets 403 there: filter customer data by owner instead (ADR-0006). Only for data with `branch_id` |
| Auth | JWT HS256 carries only `sub`, `sid`, `jti`; the `sessions` row is re-checked on every request (read-only); role, branch and `mustChangePassword` come from the DB. Access token lives `session.access_ttl_minutes` [CFG] (10 min), capped by the 12 h session; `POST /auth/refresh` (public) swaps a one-use refresh token for a new pair (reuse of a spent token revokes the session). Staff are online ⇔ an unrevoked session has `access_expires_at > now` (BR-TN-06, ADR-0091). Public paths (`SecurityConfig.PUBLIC_PATHS`) are always anonymous. Open / revoke sessions with `SessionService` (`MANDATORY`). BR-TK-17 is enforced by `MustChangePasswordInterceptor` |
| [CFG] | `identity.api.SystemConfigApi.getInt/getDecimal/getBool/getTime(ConfigKey.X)`. A new key = new `ConfigKey` + seed migration; startup fails on a missing / invalid key or a broken cross-key pair (`ConfigConstraints`). Never hard-code a [CFG] number. `ai.*` keys have three segments and include the first DECIMAL keys |
| Audit (BR-QT-15/16) | `AuditRecorder.record(AuditEntry.of("ACTION").entity(t, id).before(..).after(..).reason(..))` inside the use-case tx. `recordIndependently` (REQUIRES_NEW) only for failures that must survive rollback, called **after** the rollback. Snapshots serialise to a JSON object; keys containing password / otp / token / secret / cccd / pin are rejected. `audit_logs` is insert-only at the app layer only (`@Immutable` entity, repository `save` only, GET-only controller): the DB triggers were dropped in V31 (ADR-0098) |
| Notifications | `care.api.NotificationApi.enqueue(NotificationRequest(templateCode, channel, accountId, email, payload, link))` in the same tx (outbox only). EMAIL needs a snapshotted email; IN_APP needs a non-`PENDING` account id. Recipients per BR-TB-02: reuse `appointment/service/AppointmentNoticeTargets` + `identity.api.AccountContactApi` (visit's ST04 has its own copy, `visit/service/ReminderChannels`). A new template = seed migration in the same PR, strict `{var}` grammar (ADR-0037); codes in `identity.api.NotificationTemplateCode`. `enqueue` does not check `required_vars` — check them yourself |
| Passwords / OTP | `PasswordEncoder` (BCrypt 10). Inputs over 72 bytes are BR-TK-03, checked before encode **and** before `matches`. `OtpService` (ADR-0010/0011/0023/0025): lock `accounts` first; every tx an `OtpRejectedException` passes through needs `noRollbackFor`; on public endpoints use `prepare` / `issuePrepared` / `findActive` / `settleCheckedOtp` so BCrypt never runs under a row lock |
| Document codes | `platform/sequence/DocumentCodeGenerator` (gap-free, table `document_counters`, ceiling 999 999 per month, ADR-0107); appointments use sequence `appointment_code_seq` (gaps allowed) |
| Swagger | springdoc. Each handler gets `@Operation` copied verbatim from `docs/api/openapi/<m>-v1.yaml`, plus `@ApiErrorDoc` per 4xx and `@Parameter`; every DTO field gets `@Schema(description, example)`. Never write `security` / 401 / 500. `OpenApiContractParityIT` must stay green — it pins the number of documented endpoints (187; a new endpoint changes the pin; ADR-0040, convention 10) |
| JSON | A float into an integer field → 400 `MALFORMED_REQUEST` (ADR-0032) |
| Rate limit (ADR-0100) | `platform/security/RateLimitFilter` (after `CorsFilter` in `SecurityConfig`, **not** a bean) counts `POST` `login` / `register*` / `password/*` per IP (`getRemoteAddr()`, IPv6 by /64) in `RedisRateLimiter` (Lua fixed window); over the limit → `TooManyRequestsException` → 429 + `Retry-After` before BCrypt, DB and audit. Redis down → fail-open with a circuit-breaker. Config `app.rate-limit.*` / `RATE_LIMIT_*` (not [CFG]); **off in the `test` profile**, only `RateLimitIT` turns it on. A new public path that should be throttled goes into the filter's matchers. **AI group (ADR-0110, BR-AI-09, not built yet):** `POST /api/public/ai/chat` + `/api/me/ai/chat` share one group with two windows on the same IP — `rl:ai` (10 / 60 s) then `rl:ai-hour` (60 / 3600 s); a request refused by the minute window does not spend hour budget. `RateLimitProperties` is a record: adding a group changes its constructor |
| Jobs | `module/<m>/job/{Name}Job` with `@Scheduled(cron = "${app.jobs.<name>.cron}", zone = BUSINESS_ZONE_ID)`. Not `@Transactional`; catch and log `<NAME>_FAILED`, never rethrow; MDC `traceId`; cron `"-"` in `application-test.yml`; cron as an env var in `docker-compose.yml` / `.env.example`. Pool = 4 threads, single instance. Existing (cron): `SessionCleanupJob` (03:00), `RefreshTokenCleanupJob` (03:10:11), `OtpTokenCleanupJob` (03:20:17), `NotificationOutboxCleanupJob` (03:40), `NotificationCleanupJob` (03:50:23), `PendingAccountCleanupJob` ST02 (`*/15`), `PriorityNotificationJob` / `NotificationOutboxJob` / `InAppNotificationJob` ST20, `NoShowJob` ST05 (`0 */5`), `AppointmentReminderJob` ST03 (`0 2/5`), `CashierShiftAutoCloseJob` ST13 (`0 4/5`), `PendingOrderDigestJob` ST13 (`0 50 23`), `BoardingNoShowJob` (`0 3/5`), `BoardingOverdueJob` ST15 (`0 1/5`), `StockAlertDigestJob` ST08 (`0 0 7`), `VaccineReminderJob` / `FollowUpReminderJob` ST04 (hourly 07–20h, second 30, minute 10 / 40), `VaccineOverdueJob` ST18 (same hours, minute 25) |

## Transactions, locking, concurrency — digest

Full rules, the **lock-order table per area** and the advisory namespaces (next free **1005**): `docs/architecture/concurrency-locking.md`. **Read it before writing or changing any write flow, job or lock**, plus the ADR of the neighbouring feature.

- One `@Transactional` per use case; never `catch` inside it (rollback-only → `UnexpectedRollbackException`); `noRollbackFor` only for a committed failure counter (OTP, login, current password). BCrypt, an audit after rollback, a cache reload after commit run in a facade **outside** the tx.
- Order: authorize → lock → read **under** the lock (never `findById` before locking) → `clock.instant()` → guards → write → `saveAndFlush` → audit + outbox → map.
- `findByIdForUpdate` = `FOR NO KEY UPDATE`; FK checks take only `FOR KEY SHARE`, which does **not** conflict, so a module inserting a row that points at a staff member or pet first locks it `FOR SHARE`. Repository lock methods are `@Transactional(MANDATORY)`.
- Lock orders are fixed per area and never reversed (sessions, OTP, staff admin, customer, appointment, visit, sales, cashier, boarding, content, care task, inventory, [CFG] edit, branch / catalog edit).
- Isolation: READ COMMITTED; paged reads `readOnly` + REPEATABLE READ; a write that re-reads after a lock wait stays READ COMMITTED.
- Jobs: keyset scan → one record per tx with `FOR NO KEY UPDATE SKIP LOCKED` → re-check → conditional UPDATE (must hit 1 row) → outbox; ≤ 60 s.
- Native SQL: append only the conditions that were sent; keep status literals; bind `LocalTime` as `CAST(:t AS time)`. No `lock_timeout`: a deadlock → 409.

## Persistence

- Schema follows erd §0: PK `BIGINT GENERATED ALWAYS AS IDENTITY` (1–1 tables reuse the FK as PK); FK `<singular>_id`, `ON DELETE RESTRICT`; ROOT / PART / REF tables have `created_at` + `updated_at`, LOG tables only `created_at` and are insert-only; money `BIGINT` VND, business dates `DATE` (Vietnam time), weight `NUMERIC(6,2)`; every enum column `VARCHAR(30)` + `CHECK`. Deliberate deviations: erd §13.
- **Flyway owns the schema** (`BE/src/main/resources/db/migration/`; per-migration table in system-overview §4b). **Never edit an applied migration** — not even a comment: the checksum changes and every DB that ran it fails to start.
- **Version numbers are shared with BE-2 and not all confirmed with the other dev** (V22–V50; note in 06 §6): V1 = whole v16 schema, V2 = [CFG] seed, V3–V33 phase 1 (templates, indexes, V27 `refresh_tokens`, V31–V32 audit / notification indexes, V33 unaccent search), V34–V35 AI (FE-1, T55a), V36–V41 BE-1 tier-3 schema, V42 `orders` source `ONLINE` (BE-2's slot, done by BE-1), V43–V48 BE-2 tier-3 schema. **V50** = index `refresh_tokens (expires_at, id)` for the D054 cleanup job (BE-1, ADR-0114), taken while **V49 stays unused** — the last of BE-2's range, kept spare for ADR-0117 TBD-1 / TBD-2: a DB that already ran V50 fails Flyway validation when V49 arrives (`out-of-order` is off), so BE-2 must tell BE-1 first or use V51. **Next free: V51** (V49 only by agreement with BE-2); beyond it ask. Before taking any number check `origin/dev` and the other dev's branch.
- **V16 checksum changed:** `571e0fe` removed conflict markers from `V16__appointment_code_sequence.sql` (comments only; `origin/dev` once carried the markers). A DB that ran the old V16 fails startup with *checksum mismatch for version 16*. No Flyway Maven plugin exists: after checking that `appointment_code_seq` matches, run `UPDATE flyway_schema_history SET checksum = -439054298 WHERE version = '16' AND checksum = -223559679`. Tell BE-2 before they merge.
- The first ADMIN is never seeded: create it with SQL — an `accounts` row (with `phone`) **plus** a `staff_profiles` row (`branch_id` NULL; without it `GET /api/me` is 500); template in system-overview §1. Don't add bootstrap code for it.

## Testing

- **Unit tests** (`*Test`, JUnit 5 + Mockito) for every service or handler that carries a rule or guard: `InOrder` for lock → guard → write order, and error branches write nothing.
- **ITs** (`*IT`, `@SpringBootTest`, `test` profile, `@Import(TestcontainersConfiguration.class)`, real Postgres, never H2) for every FSM and every complex API. FSM tests extend `FsmTransitionTestBase<S>` with an independent copy of the 03 table and **every** valid and invalid pair. One `*FsmIT` per FSM on real beans (no `@MockitoBean`). Each placeholder has a `*Tier3PlaceholderTest` (`PlaceholderAssert`); `Tier3Be2ContractWiringIT` checks the wiring.
- Token in an IT: insert the account with `JdbcTemplate`, then `SessionService.open(...)` inside a `TransactionTemplate`; sessions expire on the same `Clock`, so reopen tokens after moving a `MutableClock` across days (else 401).
- **Isolation between ITs** (era registry, cleanup rules, proven techniques: `docs/architecture/testing-notes.md`): each IT uses its own `MutableClock` era and cleans only that era. A job whose scan has no lower bound (ST02, ST05, ST13, ST15, ST18 overdue, cleanup jobs) touches every older era, so an IT running it takes an era **older than all others** (next **1982**); an IT without such a job takes a **future** era (next **2039**). Rows get UUID emails or a per-test tag; `audit_logs` rows are tagged by a random `reason`; an IT that changes `system_configs` restores the V2 value and calls `SystemConfigService.reload()`.
- **Mockito traps:** re-stub with `doThrow` / `doAnswer`, never `when(mock.call())`; `@MockitoSpyBean` cannot stub a CGLIB-proxied `@Transactional` bean — spy the repository or encoder instead; to call the real bean from a spy stub use `mockingDetails(spy).getMockCreationSettings().getDefaultAnswer()`.
- **Mutation testing is expected for concurrency / transaction guarantees** (remove a lock, add `noRollbackFor`, `REQUIRES_NEW`, `save` instead of `saveAndFlush`, change the isolation) — a test must turn red. Record the mutations in the ADR.

## Frontend notes (digest — details: `docs/architecture/frontend-notes.md`, read before writing a screen, hook or mock)

`FE/src/` is `app/` (`App.tsx` router + providers, `GlobalModal.tsx`) · `pages/<domain>/<Name>Page.tsx` (one file per route; domains `about admin auth branches customer doctors hotel legal news recommend services staff`) · `components/{admin,customer,staff,ui}/` · `shared/` (api, stores, components, hooks, types, utils, services, models, constants). Server state → React Query; app state → Zustand store per domain. New page ⇒ add file + register the route in `src/app/App.tsx`. Tests: Vitest (`src/**/*.test.{ts,tsx}`), Playwright (`npm run test:e2e`).

The FE was built against the old spec — many routes (shop/cart/checkout/payment, membership, vouchers, caregivers, admin tenants, promotions, refunds, incidents, workforce, AI…) cover features that were out of scope in v16 and are tier 3 now. Check `docs/INDEX.md` before wiring a page to an API.

Known gaps:
- **Tầng 3 trên FE (T57, 6/11/2026; chưa có BE thật cho 14 module tầng 3 nên chạy mock đầy đủ, `VITE_API_MOCK` bật):** 14 module có `shared/api/<m>.api.ts` + `shared/types/<m>.ts` + mock `shared/api/mock/<m>.mock.ts` (đăng ký ở `mock-api.ts`, nguồn chéo nối ở `mock/tier3-wiring.ts`; test từng mock import thẳng nên chạy với nguồn mặc định) + hook `shared/hooks/use*.ts` (khóa gốc `[<module>]`). Màn hình: giỏ / checkout / cổng thanh toán GIẢ LẬP (`/payment/mock-gateway`) / đơn / khiếu nại của khách, `/admin/{orders,refunds,workforce,my-schedule,incidents (nhãn Khiếu nại),inpatient,my-articles,ai}`, tab mới ở `/admin/warehouse`, `/admin/catalog/fees`, `/admin/branches/:id/lifecycle`, `/admin/articles/review`, gộp hồ sơ ở `/admin/customers` (BRANCH_MANAGER chỉ thấy Gộp hồ sơ vì UC23 không có A05), chat AI ở mascot, `/recommend`. Mock identity-admin đã có 85 khóa [CFG] và 70 mẫu thông báo như SQL của BE. **Hợp đồng còn thiếu (mock không bịa):** `OnlineOrderLine` chưa có `orderLineId` và `OnlineOrder` chưa có lý do giao thất bại (shop-v1); sales-v1 chưa có nguồn ONLINE nên trả hàng cho đơn online chưa tạo được trên mock; `PublicComment` chưa có cờ của tôi; payment #4–6 (đối soát cổng) chưa có màn hình. **Mock che lỗi:** thứ tự kiểm lỗi ở mock tầng 3 chưa đo trên BE thật và KHÔNG thống nhất giữa các mock: shop / payment / returns / stockops / fees / materialuse đặt 400 VALIDATION_FAILED của body trước 403 vai trò, tám module còn lại đặt 403 vai trò trước; cả 14 mock đặt 400 BR trước 409, còn BE GĐ1 ở sales / cashier / boarding kiểm 409 trước rule (chi tiết `docs/be-fe-integration.md` mục 8.3; ma trận test tay `docs/testcases/T57-fe.md`). Bước C (gỡ trang cũ) đã làm 7/11/2026 (Hiến duyệt): gỡ `/favorites`, `/membership`, `/caregivers`, `/vouchers`, `/packages`, `/admin/promotions`, `/admin/membership` (route, menu, liên kết ở SiteHeader / SiteFooter / AccountSidebar), `cart.store.ts`, `pages/recommend/recommend.mock.ts` và mã `clinic-*` chết; `/shop/:id` giữ vì `/recommend` link tới (`docs/10-t54-t57-checklists.md` §2.2). Chưa gỡ, ngoài danh sách duyệt: `AdminPurchasingPage.tsx`, `AdminVaccinesPage.tsx` (không route nào import), `shared/stores/notifications.store.ts` (không ai import). `App.tsx` có route `*` → `pages/NotFoundPage.tsx` (404 trong khung công khai, 8/11/2026) cho URL đã gỡ và mọi URL lạ.
- **HTTP:** one client `shared/api/axios.ts` (`VITE_API_URL`, default `:8081`; token from `shared/stores/session.store.ts`, access token 10 min renewed by `refreshSession()` (D055, ADR-0091: user requests and real clicks / keys refresh when < 5 min is left; polls pass `POLL` and never refresh, `useAccessValid` pauses them once the token has expired; single-flight + Web Locks) — a 401 that refresh cannot fix ends the session, 400 `BR-TK-17` sets `mustChangePassword`). Errors: `shared/api/api-error.ts` (`ApiError` = 6 envelope fields + `ruleId` from the end of `message`). `App.tsx` clears the React Query cache when the signed-in account changes.
- **Contract mock** `shared/api/mock/`: axios adapter answering `METHOD /api/path/:param` with `docs/api` envelopes; on in `npm run dev`, off in builds (`VITE_API_MOCK=false` in `FE/.env` → real BE). Demo accounts (staff carry `branchId`; `manager2@store2.vn` manages DRAFT branch 2), password `Petcare123`, every OTP `123456`. Manual test cases: `docs/testcases/`.
- **Staff workspaces** (reception, exam room, grooming) run on the contract APIs (`visit.api.ts`, `sales.api.ts`, `appointment.api.ts`, …; T33–T37), so they follow the contract mock like every other screen. `shared/api/clinic.api.ts` / **`clinic-db.ts`** (localStorage, rule ids of spec v13) are **no longer imported by anything that runs** (T49 moved the dashboard to the contract APIs and deleted `useClinicSync` / `useResetDemo`); the dead code (`useClinic.ts`, `clinic.api.ts`, `clinic-db.ts`) was deleted at T57 Bước C (7/11/2026); `types/clinic.ts` and the `clinic-*` util files stay because live code still imports them. The vaccination (visit-v1 #18–20) and prescription (#16–17) mocks were aligned with the real BE code on 2026-10-27 (T49, `docs/testcases/T49-fe.md` §C) but never run against a live BE.
- **Auth / routes:** 7 v16 roles in `shared/types/auth.ts`, one session for every role (`useSession`, `useAccount`). `RequireRole`: no session → login (`from`), `mustChangePassword` → `/auth/change-password`, wrong role → 403 in place. Customer `/pets`, `/booking`, `/review` sit behind `RequireLinkDecision` (BR-TK-19). Staff area = one `components/staff/StaffShell.tsx` for `/staff/*` (`WORKSPACE_ROLES`) and `/admin/*` (`AdminGuard` → `rolesForPath` from `ADMIN_MENU` in `shared/constants/staff-menu.ts`; `hidden: true` items stay routable). `/admin/catalog/:tab` SUPER_MANAGER only; `/admin/branches` SUPER_MANAGER (read-only schedule) + BRANCH_MANAGER (own branch). Profile: `shared/api/profile.api.ts`, `shared/hooks/useProfile.ts` (keys `['me']`). Form pieces: `components/admin/form-ui.tsx`; schedule helpers: `shared/utils/branch-schedule.ts`.
- Two `QueryClient` instances (`main.tsx`, `app/App.tsx`); the one in `App.tsx` wins. Its defaults now include `retry: shouldRetry` (`shared/constants/realtime.ts`: 4xx is never retried; 5xx and network errors retry up to 3 times; an `ApiError` with status 0 means a network error); the `main.tsx` client (`retry: 1`) has no effect.
- **Public pages (T46):** `shared/hooks/usePublicSite.ts` + mocks `public.mock.ts` / `content.mock.ts` serve `/api/public/*` (11 endpoints exist in the BE; prescriptions #16–17 exist since T38, ADR-0076–0078, but the FE mock has not been run against them). **Vitest flake (fixed in `src/test/intervals.ts`, wired in `setup.ts`):** `gsap.registerPlugin(ScrollTrigger)` runs at *module level* in `HeroBanner`, `HomePage`, `OurServicesStage`, `StackingProcessCards`, so any test that merely imports `<App />` starts GSAP's 250 ms `setInterval`; it outlives jsdom and throws "requestAnimationFrame is not defined", making vitest exit 1 with every test green (~1 full run in 4). `ScrollTrigger.killAll()` does **not** stop it (only `ScrollTrigger.disable()` does), so `setup.ts` now tracks every `setInterval` a test file creates and clears them in `afterAll`. Don't reintroduce per-file `killAll`; a single clean run proves nothing (run the suite several times). Detail: `docs/testcases/T46-fe.md` §C–D.
- **FE mock + styling gotchas (T47):** (1) mock state in `localStorage` must **merge defaults on load** (a stored state with the old shape throws on the first call of the new routes; `setup.ts` clears storage per test, so write a test that stores the old shape); mutations of admin data must `invalidateQueries({queryKey: ['public']})` (public queries have a 5 min `staleTime` and share the `QueryClient`). (2) Un-layered rules (`.table td`, `.input`, …) beat Tailwind v4 utilities: add the `!` suffix (`text-(--text-secondary)!`, `py-10!`) or the class silently does nothing. (3) Page-content JSON structure (Q1 of content-v1) is the **FE proposal** in `shared/constants/page-content.ts`, confirmed by the user on 27/10 but not yet recorded in the generated contract (regenerating needs Python) and BE-2 not yet told. Detail: `docs/testcases/T47-fe.md`.
- **Báo cáo + đối chiếu BE thật (T49):** `/admin/reports` (UC89) gọi BE thật `report-v1` #1–7 (T44, có từ 30/10; đã đối chiếu SQL ↔ API ↔ Chrome thật 1/11, `T49-fe.md` C.11); mock `shared/api/mock/report.mock.ts` phải giống BE thật: kỳ hợp lệ khi `to < from + 12 tháng` (đúng +12 tháng bị 400), `withinDays` 0–365, #4 / #5 chỉ trả nhóm có mẫu số ≥ 1 (kỳ trống = `rows: []`), thứ tự lỗi 400 thiếu / sai kiểu → 403 vai trò → 400 `VALIDATION_FAILED` → 403 phạm vi (message chung, KHÔNG kèm mã rule) → 400 `BR-BC-01`; doanh thu mock = Order PAID thật + khoản cố định, #3–5 là dòng mẫu; `/admin` là dashboard trên API hợp đồng, 5 route ẩn cũ chuyển hướng theo vai trò. **Bẫy lớn nhất tìm được: BE thật KHÔNG trả `branchId` ở `POST /auth/login`** (chỉ `GET /me` → `staffProfile.branchId`) mà mock có: dùng `useStaffBranch()` / `useStaffBranchId()` (`shared/hooks/useStaffBranch.ts`), KHÔNG đọc `account.branchId` trực tiếp; `login` đã gộp giá trị này vào phiên. Mock che lỗi khi nó dễ dãi hơn BE (quyền `GET /staff`, không cắt trang, tên `entityType` audit, ngữ nghĩa PATCH vắng = giữ nguyên): khi viết mock mới, đọc controller + DTO của BE trước. Ngày "hôm nay" luôn `vnDate()` / `todayVn()` (`localIsoDate` nay cũng là giờ VN). Mặc định BE là `size=20` (đã thấy ở staff, accounts): danh sách không gửi `size` có thể chỉ hiện 20 dòng đầu trên BE thật (mock trả hết). Đã chạy FE với BE thật tối 27/10 (quét 34 trang × 5 vai trò + luồng chính tiếp nhận → khám → thu tiền; `T49-fe.md` §C.3–C.4, cách dựng lại ở §F): không lỗi chức năng ngoài 2 lỗi FE nhẹ. Docker Desktop tự bật lại container cũ của người dùng, trong đó `petcare-api` giữ cổng 8081: chạy BE của mình ở **8082** và đặt `VITE_API_URL` theo. Màn bị BE chặn: `T49-fe.md` §D.
- **Lưu trú (T48):** mock `shared/api/mock/boarding.mock.ts` (+`boarding.seed.ts`; đủ boarding-v1 #1–22, #1–12 chép BE thật, #13–22 là ASSUMPTION) đọc mock khác qua *đăng ký nguồn* (`registerBoardingSource` / `registerStaySource` / `registerCounterGuests`…) để khỏi import vòng; khách tại quầy của đặt chỗ mẫu (9001–9008) chỉ tra được theo id ở bàn thu tiền / chi tiết khách. Khách vào `/boarding-bookings[/new|/:id]` (danh sách / chi tiết ngoài `RequireLinkDecision`, đặt chỗ trong); nhân viên `/admin/boarding`, `/admin/kennels` (BRANCH_MANAGER). `Booking.activeOrderId` suy từ Order BOARDING chưa hủy; trả thú thu tiền ở bàn Lễ tân. `Modal` (`modal-templates`) nay có `aria-labelledby`; dialog mới dùng Radix `Dialog`. Responsive chưa làm (quyết định 27/10). Detail: `docs/testcases/T48-fe.md`.

## Gotchas

- **Rebases have committed conflict markers seven times** (the last: BE-1 commit `8f8542d` put markers in `CLAUDE.md` and reached `origin/dev` via PR #30, found 9/11/2026; before that, six BE-1 commits and `origin/dev` @ `b2f1da2` did not compile, cleaned by hand in merge `d097685`). After any rebase / merge run `git grep -n '^<<<<<<< \|^>>>>>>> '` and `mvn clean compile` before pushing; when markers are removed by hand, `git add` every cleaned file. When both sides re-implemented the same thing, keep the owner's version (BE-2 for customer, BE-1 for appointment) — two beans per interface or two methods with one name break the build.
- **ADR and Flyway numbers are global across branches** and have collided before (ADR 0041–0044, 0047–0051, 0088, 0091–0092; V13). Check `origin/dev` and the other dev's branch first. When renumbering, add a **Đánh số lại** line to each file and update every reference.
- **Large files:** `docs/02-business-rules.md` (~80 KB), `docs/06-module-contracts.md`, `docs/time.md` (~140 KB) — read by section (Grep the heading or rule id), never whole.
- **Port mismatch:** `mvn spring-boot:run` and axios default to 8081; the Vite proxy and Docker use 8080. Set `VITE_API_URL` consistently.
- **Smoke tests on Windows:** `curl.exe` corrupts non-ASCII bytes in arguments (→ 400 `MALFORMED_REQUEST`). Send UTF-8 bodies with `--data-binary @file`.
- **Cross-module `api` interfaces:** a new abstract method breaks every anonymous / stand-in implementation in the other dev's tests (`new VaccinationQueryApi() {…}` in `BoardingBookingIT`). `grep` for `new <Api>() {` and `implements <Api>` before adding one, and tell the other dev. That is why tier-3 additions are `default` placeholders.
- **Postgres must have pgvector (V34+, ADR-0110):** compose, CI and Testcontainers use `pgvector/pgvector:pg17`. Windows Postgres and `postgres:17` containers fail Flyway on V34 (`CREATE EXTENSION vector` needs a superuser). The image is Debian 12 (glibc 2.36), `postgres:17` is Debian 13 (2.41): an old volume opens but prints `collation version mismatch` and string indexes may be wrong — `pg_dump -Fc` + `pg_restore` into a new container, or in each DB (also `template1`) `REINDEX DATABASE <db>; ALTER DATABASE <db> REFRESH COLLATION VERSION;` (ADR-0110 §2). Compose still names the volume `postgres_data`, so run that fix once after pulling.
- **CI (`.github/workflows/ci.yml`, ADR-0121):** triggers on `main, dev`. A PR runs the conflict-marker `git grep`, `mvn clean compile`, `mvn test` (unit; Docker for `PetcareApplicationTests`) and the FE job (`tsc`, lint with `|| true`, build). `integration-tests` (`mvn verify`, all ITs) runs only on push to `main` or `workflow_dispatch`, so IT-only breakage shows late: still run the ITs in groups locally before merging. Never run on a real Actions runner yet (timings unknown). The 3 `FE/src/test/seed-dev*` files are excluded from `tsc` because they import the gitignored `FE/scripts/`.
- **`SalesOrder` has no `@Version`** (D045): lock via a locking `VisitOrderApi` method first, read after — a stale entity overwrites `total_amount`.
- **OSIV is off** (ADR-0020): map to DTOs inside the service tx; a lazy access in a controller throws.
- **BCrypt `matches` only compares the first 72 bytes:** reject longer input before matching.
- **`docs/dept/` is gitignored:** BE-2 never sees the debt files. Anything BE-2 needs goes into `docs/06-module-contracts.md` §6. Don't un-ignore it without asking.
- **Skills** live in `.opencode/skills/` (`designing-apis`, `postman-api-testing`); there is no `.claude/skills/`.
- `hs_err_pid*.log` / `replay_pid*.log` are JVM crash dumps; `.ua/` is tooling scratch.

## After finishing a change

1. Contract change: edit `docs/api/generator/m_<module>.py`, regenerate, run the check. Cross-module interface change: `docs/06-module-contracts.md`.
2. New technical decision: `docs/adr/00nn-*.md` + `docs/adr/INDEX.md` (+ the grouped line in `docs/INDEX.md` §5).
3. Debt left or paid: `docs/dept/D<nnn>-*.md` + `docs/dept/INDEX.md`.
4. State: `docs/architecture/system-overview.md` (one short §3.2 row per feature + one header-log line, keep 5; detail goes to the ADR), `docs/time.md` (task status).
5. **This file:** only when something system-wide changes — the state table, a lock order, a platform tool, a new gotcha, the next free V / ADR / era number. Keep feature history and test-run history out of it.
6. Never `git add` / `commit` / `push` unless the user asks.
