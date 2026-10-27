# CLAUDE.md

Guidance for Claude Code in this repository. Loaded into every session, so it holds only **what is true across the whole system**; detail lives in the files of the *Documentation map* — read them on demand, by section.

## Project

Pet Care — management system for a chain of pet clinics: exam / vaccination, grooming, boarding, counter sales, cashier, inventory, customer care. Monorepo: `BE/` (Java 21, Spring Boot 3.5, Maven, PostgreSQL 17 + Flyway) and `FE/` (React 18 + Vite + TypeScript). Redis 7 is configured but unused; SMTP is used by the notification worker (dev: Mailpit). Documentation is Vietnamese; code and identifiers are English.

Team: **BE-1** (branch `manh`) = identity, appointment, visit, care · **BE-2** (branch `dung`) = branch, customer, catalog, sales, inventory, boarding, content, report · **FE-1** = all UI. PRs go to `dev`; `main` is the default branch. Phase 1 runs 05–30/10/2026 (tier 1–2 scope), phase 2 is Nov–Dec (tier 3).

## Business in one page (spec v16)

**Actors:** A01 GUEST · A02 CUSTOMER · A03 ADMIN (technical only: SUPER_MANAGER accounts, lock / unlock, [CFG], templates, audit) · A04 SUPER_MANAGER (whole chain) · A05 BRANCH_MANAGER, A06 RECEPTIONIST (also cashier), A07 VET, A08 CARETAKER — each of A05–A08 belongs to exactly one branch.

**Main flow:** book appointment (LH) → check-in creates a **Visit** + opens an **Order** with one auto line (TN) → exam / vaccination / grooming (KB) → Visit completed → Order `PENDING` → cashier collects in an open shift (TG) → stock deducted FEFO (KO) → vaccination / follow-up reminders and Care Tasks (TB). Boarding (LT) runs in parallel and ends in a `BOARDING` Order that must be `PAID` before the pet is handed back.

**Cross-cutting principles (04 §Nguyên tắc):**
1. **Customer is the root, Account is optional.** Business data points to `customers`, never to `accounts`; linking an account = setting `customers.account_id` (BR-TK-19).
2. **Identity = verified email only.** Phone is never unique. CCCD (national ID) is never stored (BR-TK-01, 16, BR-KH-10).
3. **No hard delete** except where a rule allows it (BR-KH-06, BR-TK-08, 18, 19, BR-BV-02, BR-KB-03, BR-KB-04). The app deletes children itself (FKs are `RESTRICT`).
4. **Price snapshots:** an order line freezes its price when added; a boarding booking freezes its nightly rate.
5. **All stock is per lot.** Every quantity change writes a `stock_movements` row; FEFO never issues expired lots.
6. **Cross-aggregate consequences run in one transaction**, coordinated by the aggregate that emits the event (03 *Phụ lục*).
7. **Vaccines are counted by `VaccineType`**, not by product brand.
8. **Only vaccinations recorded by the system are trusted.** External history is free text in the medical record.
9. **Branch scope** applies to data with `branch_id`. Customers, pets, catalogs and suppliers are chain-wide.
10. **[CFG] values apply only to work created after the change** (BR-QT-13): snapshot them into the row. Operational windows checked at run time (check-in window, NO_SHOW cutoff, reminder lead, login thresholds) apply from the next check (ADR-0057).

**Key cross-object chains (03 *Phụ lục*), all in one transaction:**

| Event | Consequences |
|---|---|
| Visit#1 check-in | Appointment → `CHECKED_IN`; Order (VISIT) `OPEN` + auto line |
| Visit#5 complete | Appointment → `COMPLETED`; Order → `PENDING`; medical record locked |
| Visit#6 cancel | Appointment → `CANCELLED` (not late); Order → `CANCELLED` |
| Vaccination recorded / deleted | FEFO deduct / restore to the same lot; vaccine order line added / removed; old Care Task cancelled |
| Order#8 paid | FEFO deduct; payment recorded in the shift |
| Pet deceased / owner transferred | `BOOKED` appointments and bookings → `CANCELLED` (not late); reminders / Care Tasks cancelled or moved |

**Scope tiers:** 1 = full, 2 = thin, 3 = diagram only — **don't build tier 3**; the list is in `docs/INDEX.md`. Rules marked 🆕 are proposals not yet approved. Vietnamese enum values map to ASCII codes (erd §0, e.g. `TÁI_CHỦNG` → `VACCINE_DUE`, nhóm Khám/Tiêm → `MEDICAL`).

## Current state (2026-10-27)

`manh` @ `1c7b08f` = `origin/dev` (3 commits ahead of `origin/manh`); T28 is merged (PR #19, ADR-0072–0075). **Uncommitted:** T38 (ADR-0076: `PrescriptionItem`, `PrescriptionApi`; ADR-0077: visit-v1 #16 prescribe; ADR-0078: #17 remove a prescription line; part 3: integration + smoke, no `src/main` change) — T38 is complete. `origin/dung` stops at ADR-0071 / V21. No `*Placeholder` left in `src/main`. Per-feature map: `docs/architecture/system-overview.md` §3.2.

| Module | Owner | Built (contract #, ADR) | Still missing |
|---|---|---|---|
| identity | BE-1 | identity-v1 #1–24, #28–37; ST01, ST02, session cleanup; `StaffDirectoryApi`, `AccountContactApi` | #25–27 (UC22) |
| care | BE-1 | ST20 (EMAIL HIGH / NORMAL, IN_APP), outbox cleanup, care-v1 #5–8; `CareTaskApi.cancelForRevaccination` (0074) | #9–10 (D025), rest of Care Task (`create`, `cancelFollowUpTasks` throw — D047), ST04, `FollowUpApi` (T39, D029) |
| appointment | BE-1 | appointment-v1 #1–9, ST05, ST03, clinic-cancel listeners, 3 APIs (0045–0057) | `findPetForShare` in booking (D027) |
| visit | BE-1 | visit-v1 #1–15 (0058–0070), #18 (0073), #19 (0074), #20 (0075), #16 (0077), #17 (0078); `VisitQueryApi`, `VaccinationQueryApi` (0072), `PrescriptionApi` (0076); lock listener | #21 (T28b); D042, D043, D046 |
| branch, catalog | BE-2 | branch-v1 #1–21, catalog-v1 #1–22; `BranchQueryApi`, `BranchApi`, `CatalogQueryApi` | Swagger annotations (D026) |
| customer | BE-2 | customer-v1 #1–21; `CustomerApi`, `CustomerQueryApi`, `PetApi`, `PetQueryApi` | — |
| inventory | BE-2 | inventory-v1 #1–17; `StockApi`, `StockQueryApi` | `StockReportApi`, ST08 (T44) |
| sales | BE-2 | sales-v1 #1–8, #10, #12–21, ST13; `VisitOrderApi`, `OrderQueryApi`, `CashierShiftQueryApi` | #9 + `BoardingOrderApi` (T43), #11 (`PrescriptionApi` ready since ADR-0076, BE-2 wires it) |
| boarding | BE-2 | boarding-v1 #1–12, ST05, clinic-cancel listeners; `BoardingQueryApi` (0065) | #13–22, ST15 (T42, T43); `activeOrderId` null |
| content | BE-2 | content-v1 #1–22; `FeedbackQueryApi` (0071) | `FeedbackReportApi` (T44) |
| report | BE-2 | — | everything (T44) |

**Blocking debts:** D029 `FollowUpApi` (with T39); D045 `SalesOrder` has no `@Version` — #19 must lock the Order before reading it.

**Tests:** last full green `mvn clean verify` = 2026-10-19; a full run gets killed by low host memory — run ITs in groups (`-Dit.test='com/petcare/module/<m>/**/*IT'`). Known red: 2 ITs in `AppointmentReminderIT` (`findDueForReminderIds` lacks the lower bound `(slot_date, slot_start) > (:fromDate, :fromTime)` since `af117e0`). Latest (2026-10-27, T38 part 3): unit 5134/5134; ITs by group 2203 — visit 397, `OpenApiContractParityIT` 11 (pins 158 endpoints), sales + appointment 512, inventory + customer 244, identity 730, care + branch + catalog 139, boarding + content + root 170 — only the 2 known reds; contract check 230 operations; Docker smoke of #16/#17 → complete → pay 27/27.

## Commands

All Maven commands run from `BE/`; npm commands from `FE/`. **Docker Desktop must be running** for any `*IT` and for `PetcareApplicationTests` (Testcontainers `postgres:17`).

```bash
cp .env.example .env                              # once (repo root): compose has no JWT_SECRET default
docker compose up -d postgres redis mailpit       # local deps; Mailpit SMTP :1025, UI http://localhost:8025
docker compose up -d --build postgres redis mailpit backend   # BE smoke test (8080); plain `--build` also builds `frontend`, which fails (2026-10-26) on TS errors in FE/src/test/seed-dev.dry-run.test.ts

mvn clean compile -DskipTests
mvn spring-boot:run                               # port 8081 (SERVER_PORT)
mvn test                                          # Surefire: *Test / *Tests
mvn verify                                        # + Failsafe: *IT
mvn test -Dtest=SomeServiceTest#someMethod
mvn verify -Dit.test=SomeFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false

npm ci && npm run dev                             # http://localhost:5173, /api proxied to VITE_API_URL or :8080
npm run build                                     # tsc && vite build
npm run lint                                      # eslint --max-warnings 0
npx tsc --noEmit                                  # no typecheck script
npm run test / npm run test:e2e                   # Vitest / Playwright

python docs/api/generator/generate.py && node docs/api/check-contracts.mjs   # after editing an API contract
git grep -n '^<<<<<<< \|^>>>>>>> '                # after every rebase / merge (see Gotchas)
```

Surefire and Failsafe pin `-Duser.timezone=Asia/Ho_Chi_Minh`; tests use real Flyway migrations with `ddl-auto=validate`.

## Documentation map

Never invent a rule, transition, model, column or enum value. When something is missing, record it as an assumption or TBD (in the ADR, a debt file, or contract §E). Spec files: start at `docs/INDEX.md`, which maps every module to exact line ranges.

| Need | File |
|---|---|
| Business rules `BR-<MODULE>-<n>` — **priority 1** on conflict | `docs/02-business-rules.md` |
| State machines, numbered transitions (`Visit#5`) — priority 2 | `docs/03-state-machines.md` |
| Domain model (ROOT / PART / REF / LOG) — priority 3 | `docs/04-domain-model.md` |
| ERD: 55 tables, enum codes, decision log §13 — priority 4 | `docs/05-erd.md` |
| Actors, UC01–UC89, ST01–ST21 (must match the rules) | `docs/01-business-operations.md` |
| BE ↔ BE contracts (`module/<m>/api`), who calls what, delivery dates, notes for the other dev | `docs/06-module-contracts.md` |
| FE ↔ BE HTTP contracts (12 modules, 230 endpoints) — **generated** from `docs/api/generator/m_<module>.py`, never hand-edit | `docs/api/*-v1.{md,yaml}` |
| What exists in code, per feature (§3), migrations (§4b) | `docs/architecture/system-overview.md` |
| Why a technical decision was made | `docs/adr/INDEX.md` → `docs/adr/00nn-*.md` |
| Known code debt (placeholders, deferred work, assumptions) | `docs/dept/INDEX.md` → `D<nnn>-*.md` (gitignored, local only) |
| Task status / deadlines | `docs/time.md`, `docs/timeline_petcare_v15.xlsx` |
| Backend conventions 01–10 (+ open TBDs) · manual FE test cases · task plans | `docs/convention/backend/` · `docs/testcases/` · `docs/plan/` |

Module codes: TK, QT, CN, CK, KH, SP, NS, LH, TN, KB, LT, BH, TG, KO, BV, DG, TB, BC.

Open convention TBDs (don't decide silently — ask or write an ADR): command names when no Actor↔Command table exists; how "warn, don't block" rules return warnings (contracts assume a named field in `data`, e.g. `stockWarning`); locking for kennel capacity.

**ADR map** (`docs/adr/`, immutable once Accepted — supersede instead of editing; **next free: 0079**):

| Area | ADRs |
|---|---|
| Platform | 0001 audit · 0002 client IP · 0004 [CFG] · 0007 jobs · 0015 timestamps from `Clock` · 0020 OSIV off · 0032 strict JSON ints · 0040 Swagger = contract |
| Auth | 0003 JWT + DB session · 0005 public paths anonymous · 0006 customer owner scope · 0009 BCrypt · 0010, 0011, 0025 OTP · 0019 login · 0021 logout · 0022 change password · 0023 forgot / reset |
| Accounts, staff, admin | 0024 staff directory · 0026 staff self-edit · 0027 profile link · 0028 customer self-edit · 0029–0035 staff admin / lock · 0036 [CFG] edit · 0037 templates · 0038 audit view · 0039 in-app notifications |
| Notifications, jobs | 0008 session cleanup · 0012, 0014, 0016, 0017 ST20 · 0013 ST02 · 0018 FK indexes on `accounts` |
| Appointment (BE-1) | 0045 booking locks · 0046 slots · 0047 book · 0048 view · 0049 reschedule / cancel · 0050 cross-module API · 0052 clinic cancel · 0053, 0054 ST05 · 0055 booking restriction · 0056 ST03 · 0057 assumptions confirmed |
| Visit (BE-1) | 0058 foundation · 0059 check-in (amends 0044) · 0060 queue · 0061 dispatch · 0062 call / cancel / lock listener · 0063 reassign · 0066 medical record · 0067 record write · 0068 weight · 0069 complete (replaces 0066 §5) · 0070 addendum · 0072 vaccination foundation · 0073 vaccination options (#18) · 0074 vaccination record (#19) · 0075 vaccination delete (#20) · 0076 prescription foundation + `PrescriptionApi` · 0077 prescribe (#16) · 0078 remove prescription line (#17) |
| BE-2 | 0041 customer & pet · 0042 inventory stock ledger · 0043 document codes · 0044 order core · 0051 retail order · 0064 cashier shift / payment / ST13 · 0065 boarding kennels / bookings / ST05 · 0071 public pages, articles, feedback |

## Backend architecture

```
com.petcare/
├── platform/         # cross-cutting infra; must NOT import module/*
│                     # audit, config, exception, fsm, model (envelopes), security, sequence
└── module/<m>/       # api/ controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/] [job/] [listener/]
```

- **Module boundary:** a module imports only another module's `api/` package (interfaces, records, enums, events) — never its `entity`, `repository` or internal `service`.
- **1–1 consequence** = interface call inside the caller's transaction (implementation is `@Transactional(MANDATORY)`; the caller never catches its exceptions).
- **Fan-out consequence** = synchronous event (`ApplicationEventPublisher` + `@EventListener` in `module/<m>/listener/`, delegating to a `MANDATORY` service). Not `AFTER_COMMIT`, no `@Async`, no `REQUIRES_NEW`, no swallowed exceptions. A listener failure rolls back the publisher.
- **Unbuilt interface:** the owner commits an `<Interface>Placeholder` that throws `UnsupportedOperationException`, never a "safe" default. Exactly one bean per interface — delete the placeholder (and its test) when the real one lands.
- **Request flow:** `Controller` (`@RequestMapping("/api/…")`, `@Valid`, `@PreAuthorize`, no logic, never sees a rule id) → `Service` (`@Transactional` spanning one use case, rules, `BranchScope`) → `[TransitionHandler]` → `Repository`. Entities never leave the service; MapStruct (`componentModel = "spring"`, `unmappedTargetPolicy = ERROR`); DTOs are records. Bean Validation runs before `@PreAuthorize`; every constraint carries a Vietnamese `message`.
- **Success envelope:** `ApiResponse<T>{data, message, code}` via `ok`, `created`, `accepted`; lists put `PageResponse<T>` inside `data`. A 204 is a `void` handler + `@ResponseStatus(NO_CONTENT)`.
- **Error envelope:** `ErrorResponse{success:false, errorCode, message, statusCode, timestamp, traceId}`, produced only by `GlobalExceptionHandler`:

  | Exception | Status |
  |---|---|
  | `BusinessRuleViolationException(ruleId, msg)` | 400 (appends ` (BR-…)` itself) |
  | `ResourceNotFoundException` | 404 |
  | `AccessDeniedScopeException` | 403 |
  | `InvalidStateTransitionException`, `ConcurrencyConflictException`, DB lock / constraint errors | 409 |

  **No 422.** Subclass an exception only for ≥ 2 extra fields, a different status, or special retry semantics.
- **FSM:** enum + `{Entity}TransitionHandler extends StateMachineBase<S>`. `allowedTransitions()` is copied **exactly** from 03 — never add an edge by inference. Rule guards run before `validateTransition()`. Article (BR-BV-02) and Feedback (BR-DG-04) are rule-only lifecycles. The account lock is the `is_locked` flag, independent of `status`.
- **Error precedence:** 401 → 400 BR-TK-17 → 400 malformed / validation → 403 role → data authorization / 404. After that, the order is decided per feature in its ADR: staff admin puts 400 BR before 409 (ADR-0029); appointment puts 409 before any 400 (ADR-0049). Copy the neighbouring feature.

### Platform toolbox (reuse, don't re-create)

| Need | Use |
|---|---|
| Time | Inject `Clock` (`TimeConfig.BUSINESS_ZONE` = Asia/Ho_Chi_Minh): `Instant.now(clock)`, `LocalDate.now(clock)`. Never `now()` without the clock. `created_at` / `updated_at` come from the clock (`TimestampedEntity` / `CreatedAtEntity`, ADR-0015); bulk updates set `updated_at` themselves |
| Current user / scope | `BranchScope.current()` (accountId, role, branchId, `CHAIN` / `BRANCH` / `OWNER`), `resolve(branchId)` for lists, `check(branchId)` for a loaded row. CUSTOMER always gets 403 there: filter customer data by owner instead (ADR-0006). Only for data with `branch_id` |
| Auth | JWT HS256 carries only `sub`, `sid`, `jti`; the `sessions` row is re-checked on every request; role, branch and `mustChangePassword` come from the DB. Public paths (`SecurityConfig.PUBLIC_PATHS`) are always anonymous. Open / revoke sessions with `SessionService` (`MANDATORY`). BR-TK-17 is enforced by `MustChangePasswordInterceptor` |
| [CFG] | `identity.api.SystemConfigApi.getInt/getDecimal/getBool/getTime(ConfigKey.X)`. A new key = new `ConfigKey` + seed migration; startup fails on a missing / invalid key or a broken cross-key pair (`ConfigConstraints`). Never hard-code a [CFG] number |
| Audit (BR-QT-15/16) | `AuditRecorder.record(AuditEntry.of("ACTION").entity(t, id).before(..).after(..).reason(..))` inside the use-case tx. `recordIndependently` (REQUIRES_NEW) only for failures that must survive rollback, called **after** the rollback. Snapshots serialise to a JSON object; keys containing password / otp / token / secret / cccd / pin are rejected. `audit_logs` is insert-only (trigger) |
| Notifications | `care.api.NotificationApi.enqueue(NotificationRequest(templateCode, channel, accountId, email, payload, link))` in the same tx (outbox only). EMAIL needs a snapshotted email; IN_APP needs a non-`PENDING` account id. Recipients per BR-TB-02: reuse `appointment/service/AppointmentNoticeTargets` + `identity.api.AccountContactApi`. A new template = seed migration in the same PR, strict `{var}` grammar (ADR-0037). `enqueue` does not check `required_vars` — check them yourself |
| Passwords / OTP | `PasswordEncoder` (BCrypt 10). Inputs over 72 bytes are BR-TK-03, checked before encode **and** before `matches`. `OtpService` (ADR-0010/0011/0023/0025): lock `accounts` first; every tx an `OtpRejectedException` passes through needs `noRollbackFor`; on public endpoints use `prepare` / `issuePrepared` / `findActive` / `settleCheckedOtp` so BCrypt never runs under a row lock |
| Document codes | `platform/sequence/DocumentCodeGenerator` (gap-free, table `document_counters`); appointments use sequence `appointment_code_seq` (gaps allowed) |
| Swagger | springdoc. Each handler gets `@Operation` copied verbatim from `docs/api/openapi/<m>-v1.yaml`, plus `@ApiErrorDoc` per 4xx and `@Parameter`; every DTO field gets `@Schema(description, example)`. Never write `security` / 401 / 500. `OpenApiContractParityIT` must stay green — it also pins the number of documented endpoints (ADR-0040, convention 10) |
| JSON | A float into an integer field → 400 `MALFORMED_REQUEST` (ADR-0032) |
| Jobs | `module/<m>/job/{Name}Job` with `@Scheduled(cron = "${app.jobs.<name>.cron}", zone = BUSINESS_ZONE_ID)`. Not `@Transactional`; catch and log `<NAME>_FAILED`, never rethrow; MDC `traceId`; cron `"-"` in `application-test.yml`; cron as an env var in `docker-compose.yml` / `.env.example`. Pool = 4 threads, single instance. Existing: `SessionCleanupJob`, `PendingAccountCleanupJob` (ST02, 15 min), `PriorityNotificationJob` / `NotificationOutboxJob` / `InAppNotificationJob` (ST20), `NotificationOutboxCleanupJob`, `NoShowJob` (ST05, `0 */5`), `AppointmentReminderJob` (ST03, `0 2/5`), `CashierShiftAutoCloseJob` (ST13, `0 4/5`), `PendingOrderDigestJob` (ST13, `0 50 23`), `BoardingNoShowJob` (ST05 boarding, `0 3/5`; `1/5` is kept for ST15) |

## Transactions, locking, concurrency — house style

Every write ADR follows these. Read the ADR of the neighbouring feature before writing a new flow.

- **One `@Transactional` per use case** on the service. No `noRollbackFor` except for a committed failure counter (OTP, login, current password); such exceptions are thrown directly inside that method, after its writes. Never `catch` inside the tx: it becomes rollback-only and fails later with `UnexpectedRollbackException`.
- **Facade without a tx** when work must happen outside it: BCrypt (never hold a connection or a row lock during it); an audit after rollback (403 hierarchy denial, failed login); a cache reload after commit; catching an error that must not leak (forgot password always returns 202).
- **Order inside a use case:** authorize (role, then ownership / scope on an immutable projection) → lock → read under the lock (never `findById` before locking: the persistence context returns the stale entity; lock with a native scalar query or a projection) → `now = clock.instant()` after the locks (truncate to µs when returned and re-read) → guards → write → `saveAndFlush` / `flush` (DB errors surface inside the call) → audit and outbox → map.
- `findByIdForUpdate` emits **`FOR NO KEY UPDATE`** (Hibernate 6). FK checks only take `FOR KEY SHARE`, which does **not** conflict with it: a module inserting a row that points at a staff member or a pet must first lock that row `FOR SHARE` (`PetQueryApi.findPetForShare`, `accounts … FOR SHARE` for visit assignment / shift opening).
- Put `@Transactional(MANDATORY)` on repository lock methods too; otherwise a lock called outside a tx locks and releases immediately in autocommit.
- **Lock orders (never reverse):**

  | Area | Order |
  |---|---|
  | Sessions | `accounts → sessions` (revoke via `@Modifying` queries in that order) |
  | OTP | `accounts → otp_tokens → customers` |
  | Staff admin | `branches (id ↑, only via BranchApi.lockForStaffChange) → accounts → staff_profiles → sessions`. Any flow lowering a branch's ACTIVE BRANCH_MANAGER count locks the branch first |
  | Customer | `accounts → customers (id ↑) → pets → weight_records` |
  | Appointment writes | `advisory(1001, pet) → branches (BranchApi.lockForBooking) → appointments`; advisory **1002** per customer (violation evaluation / restriction lift) always last |
  | Visit (ADR-0058; per feature 0059–0063, 0067–0069, 0074–0078) | prefix `accounts` (staff, `FOR SHARE`, id ↑; `StaffDirectoryApi.findStaffForShare`) → `pets FOR SHARE` → advisory **1004** (#19/#20 only) → `visits` (id ↑) → `appointments` (`AppointmentApi`) → `document_counters` (`visits` → `ORDER`) → `orders` (`VisitOrderApi`; lock before read, D045) → stock lots (`StockApi`). Each flow drops or adds steps (check-in has no `branches`, #14 and record writes stop at `visits`, #16/#17 have no lot, #17/#20 lock the child row after `visits`, #19 takes `products FOR SHARE` before `orders`): per-flow orders are in system-overview §3.2 and the ADR |
  | Sales | caller's document → `orders` (lock before read) → `products FOR SHARE` → stock lots; several orders by ascending id; #11 move to external purchase: `orders` → `prescription_items` (`PrescriptionApi`, never `visits`) |
| Cashier (ADR-0064) | open shift: `accounts FOR SHARE` (own) → INSERT shift · payment: shift `FOR SHARE` → all orders in **one** `ORDER BY id` statement → all lots in **one** statement (`StockApi.issueForPayment`) → `document_counters` last · close / reconcile: shift `FOR NO KEY UPDATE` · ST13: shift `SKIP LOCKED` |
  | Advisory namespaces | **1001** pet (appointment writes) · **1002** customer violation (always last) · **1003** customer feedback · **1004** pet vaccine dose (#19/#20) · next free **1005** |
  | Boarding (ADR-0065) | booking: `pets FOR SHARE` → `branches` (`BranchApi.lockForBooking`, shared with appointment booking and branch hours / holiday changes) → INSERT → `document_counters` · extend: `pets FOR SHARE` → `branches` → booking · cancel: booking → `evaluateAfterViolation` (advisory 1002 last) · kennel writes: `branches` → kennel · ST05: booking `SKIP LOCKED` → 1002 · clinic cancel: bookings by ascending id in batches of 100 |
  | Content (ADR-0071) | article create / move: `article_categories FOR SHARE` → `articles` · category delete: `article_categories FOR NO KEY UPDATE` → count `articles` · feedback submit: advisory **1003** (customer) → count → INSERT · feedback open: conditional `UPDATE … WHERE status = 'NEW'` · resolve: `feedbacks FOR NO KEY UPDATE` |
  | Inventory | caller's document → `stock_receipts` → `suppliers FOR SHARE` → `products FOR SHARE` → all lots in **one** `ORDER BY id FOR NO KEY UPDATE` → `StockLedger.apply` (the only writer of `stock_lots.quantity`) |
  | [CFG] edit | `system_configs` rows by ascending key, the key and its cross-key partner |

- **Isolation:** default READ COMMITTED. Paged reads (count + page) use `readOnly` + REPEATABLE READ. A write that re-reads after waiting for a lock (advisory or row) must stay READ COMMITTED — under REPEATABLE READ the re-read still sees the pre-lock snapshot (ADR-0061 M5: an assignment succeeded for a staff member transferred meanwhile).
- **Jobs:** keyset scan of ids (read-only, in the order of the index it uses) → **one record per tx** with `… FOR NO KEY UPDATE SKIP LOCKED` → re-check under the lock → conditional UPDATE (`WHERE status = …`, must hit 1 row) → outbox in the same tx; time budget ≤ 60 s. Long jobs pick a cron minute no other long job uses (ST20 HIGH needs a free thread).
- **Native SQL:** append only the conditions that were sent (never `:x IS NULL OR …` — it loses the index); keep status literals in the query so partial indexes match; bind `LocalTime` as `CAST(:t AS time)` from `toString()` (Hibernate truncates it to milliseconds otherwise).
- No `lock_timeout` is configured: a deadlock (40P01) → `PessimisticLockingFailureException` → 409.

## Persistence

- Schema follows erd §0: PK `BIGINT GENERATED ALWAYS AS IDENTITY` (1–1 tables reuse the FK as PK); FK `<singular>_id`, `ON DELETE RESTRICT`; ROOT / PART / REF tables have `created_at` + `updated_at`, LOG tables only `created_at` and are insert-only; money `BIGINT` VND, business dates `DATE` (Vietnam time), weight `NUMERIC(6,2)`; every enum column `VARCHAR(30)` + `CHECK`. Deliberate deviations: erd §13.
- **Flyway owns the schema** (`BE/src/main/resources/db/migration/`, V1 = the whole v16 schema, V2 = 52 [CFG] rows, then seed templates and indexes). **Never edit an applied migration** — not even a comment: the checksum changes and every DB that ran it fails to start.
- **Version numbers are shared with BE-2:** V1–V21 are taken (V13–V15 BE-2; V16 appointment sequence, V17–V18 appointment templates; V19 cashier templates + indexes, BE-2; V20 boarding ST05 index + clinic-cancel templates, BE-2; V21 page_contents seed, BE-2 — V17–V21 not yet confirmed with the other dev). **Agree on V22 before creating it.** A dev DB that ran the old `V13__appointment_code_sequence` needs `flyway repair` or `docker compose down -v` (ask first — it wipes data).
- **V16 checksum changed:** `571e0fe` removed conflict markers from `V16__appointment_code_sequence.sql` (comments only; `origin/dev` and `origin/manh` still carry the markers). A DB that ran V16 before that fails startup with *checksum mismatch for version 16*. There is no Flyway Maven plugin: after checking that `appointment_code_seq` matches the file, run `UPDATE flyway_schema_history SET checksum = -439054298 WHERE version = '16' AND checksum = -223559679` (what `repair` would write). Tell BE-2 before they merge.
- The first ADMIN is never seeded: create it with SQL — an `accounts` row (with `phone`) **plus** a `staff_profiles` row (`branch_id` NULL); template in system-overview §1. Don't add bootstrap code for it.

## Testing

- **Unit tests** (`*Test`, JUnit 5 + Mockito) for every service or handler that carries a rule or guard: `InOrder` for lock → guard → write order, and error branches write nothing.
- **ITs** (`*IT`, `@SpringBootTest`, `test` profile, `@Import(TestcontainersConfiguration.class)`, real Postgres, never H2) for every FSM and every complex API. FSM tests extend `FsmTransitionTestBase<S>` with an independent copy of the 03 table and **every** valid and invalid pair.
- Token in an IT: insert the account with `JdbcTemplate`, then `SessionService.open(...)` inside a `TransactionTemplate`.
- **Isolation between ITs:** each feature uses its own `MutableClock` era (year 2000 / 2001 / 2003…) and cleans only that era; rows get UUID emails or a per-test tag; `audit_logs` cannot be deleted (trigger) — tag rows with a random `reason` or narrow the time window. An IT that changes `system_configs` restores the V2 value and calls `SystemConfigService.reload()`; one that edits `notification_templates` restores `subject = default_subject, body = default_body`. An IT that inserts `prescription_items` or a medical record for an appointment-based visit deletes them itself before `AppointmentItSupport.cleanUp` (FK `RESTRICT`; `VaccinationItSupport.deleteDoses`).
- **Proven techniques (copy the template):** race = hold a row / advisory lock in another thread and wait on `pg_stat_activity` (`whileHeld` in `BookAppointmentIT`, `StaffRoleTransferIT`) · rollback on failure = temporary trigger rejecting an INSERT / UPDATE (`InAppNotificationIT`) · no connection during BCrypt = `HikariPoolMXBean.getActiveConnections() == 0` in a spy `PasswordEncoder` (`LoginIT`) · index proof = `EXPLAIN` on 20 000+ rows in a rolled-back tx + `ANALYZE` (`NoShowIT`) · no write = compare `xmin` (`BookingRestrictionIT`) · unbuilt module = `@Primary` SQL stand-in for the `api` interface (`JdbcPetCustomerModule`); an endpoint that does not exist yet = a `TransactionTemplate` that calls the `api` interface and JDBC in the contract's lock order (`PrescriptionIT`, sales #11, D048) · jobs = era data, failing batch, `SKIP LOCKED` vs a held row (`AppointmentReminderIT`) · SMTP = GreenMail :3025 (`NotificationOutboxIT`).

- **Mockito traps:** re-stub with `doThrow` / `doAnswer`, never `when(mock.call())`; `@MockitoSpyBean` cannot stub a CGLIB-proxied `@Transactional` bean — spy the repository or encoder instead; to call the real bean from a spy stub use `mockingDetails(spy).getMockCreationSettings().getDefaultAnswer()`.
- **Mutation testing is expected for concurrency / transaction guarantees** (remove a lock, add `noRollbackFor`, `REQUIRES_NEW`, `save` instead of `saveAndFlush`, change the isolation) — a test must turn red. Record the mutations in the ADR.

## Frontend notes

`FE/src/` is `app/` (`App.tsx` router + providers, `GlobalModal.tsx`) · `pages/<domain>/<Name>Page.tsx` (one file per route; `admin/`, `auth/`, `customer/`, `staff/`, `hotel/`, `news/`…) · `components/{admin,customer,staff,ui}/` · `shared/` (api, stores, components, hooks, types, utils, services, models). Server state → React Query; app state → Zustand store per domain. New page ⇒ add file + register the route in `src/app/App.tsx`. Tests: Vitest (`npm run test`, files `src/**/*.test.{ts,tsx}`), Playwright (`npm run test:e2e`).

The FE was built against the old spec — many routes (shop/cart/checkout/payment, membership, vouchers, caregivers, and admin tenants, promotions, refunds, incidents, workforce, AI…) cover features that are out of scope or tier 3 in v16. Check `docs/INDEX.md` before wiring a page to an API.

Known gaps:
- **HTTP:** one client `shared/api/axios.ts` (`VITE_API_URL`, default `:8081`; token from `shared/stores/session.store.ts`, no refresh token — 401 ends the session, 400 `BR-TK-17` sets `mustChangePassword`). Errors: `shared/api/api-error.ts` (`ApiError` = 6 envelope fields + `ruleId` from the end of `message`). `App.tsx` clears the React Query cache when the signed-in account changes.
- **Contract mock** `shared/api/mock/`: axios adapter answering `METHOD /api/path/:param` with `docs/api` envelopes; on in `npm run dev`, off in builds (`VITE_API_MOCK=false` in `FE/.env` → real BE). Demo accounts (staff carry `branchId`; `manager2@store2.vn` manages DRAFT branch 2), password `Petcare123`, every OTP `123456`. Manual test cases: `docs/testcases/`.
- **Staff workspaces** (reception, exam room, grooming) run on the contract APIs (`visit.api.ts`, `sales.api.ts`, `appointment.api.ts`, …; T33–T37), so they follow the contract mock like every other screen. `shared/api/clinic.api.ts` / **`clinic-db.ts`** (localStorage, rule ids of spec v13) are **no longer imported by anything that runs** (T49 moved the dashboard to the contract APIs and deleted `useClinicSync` / `useResetDemo`); the dead code (`useClinic.ts`, `clinic.api.ts`, `clinic-db.ts`; list in `docs/testcases/T37-fe.md` §B.6) goes at T57. The vaccination (visit-v1 #18–20) and prescription (#16–17) mocks were aligned with the real BE code on 2026-10-27 (T49, `docs/testcases/T49-fe.md` §C) but never run against a live BE.
- **Auth / routes:** 7 v16 roles in `shared/types/auth.ts`, one session for every role (`useSession`, `useAccount`). `RequireRole`: no session → login (`from`), `mustChangePassword` → `/auth/change-password`, wrong role → 403 in place. Customer `/pets`, `/booking`, `/review` sit behind `RequireLinkDecision` (BR-TK-19). Staff area = one `components/staff/StaffShell.tsx` for `/staff/*` (`WORKSPACE_ROLES`) and `/admin/*` (`AdminGuard` → `rolesForPath` from `ADMIN_MENU` in `shared/constants/staff-menu.ts`; `hidden: true` items stay routable). `/admin/catalog/:tab` SUPER_MANAGER only; `/admin/branches` SUPER_MANAGER (read-only schedule) + BRANCH_MANAGER (own branch). Profile: `shared/api/profile.api.ts`, `shared/hooks/useProfile.ts` (keys `['me']`). Form pieces: `components/admin/form-ui.tsx`; schedule helpers: `shared/utils/branch-schedule.ts`.
- Two `QueryClient` instances (`main.tsx`, `app/App.tsx`); the one in `App.tsx` wins.
- **Public pages (T46):** `shared/hooks/usePublicSite.ts` + mocks `public.mock.ts` / `content.mock.ts` serve `/api/public/*` (11 endpoints exist in the BE; prescriptions #16–17 exist since T38, ADR-0076–0078, but the FE mock has not been run against them). **Vitest flake (fixed in `src/test/intervals.ts`, wired in `setup.ts`):** `gsap.registerPlugin(ScrollTrigger)` runs at *module level* in `HeroBanner`, `HomePage`, `OurServicesStage`, `StackingProcessCards`, so any test that merely imports `<App />` starts GSAP's 250 ms `setInterval`; it outlives jsdom and throws "requestAnimationFrame is not defined", making vitest exit 1 with every test green (~1 full run in 4). `ScrollTrigger.killAll()` does **not** stop it (only `ScrollTrigger.disable()` does), so `setup.ts` now tracks every `setInterval` a test file creates and clears them in `afterAll`. Don't reintroduce per-file `killAll`; a single clean run proves nothing (run the suite several times). Detail: `docs/testcases/T46-fe.md` §C–D.
- **FE mock + styling gotchas (T47):** (1) mock state in `localStorage` must **merge defaults on load** (a stored state with the old shape throws on the first call of the new routes; `setup.ts` clears storage per test, so write a test that stores the old shape); mutations of admin data must `invalidateQueries({queryKey: ['public']})` (public queries have a 5 min `staleTime` and share the `QueryClient`). (2) Un-layered rules (`.table td`, `.input`, …) beat Tailwind v4 utilities: add the `!` suffix (`text-(--text-secondary)!`, `py-10!`) or the class silently does nothing. (3) Page-content JSON structure (Q1 of content-v1) is the **FE proposal** in `shared/constants/page-content.ts`, confirmed by the user on 27/10 but not yet recorded in the generated contract (regenerating needs Python) and BE-2 not yet told. Detail: `docs/testcases/T47-fe.md`.
- **Báo cáo + đối chiếu BE thật (T49):** `/admin/reports` (UC89) chạy mock không trạng thái `shared/api/mock/report.mock.ts` (BE chưa có `report`, T44; doanh thu mock = Order PAID thật + khoản cố định, #3–5 là dòng mẫu); `/admin` là dashboard trên API hợp đồng, 5 route ẩn cũ chuyển hướng theo vai trò. **Bẫy lớn nhất tìm được: BE thật KHÔNG trả `branchId` ở `POST /auth/login`** (chỉ `GET /me` → `staffProfile.branchId`) mà mock có: dùng `useStaffBranch()` / `useStaffBranchId()` (`shared/hooks/useStaffBranch.ts`), KHÔNG đọc `account.branchId` trực tiếp; `login` đã gộp giá trị này vào phiên. Mock che lỗi khi nó dễ dãi hơn BE (quyền `GET /staff`, không cắt trang, tên `entityType` audit, ngữ nghĩa PATCH vắng = giữ nguyên): khi viết mock mới, đọc controller + DTO của BE trước. Ngày "hôm nay" luôn `vnDate()` / `todayVn()` (`localIsoDate` nay cũng là giờ VN). Mặc định BE là `size=20` (đã thấy ở staff, accounts): danh sách không gửi `size` có thể chỉ hiện 20 dòng đầu trên BE thật (mock trả hết). Đã chạy FE với BE thật tối 27/10 (quét 34 trang × 5 vai trò + luồng chính tiếp nhận → khám → thu tiền; `T49-fe.md` §C.3–C.4, cách dựng lại ở §F): không lỗi chức năng ngoài 2 lỗi FE nhẹ. Docker Desktop tự bật lại container cũ của người dùng, trong đó `petcare-api` giữ cổng 8081: chạy BE của mình ở **8082** và đặt `VITE_API_URL` theo. Màn bị BE chặn: `T49-fe.md` §D.
- **Lưu trú (T48):** mock `shared/api/mock/boarding.mock.ts` (+`boarding.seed.ts`; đủ boarding-v1 #1–22, #1–12 chép BE thật, #13–22 là ASSUMPTION) đọc mock khác qua *đăng ký nguồn* (`registerBoardingSource` / `registerStaySource` / `registerCounterGuests`…) để khỏi import vòng; khách tại quầy của đặt chỗ mẫu (9001–9008) chỉ tra được theo id ở bàn thu tiền / chi tiết khách. Khách vào `/boarding-bookings[/new|/:id]` (danh sách / chi tiết ngoài `RequireLinkDecision`, đặt chỗ trong); nhân viên `/admin/boarding`, `/admin/kennels` (BRANCH_MANAGER). `Booking.activeOrderId` suy từ Order BOARDING chưa hủy; trả thú thu tiền ở bàn Lễ tân. `Modal` (`modal-templates`) nay có `aria-labelledby`; dialog mới dùng Radix `Dialog`. Responsive chưa làm (quyết định 27/10). Detail: `docs/testcases/T48-fe.md`.

## Gotchas

- **Rebases have committed conflict markers twice** (PR #14 → repair `49fd9a7`; T25 onto `dev` → cleanup 2026-10-20). After any rebase / merge run `git grep -n '^<<<<<<< \|^>>>>>>> '` and `mvn clean compile` before pushing. When both sides re-implemented the same thing, keep the owner's version (BE-2 for customer, BE-1 for appointment) — not both: two beans per interface or two methods with one name break the build.
- **ADR and Flyway numbers are global across branches.** Both branches have taken the same numbers before (ADR 0041–0044, 0047–0051; V13). Before taking a number, check `origin/dev` and the other dev's branch. When renumbering, add a **Đánh số lại** line to each file and update every reference (`git blame` tells which numbering a line was written with).
- **Large files:** `docs/02-business-rules.md` (~80 KB), `docs/06-module-contracts.md`, `docs/time.md` — read them by section (Grep the heading or rule id), never whole.
- **Port mismatch:** `mvn spring-boot:run` and axios default to 8081; the Vite proxy and Docker use 8080. Set `VITE_API_URL` consistently.
- **CI is broken for BE:** `.github/workflows/ci.yml` calls a Flyway Maven plugin that doesn't exist and triggers on `main, develop` (the working branch is `dev`); FE lint runs with `|| true`. Verify locally (`mvn clean verify` + docker smoke).
- **`SalesOrder` has no `@Version`** (D045): never read an Order of a visit before calling a locking `VisitOrderApi` method in the same tx — the stale entity overwrites `total_amount`. Lock first, read after.
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
5. **This file:** only when something system-wide changes — the state table, a lock order, a platform tool, a new gotcha, the next free V / ADR number. Keep feature history out of it.
6. Never `git add` / `commit` / `push` unless the user asks.
