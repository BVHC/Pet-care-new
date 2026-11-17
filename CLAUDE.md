# CLAUDE.md

Guidance for Claude Code in this repository. Loaded every session, so it holds only **what is true across the whole system**. Detail lives in the files of the *Documentation map* — read them on demand, by section. Per-feature code map: `docs/architecture/system-overview.md` §3.2.

## Project

Pet Care — management system for a chain of pet clinics: exam / vaccination, grooming, boarding, counter sales, cashier, inventory, customer care. Monorepo: `BE/` (Java 21, Spring Boot 3.5, Maven, PostgreSQL 17 + pgvector + Flyway) and `FE/` (React 18 + Vite + TypeScript). Redis 7 only backs the IP rate limiter of the public identity / AI endpoints (ADR-0100, 0110); SMTP is used by the notification worker (dev: Mailpit); the AI subsystem calls Gemini over HTTPS (`GEMINI_API_KEY`). Docs are Vietnamese; code and identifiers are English.

Team: **BE-1** (branch `manh`) = identity, appointment, visit, care · **BE-2** (branch `dung`) = branch, customer, catalog, sales, inventory, boarding, content, report, payment, shop · **FE-1** (Hiến) = all UI + the AI subsystem (module `ai`). PRs go to `dev`; `main` is the default branch. Phase 1 (05–30/10/2026, tier 1–2) is done; **phase 2 (tier 3 + AI) runs Nov–Dec** and was approved at T54 (5/11/2026).

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

**Scope tiers:** 1 = full, 2 = thin, 3 = specified at T54, being built in phase 2 (`docs/07`–`10`, `docs/api/*` phase-2 contracts). Phase-1 contracts and the list of tier-3 UC / ST are in `docs/INDEX.md`. Vietnamese enum values map to ASCII codes (erd §0, e.g. `TÁI_CHỦNG` → `VACCINE_DUE`, nhóm Khám/Tiêm → `MEDICAL`). Dropped in phase 2: complaints (ADR-0122), surgery (ADR-0125), home visit (ADR-0124 `bo-kham-tai-nha`) — their rule codes stay as gaps.

## Current state (2026-11-14)

<<<<<<< HEAD
<<<<<<< HEAD
Branch `manh` @ `c27bae3` (T77a, on top of AI-0 `4ea419e` and the `origin/dev` merge of 2026-11-06); check `git log` / `origin/dev` before relying on branch positions (`origin/dung` stops at ADR-0071 / V21). Flyway V59 (V49 spare for BE-2; V51 = complaints removed, T70 PR1, ADR-0131; V52 / V53 / V56 = AI switches and chat-log columns, T92 / T90 / T91, ADR-0136 / 0134 / 0135; V54 = `orders.version`, D045, ADR-0132; V55 = feedback status columns dropped, T70 PR2, renumbered from V52 on 15/11; V57 = AI `ai_chat_logs.intent` + `ai.chat.open_domain_enabled`, T92 part 2, ADR-0136; V58 = `online_orders.delivery_failure_reason`, task 126, BE-2, ADR-0146; V59 = AI `ai.ingest.max_file_kb` + `ai_documents.risk_level` + topic `SAFETY` / `LIFESTYLE`, T89, ADR-0147; next free V60). Phase 1 complete; 263 handler mappings (`@*Mapping` in `BE/src/main`: 220 documented + 43 in `branch` / `catalog`; `/resolve` removed; open: visit #21 T28b, care #9–10 D025). `OpenApiContractParityIT` pins **220** documented operations (13 annotated modules incl. `ai`, `fees`, `shop`; `branch` / `catalog` have none, D026). 84 [CFG] keys (`ConfigKey`; 85 before V51: −6 `complaint.*` +1 `feedback.retention_days` = 80, then +4 AI switches V52 / V53 / V56 / V57). Tests (class files, 2026-11-17): 378 `*Test` + `PetcareApplicationTests`, 233 `*IT`. V22 / V23 (and V24–V48) still await BE-2's confirmation of the numbers. Next for BE-1: phase 2 (T58+: Nhân sự, nội trú; task list `docs/time.md`). Next for FE-1: AI chat T77b.
=======
Branch `manh` @ `67bb006` = `origin/dev` `3acce4c` (AI chat T77b / T77c, T78, T82, AI v2 T88–T93) + 2 BE-1 commits (T70 `ff15931`, D045 `67bb006`) after a rebase on 2026-11-14; check `git log` / `origin/dev` before relying on branch positions (`origin/dung` stops at ADR-0071 / V21). Flyway V1–V48, V50, V51, **V52 ×2**, V53, V54 (V49 spare for BE-2; V51 = complaints removed, V52 = feedback status columns dropped, T70 PR1 / PR2, ADR-0131; **AI also took V52** `ai_general_knowledge_switch` and V53 `ai_business_intent_and_article_sync`; V54 = `orders.version`, D045, ADR-0132); **next free V55**. Phase 1 complete; 243 handler mappings (229 phase-1 + 14 `ai`; open: visit #21 T28b, care #9–10 D025). `OpenApiContractParityIT` pins 201 on `origin/dev` and 193 on the BE-1 side (content #22 removed); the file still has conflict markers, merged value expected 200 (`branch` / `catalog` have no annotations, D026). 82 `ConfigKey` constants (grep 2026-11-14). Tests (class files, 2026-11-14): 361 `*Test`, 221 `*IT`. V22 / V23 (and V24–V48) still await BE-2's confirmation of the numbers. Next for BE-1: phase 2 (T58+: Nhân sự, nội trú; task list `docs/time.md`). Next for FE-1: AI v2 (T88–T93) and T66 Nhân sự screens.
=======
Branch `manh` @ `67bb006` = `origin/dev` `3acce4c` (AI chat T77b / T77c, T78, T82, AI v2 T88–T93) + 2 BE-1 commits (T70 `ff15931`, D045 `67bb006`) after a rebase on 2026-11-14; check `git log` / `origin/dev` before relying on branch positions (`origin/dung` stops at ADR-0071 / V21). Flyway V1–V48, V50–V56 (V49 spare for BE-2; V51 = complaints removed, T70 PR1, ADR-0131; V52 `ai_general_knowledge_switch` and V53 `ai_business_intent_and_article_sync` = AI; V54 = `orders.version`, D045, ADR-0132; V55 = workforce `shift_assignments.source` + [CFG] `workforce.weekly_required_shifts`, T58, ADR-0123; V56 = feedback status columns dropped, T70 PR2, ADR-0131, renumbered from V52 on 2026-11-16, D069); **next free V57**. Phase 1 complete; 243 handler mappings (229 phase-1 + 14 `ai`; open: visit #21 T28b, care #9–10 D025). `OpenApiContractParityIT` pins 200 (201 on `origin/dev` minus content #22; confirmed 2026-11-16; `branch` / `catalog` have no annotations, D026). 83 `ConfigKey` constants (2026-11-16). Tests (class files, 2026-11-14): 361 `*Test`, 221 `*IT`. V22 / V23 (and V24–V48) still await BE-2's confirmation of the numbers. Next for BE-1: phase 2 (T58+: Nhân sự, nội trú; task list `docs/time.md`). Next for FE-1: AI v2 (T88–T93) and T66 Nhân sự screens.
>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069))

**Blockers before merging to `dev` (rebase of 2026-11-14, D069):**
1. ~~Unresolved conflict markers in 4 test files — `OpenApiContractParityIT`, `SchemaMigrationIT`, `SystemConfigIT`, `ConfigKeyTest` — so `mvn clean compile` is red. (`system-overview.md`, `docs/INDEX.md`, `docs/adr/INDEX.md` were cleaned on 2026-11-14.)~~ **Done 2026-11-16** (markers removed, `mvn clean compile` green).
2. ~~Two `V52__*.sql` files (BE-1 `drop_feedback_status_columns` vs AI `ai_general_knowledge_switch`): Flyway fails at startup. BE-1 renumbers its own; the new number is not chosen yet (V55 is next free, and D062 needs one too).~~ **Done 2026-11-16:** BE-1's file is now `V56__drop_feedback_status_columns.sql`; V55 went to D062 (workforce).
3. ADR 0131 and 0132 each have two files (AI vs BE-1): each side adds a **Đánh số lại** line. Next free ADR: **0139**.
After every rebase / merge run `git grep -n '^<<<<<<< \|^>>>>>>> '` over the whole repo, **including `BE/src/test`**, not only docs.
>>>>>>> 57d16e78 (docs: cập nhật api docs cho module nhân sự)

**Phase 2 so far:**
- **T55 (6/11) — schema + placeholders:** V34–V48 (ADR-0110, 0113, 0116, 0117), 8 + 7 + 18 new [CFG] keys, 40 tier-3 notification templates, and cross-module contract placeholders: `default` methods in `api` interfaces that throw `UnsupportedOperationException` ("Placeholder T55b / T55c") plus `@Component` classes `CashierShiftApiPlaceholder` (sales), `PaymentGatewayClientPlaceholder` (module `payment`, api only), `OnlineOrderApiPlaceholder` (module `shop`, api only). List: system-overview §3.3. When the real implementation lands: remove the `default` (the service overrides it) or delete the placeholder class + its `*Tier3PlaceholderTest`; exactly one bean per interface.
- **AI-0 (9/11, ADR-0129, Proposed):** FE-1 replaced the 15 read-only placeholders of I-1…I-8, I-10 (`PetQueryApi`, `CatalogQueryApi`, `BranchQueryApi`, `AppointmentQueryApi`, `VaccinationQueryApi`, `StockQueryApi`, `VisitQueryApi`, `BoardingQueryApi`, `OrderQueryApi`) with real code in BE-1 / BE-2 modules; BE-1 / BE-2 still confirm the assumptions. The other `default` placeholders (T63, T73, T74, T75) stay.
<<<<<<< HEAD
- **T77a (10/11, ADR-0130, Proposed):** module `ai` foundation — `GeminiClient`, knowledge management UC93 (ai-v1 #5–#11), FSM #13, ST22 indexing, ST23 chat-log cleanup. No chat yet (#1–2), no #3, #4, #12–13. No Java yet for workforce, inpatient, shop, payment.
- **Dropped in the spec, not yet in code:** surgery (ADR-0125): table `surgeries` (V37) and enum `SURGERY` (V41) stay dead until a later migration, the FE surgery screens were removed (T81a, 11/11), so D063 is half paid and only the migration that drops the table and the enum is open. Complaints (ADR-0122, ADR-0131): **V51 done (T70 PR1)** — tables, 6 `complaint.*` keys, 7 `COMPLAINT_*` templates and their Java constants removed, `feedback.retention_days` + 2 `feedbacks` indexes added. **V52 done (T70 PR2):** feedback is stateless and read-only (no status columns, no `/resolve`, OpenAPI pin 193). **ST24 `FeedbackCleanupJob` written (T70 PR3, BR-DG-05, ADR-0131):** hard-deletes feedback older than [CFG] `feedback.retention_days`, 03:30:31, batches of 500 with a 50 ms pause. Still open (D061): report note (PR4), FE (T81). **V53 was taken by AI v2 (T90); D062 has no code or migration yet.** None of V51, V52 or `FeedbackCleanupIT` has been run yet (Docker was off) — run them before merging.
- **FE:** T57-fe (`3a65850`) added mock + screens for the tier-3 modules. Since then the FE is wired to the real BE for `ai` (T82, 11/11), the shipping fee of `fees` (T80, 16/11) and `shop` #1–#18 (customer side T67 17/11, receptionist side T68 18/11); the other tier-3 modules and shop `cancel-failed` (#19, task 138) still run on the mock only.
=======
- **AI (FE-1, 10–14/11):** T77a foundation (ADR-0130: `GeminiClient`, UC93 ai-v1 #5–#11, FSM #13, ST22, ST23), T77b public chat #1 (ADR-0131), T77c personal chat #2 + vaccination summary #3 (ADR-0132), T78 recommendations #4 + item profiles #12–#13 (ADR-0133), T89 file import #14 (ADR-0134). ADR-0129…0133 Accepted 12/11. AI v2 (T88–T93, ADR-0134…0136 Proposed) is in progress: V52 / V53 of the AI side, [CFG] `ai.chat.general_knowledge_enabled`, `ai.chat.business_intent_enabled`. T82 wired the FE to the real `ai` BE; T81a removed the complaint and surgery screens. No Java yet for workforce, inpatient, shop, payment.
- **Workforce contract (14/11, ADR-0137, Proposed):** `workforce-v1` now has 17 endpoints (A06–A08 may read `GET /shift-definitions`; new `GET /me/shift-registrations/{weekStart}`; 409 before 400 on the 5 transition endpoints). No Java (T58–T59); the FE mock still has 14 (D062).
- **Dropped in the spec, not yet in code:** surgery (ADR-0125): table `surgeries` (V37) and enum `SURGERY` (V41) stay dead until a later migration, FE still has surgery screens (D063). Complaints (ADR-0122, ADR-0131): **V51 done (T70 PR1)** — tables, 6 `complaint.*` keys, 7 `COMPLAINT_*` templates and their Java constants removed, `feedback.retention_days` + 2 `feedbacks` indexes added. **V56 done (T70 PR2, renumbered from V52):** feedback is stateless and read-only (no status columns, no `/resolve`; OpenAPI pin 200 after the merge). **ST24 `FeedbackCleanupJob` written (T70 PR3, BR-DG-05, ADR-0131):** hard-deletes feedback older than [CFG] `feedback.retention_days`, 03:30:31, batches of 500 with a 50 ms pause. Still open (D061): report note (PR4), FE (T81). V53 is now taken by AI; D062 took V55 (written, not yet applied by an IT of the workforce module). V51, V56 and `FeedbackCleanupIT` ran green on 2026-11-16 (Testcontainers).
- **FE:** T57-fe (`3a65850`) added mock + screens for the tier-3 modules; they run on the mock only (the `ai` module is the exception since T82). FE debts to act on: *FE debts* below.
>>>>>>> 57d16e78 (docs: cập nhật api docs cho module nhân sự)

| Module | Owner | Built | Missing |
|---|---|---|---|
| identity | BE-1 | identity-v1 #1–39 (UC22 #25–27, assignable staff #38, refresh #39), ST01, ST02, jobs, `StaffDirectoryApi`, `AccountContactApi`; ADR-0089, 0096, 0097 still Proposed | — (D055: FE refresh wired in `3a65850`, not yet run against a live BE) |
| care | BE-1 | care-v1 #1–8, ST20 outbox, `CareTask` + FSM + `CareTaskApi`, ST19 + owner-transfer listeners | #9–10 (D025); D050 |
| appointment | BE-1 | appointment-v1 #1–9, ST03, ST05, clinic-cancel listeners | — (D027 accepted, 0092) |
| visit | BE-1 | visit-v1 #1–20, `VisitQueryApi`, `VaccinationQueryApi`, `PrescriptionApi`, `FollowUpApi`, ST04, ST18, lock listener | #21 (T28b); D046 |
| branch, catalog | BE-2 | branch-v1 #1–21, catalog-v1 #1–22 | Swagger annotations (D026) |
| customer, inventory, sales, boarding, content | BE-2 | customer-v1 #1–21, inventory-v1 #1–17, sales-v1 #1–21, boarding-v1 #1–22, content-v1 #1–22; ST05, ST08, ST13, ST15; ADR-0144: every availability figure subtracts `HELD`, `StockApi.lockAvailableQuantity` / `reserve`, `OnlineSalesOrderApi`, Order source `ONLINE`, `CustomerQueryApi.findAddress`; ADR-0145: `StockApi.release`, `OnlineSalesOrderApi.cancel` / `findLines`, `payment.api.PaymentTransactionApi` | `StockApi.consumeReservation` / `restock` (127, 138) |
| report | BE-2 | report-v1 #1–7 (no tables; the 5 `*ReportApi` live in their owner modules) | — |
<<<<<<< HEAD
| fees | BE-2 | fees-v1 #1–2 (shipping fee config + `ShippingFeeApi` ST09, ADR-0140) | #3–4 material norms |
| shop | BE-2 | shop-v1 cart (`getMyCart`, `setCartItem`, `removeCartItem`, `clearMyCart`; UC61, ADR-0143), checkout (`listFulfillmentBranches`, `quoteOnlineOrder`, `placeOnlineOrder`; UC62, ADR-0144), my online orders (`listMyOnlineOrders`, `getMyOnlineOrder`, `cancelMyOnlineOrder` — PENDING_PAYMENT only, `confirmOnlineOrderReceived`; UC64, ADR-0145), fulfilment (`listOnlineOrders`, `getOnlineOrder`, `startProcessingOnlineOrder`, `shipOnlineOrder`, `markOnlineOrderDelivered`, `failOnlineOrderDelivery`, `redeliverOnlineOrder`; UC65, V58, ADR-0146), ST10 + ST12, `OnlineOrderQueryApi` | paid cancel + refund (#13) and cancel after failed delivery (#10) (138), `OnlineOrderApi` real bean (T65) |
| returns | BE-2 | `ReturnRequestQueryApi.hasOpenRequestForOrder` only (ADR-0146) | UC68 / UC69 / ST21 (tasks 137, 138) |
| ai | FE-1 | ai-v1 #5–11 (UC93), FSM #13, ST22, ST23, `GeminiClient` (T77a) | chat #1–2 (T77b), #3, #4, #12–13 |

**Debts:** `docs/dept/INDEX.md` (65 entries, gitignored). Open on 2026-11-10: D058 (tier-3 placeholders), D059 (ADR / Flyway numbering unconfirmed), D020, D025, D026, D046, D050, D051, D057, D061, D062 (workforce self-registration, ADR-0123: spec done, no code), D063 (FE paid 11/11; only the migration dropping `surgeries` and `SURGERY` is open), D064 (inpatient contract changes, ADR-0124: no Java, FE mock behind), D065 (FE test that reads BE migrations went red after V51: reader fixed on 16/11, paid for the seed part; the rest of T81 stays open). D055 (FE refresh) is paid in code. D056 (Redis without password) is accepted (ADR-0115).
=======
| ai | FE-1 | ai-v1 #1–14 (public / personal chat, vaccination summary, recommendations, item profiles, UC93, file import), FSM #13, ST22, ST23, `GeminiClient` | AI v2 T88–T93 (ADR-0134…0136 Proposed) |
| workforce | BE-1 | contract `workforce-v1` 17 endpoints (ADR-0123, 0137), tables V36 + V55; Java foundation (T58 step 1, ADR-0138): entities, repositories, FSM #11 / #12 + tests, no endpoint yet | shift-definition CRUD (T58 step 2), assignments / requests / weekly registration (T59); FE mock still has 14 endpoints (D062) |

**Debts:** `docs/dept/INDEX.md` (69 entries, gitignored — **FE-1 and BE-2 never see it**; FE-relevant ones are copied below). Open on 2026-11-14: D058 (tier-3 placeholders), D059 (ADR / Flyway numbering unconfirmed), D020, D025, D026, D046, D050, D051, D057, D061 (partly paid), D062 (workforce self-registration, ADR-0123 + contract 17 endpoints: spec done, no code), D063, D064 (inpatient contract changes, ADR-0124: no Java, FE mock behind), D065 (FE test that reads BE migrations is red after V51), D066 (feedback test inserts duplicated), D067 (ST24 review), D068 (V51 has no precondition check), D069 (rebase fallout: markers in 4 tests and V52 ×2 fixed 2026-11-16; ADR 0131 / 0132 numbering still open). D055 (FE refresh) is paid in code. D056 (Redis without password) is accepted (ADR-0115).

**FE debts (reminder for FE-1; checked against the code on 2026-11-14):**

| Debt | State | What FE does |
|---|---|---|
| D062 · T66 Nhân sự | `FE/src/shared/api/mock/workforce.mock.ts` has #1–14 only: no `PUT` / `GET /me/shift-registrations`, no `GET /shift-weekly-summaries`, no `source`; the mock checks 400 before 409 (note W1) | Follow `docs/api/workforce-v1.md` (17 endpoints): A06–A08 call `GET /shift-definitions`; add the weekly-registration screen and the manager's weekly summary; **409 before 400** on cancel / absent / withdraw / approve / reject; label SWAP as "đổi sang ca khác"; the mock reads the quota (10) |
| D064 · inpatient | `shared/types/inpatient.ts` lacks `billedDays` / `estimatedAmount`; the mock keeps the old contract | Follow `docs/api/inpatient-v1.md` (ADR-0124) |
| D065 | `identity-admin.mock.test.ts` reads BE migrations and compares row by row; red since V51; the seed still has 6 complaint keys + 7 templates (and the AI keys of V52 / V53 are new) | Update seed + test (T81); not part of CI |
| D051 | `BookingPage.tsx` does not read `petId`, `branchId`, `serviceType` from the query, so reminder links open an empty form | Read the query and preselect pet / branch / vaccination service |
| D057 | `useNotifications.ts` `onError` only toasts when a notification was cleaned up (care-v1 #7 → 404) | `invalidateQueries` in `onError` |
| D055 | Refresh token is wired but never run ≥ 25 min against a live BE | Run it by hand, then close |
| D061 / D063 (FE side) | T81a removed the screens; `complaint` / `surgery` leftovers remain in seed / mock / types (`identity-admin.seed.ts`, `catalog.mock.ts`, `types/index.ts`) | Re-check and clean; keep the `SURGERY` value of `catalog.mock` while the BE enum (V41) still has it |

Rule for every mock: it must be **no more lenient than the BE** and follow the contract's error order (`docs/api/00-method.md` §3.5, plus the per-feature order in its ADR).
>>>>>>> 57d16e78 (docs: cập nhật api docs cho module nhân sự)

**Tests baseline:** last full run 2026-11-05 on merged tree `3163f22` (every unit test + every IT group in an isolated copy): 5550 unit + 2690 IT, 5 red; re-checked green alone on 2026-11-07. Known flaky when the whole suite runs: `AuditLogViewIT.queriesUseExpectedIndex` (table statistics, D021), `ReminderScanIndexIT` (red only inside the visit group). Everything added since (T55 schema ITs, `*Tier3PlaceholderTest`, AI-0, T77a `ai` tests) is outside that run. A full `mvn clean verify` gets killed by low host memory — run ITs in groups. History: `git log`, ADRs, `docs/testcases/`.

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

Surefire and Failsafe pin `-Duser.timezone=Asia/Ho_Chi_Minh`; tests use real Flyway migrations with `ddl-auto=validate`. **AI config (env only, ADR-0109 / 0130):** `GEMINI_API_KEY` (never in a file or log; blank ⇒ BE still starts, an AI call fails with `NO_KEY`), `AI_PROVIDER` (`gemini` | `off`), `AI_LLM_MODEL`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_DIM` (768, fixed by `vector(768)`), `AI_INDEXING_ENABLED` (off in the `test` profile). AI spike scripts: `BE/scripts/spike/` (Node, not part of the build).

## Documentation map

Never invent a rule, transition, model, column or enum value. When something is missing, record it as an assumption or TBD (ADR, debt file, or contract §E). Spec files: start at `docs/INDEX.md`, which maps every module to exact line ranges.

| Need | File |
|---|---|
| Business rules `BR-<MODULE>-<n>` — **priority 1** on conflict | `docs/02-business-rules.md` |
| State machines, numbered transitions (`Visit#5`) — priority 2 | `docs/03-state-machines.md` |
| Domain model (ROOT / PART / REF / LOG) — priority 3 | `docs/04-domain-model.md` |
| ERD (55 tables in v16; 86 in `SchemaMigrationIT.ERD_TABLES`: v16 + 4 AI + tier-3 BE-1 / BE-2 tables, complaints removed at V51), enum codes, decision log §13 — priority 4 | `docs/05-erd.md` |
| Actors, UC01–UC94, ST01–ST24 (must match the rules) | `docs/01-business-operations.md` |
| Phase 2 specs (approved T54): AI · BE-2 part · BE-1 part · T54 record + T57 screen inventory · payment sandbox guide | `docs/07-ai-subsystem.md` · `docs/08-tang3-be2-spec.md` · `docs/09-tang3-be1-spec.md` · `docs/10-t54-t57-checklists.md` · `docs/payment-gateway-setup.md` |
| BE ↔ BE contracts (`module/<m>/api`), who calls what, delivery dates; tier-3 interfaces §6b | `docs/06-module-contracts.md` |
| FE ↔ BE HTTP contracts (phase 1: 12 modules, 231 endpoints; phase 2: 12 modules, 115 endpoints, only `ai` #1–14 built; 346 in total by the yaml count of 2026-11-14) — **generated** from `docs/api/generator/m_<module>.py`, never hand-edit | `docs/api/*-v1.{md,yaml}` |
| What exists in code per feature (§3), placeholders (§3.3), migrations (§4b) | `docs/architecture/system-overview.md` |
| Which endpoints the BE has vs. which FE screens can be wired | `docs/be-fe-integration.md` |
| Why a technical decision was made (ADR list by area: `docs/INDEX.md` §5) | `docs/adr/INDEX.md` → `docs/adr/00nn-*.md` |
| Known code debt (gitignored, local only) | `docs/dept/INDEX.md` → `D<nnn>-*.md` |
| Task status / deadlines | `docs/time.md`, `docs/timeline_petcare_v15.xlsx` |
| Locking / transaction rules and lock orders · IT isolation registry · FE traps and mock rules (moved out of this file) | `docs/architecture/{concurrency-locking,testing-notes,frontend-notes}.md` |
| Backend conventions 01–10 (+ open TBDs) · manual FE test cases · task plans | `docs/convention/backend/` · `docs/testcases/` · `docs/plan/` |

Module codes: TK, QT, CN, CK, KH, SP, NS, LH, TN, KB, LT, BH, TG, KO, BV, DG, TB, BC, AI.

Open convention TBDs (don't decide silently — ask or write an ADR): command names when no Actor↔Command table exists; how "warn, don't block" rules return warnings (contracts assume a named field in `data`, e.g. `stockWarning`); locking for kennel capacity.

<<<<<<< HEAD
<<<<<<< HEAD
**ADRs** (`docs/adr/`, immutable once Accepted — supersede instead of editing; `docs/adr/INDEX.md` is the full list). **Next free: 0148.** Reserved: 0118–0120 (BE-2), 0126–0128 (renumbering of the duplicates below). Phase 2 so far: 0108–0110 AI / pgvector, 0113 / 0116 / 0117 tier-3 schema + placeholders, 0121 CI, 0122 drop complaints, 0123 workforce self-registration, 0124 inpatient, 0125 drop surgery, 0129 AI-0, 0130 T77a, 0141 / 0142 AI chat T77b / T77c (were 0131 / 0132 until 16/11), 0143 shop cart (task 116, was 0141 until 17/11), 0144 online order placement (task 117), 0145 my online orders (task 125), 0146 fulfilment + ST10 / ST12 (task 126). **Numbers used twice:** 0088 (BE-2 `inventory-expiring-soon-flag` vs BE-1 `n5-unlock-flag-…`), 0123 (FE refresh token vs workforce), 0124 (`bo-kham-tai-nha` vs inpatient), 0122 (AI spike T56 vs drop complaints). 0131 / 0132 were used twice too (AI chat vs T70 / D045): the **AI** ADRs moved to 0141 / 0142 on 16/11, because the BE-1 ADRs are cited in the comments of the migrations V51 / V54 / V55, which must not change (checksum). Renumbering is each owner's job: add a **Đánh số lại** line to both files and fix every reference.
=======
**ADRs** (`docs/adr/`, immutable once Accepted — supersede instead of editing; `docs/adr/INDEX.md` is the full list). **Next free: 0138.** Reserved: 0118–0120 (BE-2), 0126–0128 (renumbering of the duplicates below). Phase 2 so far: 0108–0110 AI / pgvector, 0113 / 0116 / 0117 tier-3 schema + placeholders, 0121 CI, 0122 drop complaints, 0123 workforce self-registration, 0124 inpatient, 0125 drop surgery, 0129–0133 AI (Accepted 12/11), 0134–0136 AI v2 (Proposed), 0137 workforce contract (Proposed). **Numbers used twice:** 0088 (BE-2 `inventory-expiring-soon-flag` vs BE-1 `n5-unlock-flag-…`), 0122 (AI spike T56 vs drop complaints), 0123 (FE refresh token vs workforce), 0124 (`bo-kham-tai-nha` vs inpatient), **0131** (AI public chat vs BE-1 drop complaints V51), **0132** (AI personal chat vs BE-1 `orders.version`). Renumbering is each owner's job: add a **Đánh số lại** line to both files and fix every reference.
>>>>>>> 57d16e78 (docs: cập nhật api docs cho module nhân sự)
=======
**ADRs** (`docs/adr/`, immutable once Accepted — supersede instead of editing; `docs/adr/INDEX.md` is the full list). **Next free: 0139.** Reserved: 0118–0120 (BE-2), 0126–0128 (renumbering of the duplicates below). Phase 2 so far: 0108–0110 AI / pgvector, 0113 / 0116 / 0117 tier-3 schema + placeholders, 0121 CI, 0122 drop complaints, 0123 workforce self-registration, 0124 inpatient, 0125 drop surgery, 0129–0133 AI (Accepted 12/11), 0134–0136 AI v2 (Proposed), 0137 workforce contract (Proposed). **Numbers used twice:** 0088 (BE-2 `inventory-expiring-soon-flag` vs BE-1 `n5-unlock-flag-…`), 0122 (AI spike T56 vs drop complaints), 0123 (FE refresh token vs workforce), 0124 (`bo-kham-tai-nha` vs inpatient), **0131** (AI public chat vs BE-1 drop complaints V51), **0132** (AI personal chat vs BE-1 `orders.version`). Renumbering is each owner's job: add a **Đánh số lại** line to both files and fix every reference.
>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069))

## Backend architecture

```
com.petcare/
├── platform/         # cross-cutting infra; must NOT import module/*
│                     # audit, config, exception, fsm, model (envelopes), security, sequence
└── module/<m>/       # api/ controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/] [job/] [listener/]
```

Modules: identity, care, appointment, visit, workforce (BE-1; `workforce` has no endpoint yet) · branch, customer, catalog, sales, inventory, boarding, content, report, payment, shop (BE-2; `payment` and `shop` hold only an `api/` + a placeholder) · ai (FE-1; also has `llm/`).

- **Module boundary:** a module imports only another module's `api/` package (interfaces, records, enums, events) — never its `entity`, `repository` or internal `service`. `ai` only reads other modules through `*QueryApi` (07 §11).
- **1–1 consequence** = interface call inside the caller's transaction (implementation `@Transactional(MANDATORY)`; the caller never catches its exceptions).
- **Fan-out consequence** = synchronous event (`ApplicationEventPublisher` + `@EventListener` in `module/<m>/listener/`, delegating to a `MANDATORY` service). Not `AFTER_COMMIT`, no `@Async`, no `REQUIRES_NEW`, no swallowed exceptions. A listener failure rolls back the publisher. Events: `AccountLockedEvent` / `AccountUnlockedEvent`, `BranchClinicCancellationEvent`, `PetDeceasedEvent`, `PetOwnerTransferredEvent`, `StaffLeftBranchEvent` and `CustomerMergedEvent` (tier 3, no listener yet). **One documented exception:** `ai` ST22 triggers indexing `AFTER_COMMIT` on a background thread (`AiIndexRequested`, ADR-0130), because embedding calls must not run inside a transaction.
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
- **FSM:** enum + `{Entity}TransitionHandler extends StateMachineBase<S>`. `allowedTransitions()` is copied **exactly** from 03 (tier 3: from the specialist file) — never add an edge by inference. Rule guards run before `validateTransition()`. Article (BR-BV-02) and Feedback (BR-DG-04) are rule-only lifecycles. The account lock is the `is_locked` flag, independent of `status`.
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
| Swagger | springdoc. Each handler gets `@Operation` copied verbatim from `docs/api/openapi/<m>-v1.yaml`, plus `@ApiErrorDoc` per 4xx and `@Parameter`; every DTO field gets `@Schema(description, example)`. Never write `security` / 401 / 500. `OpenApiContractParityIT` must stay green — it pins the number of documented endpoints (**200**; a new endpoint changes the pin; ADR-0040, convention 10) |
| JSON | A float into an integer field → 400 `MALFORMED_REQUEST` (ADR-0032) |
| Rate limit (ADR-0100) | `platform/security/RateLimitFilter` (after `CorsFilter` in `SecurityConfig`, **not** a bean) counts `POST` `login` / `register*` / `password/*` per IP (`getRemoteAddr()`, IPv6 by /64) in `RedisRateLimiter` (Lua fixed window); over the limit → `TooManyRequestsException` → 429 + `Retry-After` before BCrypt, DB and audit. Redis down → fail-open with a circuit-breaker. Config `app.rate-limit.*` / `RATE_LIMIT_*` (not [CFG]); **off in the `test` profile**, only `RateLimitIT` turns it on. A new public path that should be throttled goes into the filter's matchers. **AI group (ADR-0110, BR-AI-09):** the filter already matches `POST /api/public/ai/chat` + `/api/me/ai/chat` with two windows on the same IP — `rl:ai` (10 / 60 s) then `rl:ai-hour` (60 / 3600 s); a request refused by the minute window does not spend hour budget; the endpoints themselves are T77b. `RateLimitProperties` is a record: adding a group changes its constructor |
| Jobs | `module/<m>/job/{Name}Job` with `@Scheduled(cron = "${app.jobs.<name>.cron}", zone = BUSINESS_ZONE_ID)`. Not `@Transactional`; catch and log `<NAME>_FAILED`, never rethrow; MDC `traceId`; cron `"-"` in `application-test.yml`; cron as an env var in `docker-compose.yml` / `.env.example`. Pool = 4 threads, single instance. Existing (cron): `SessionCleanupJob` (03:00), `RefreshTokenCleanupJob` (03:10:11), `OtpTokenCleanupJob` (03:20:17), `FeedbackCleanupJob` ST24 (03:30:31), `NotificationOutboxCleanupJob` (03:40), `NotificationCleanupJob` (03:50:23), `PendingAccountCleanupJob` ST02 (`*/15`), `PriorityNotificationJob` / `NotificationOutboxJob` / `InAppNotificationJob` ST20, `NoShowJob` ST05 (`0 */5`), `AppointmentReminderJob` ST03 (`0 2/5`), `CashierShiftAutoCloseJob` ST13 (`0 4/5`), `PendingOrderDigestJob` ST13 (`0 50 23`), `BoardingNoShowJob` (`0 3/5`), `BoardingOverdueJob` ST15 (`0 1/5`), `StockAlertDigestJob` ST08 (`0 0 7`), `VaccineReminderJob` / `FollowUpReminderJob` ST04 (hourly 07–20h, second 30, minute 10 / 40), `VaccineOverdueJob` ST18 (same hours, minute 25), `KnowledgeIndexJob` ST22 (`45 */5 * * * *`), `ChatLogCleanupJob` ST23 (`29 0 4 * * *`), `OnlineOrderPaymentExpiryJob` ST10 (`20 6/15 * * * *`), `OnlineOrderAutoConfirmJob` ST12 (`40 12 * * * *`) |

## Transactions, locking, concurrency — digest

Full rules, the **lock-order table per area** and the advisory namespaces (next free **1005**): `docs/architecture/concurrency-locking.md`. **Read it before writing or changing any write flow, job or lock**, plus the ADR of the neighbouring feature.

- One `@Transactional` per use case; never `catch` inside it (rollback-only → `UnexpectedRollbackException`); `noRollbackFor` only for a committed failure counter (OTP, login, current password). BCrypt, an audit after rollback, a cache reload after commit run in a facade **outside** the tx. Calls to an external provider (Gemini) never run inside a tx either.
- Order: authorize → lock → read **under** the lock (never `findById` before locking) → `clock.instant()` → guards → write → `saveAndFlush` → audit + outbox → map.
- `findByIdForUpdate` = `FOR NO KEY UPDATE`; FK checks take only `FOR KEY SHARE`, which does **not** conflict, so a module inserting a row that points at a staff member or pet first locks it `FOR SHARE`. Repository lock methods are `@Transactional(MANDATORY)`.
- Lock orders are fixed per area and never reversed (sessions, OTP, staff admin, customer, appointment, visit, sales, cashier, online order, boarding, content, care task, inventory, [CFG] edit, branch / catalog edit, ai document: `docs/architecture/concurrency-locking.md`).
- Isolation: READ COMMITTED; paged reads `readOnly` + REPEATABLE READ; a write that re-reads after a lock wait stays READ COMMITTED.
- Jobs: keyset scan → one record per tx with `FOR NO KEY UPDATE SKIP LOCKED` → re-check → conditional UPDATE (must hit 1 row) → outbox; ≤ 60 s.
- Native SQL: append only the conditions that were sent; keep status literals; bind `LocalTime` as `CAST(:t AS time)`. No `lock_timeout`: a deadlock → 409.

## Persistence

- Schema follows erd §0: PK `BIGINT GENERATED ALWAYS AS IDENTITY` (1–1 tables reuse the FK as PK); FK `<singular>_id`, `ON DELETE RESTRICT`; ROOT / PART / REF tables have `created_at` + `updated_at`, LOG tables only `created_at` and are insert-only; money `BIGINT` VND, business dates `DATE` (Vietnam time), weight `NUMERIC(6,2)`; every enum column `VARCHAR(30)` + `CHECK`. Deliberate deviations: erd §13.
- **Flyway owns the schema** (`BE/src/main/resources/db/migration/`; per-migration table in system-overview §4b). **Never edit an applied migration** — not even a comment: the checksum changes and every DB that ran it fails to start. `ai_chunks.embedding` (`vector(768)`) is deliberately not mapped in the entity (ADR-0130): write with native `CAST(? AS vector)`.
<<<<<<< HEAD
<<<<<<< HEAD
- **Version numbers are shared with BE-2 and not all confirmed with the other dev** (V22–V56; note in 06 §6): V1 = whole v16 schema, V2 = [CFG] seed, V3–V33 phase 1, V34–V35 AI (FE-1, T55a), V36–V41 BE-1 tier-3 schema, V42 `orders` source `ONLINE` (BE-2's slot, done by BE-1), V43–V48 BE-2 tier-3 schema, **V50** = index `refresh_tokens (expires_at, id)` (BE-1, ADR-0114) while **V49 stays unused** — kept spare for ADR-0117 TBD-1 / TBD-2: a DB that already ran V50 fails Flyway validation when V49 arrives (`out-of-order` is off), so BE-2 must tell BE-1 first or use a later number. **V51** = T70 PR1 (ADR-0131), **V52** = AI switch `ai.chat.general_knowledge_enabled` (T92), **V53** = AI `external_id` / `source_type` / `ai.chat.business_intent_enabled` (T90), **V54** = `orders.version` (BE-1 for BE-2, ADR-0132, awaits BE-2), **V55** = T70 PR2 feedback status columns (renumbered from V52 on 15/11, V52 was taken), **V56** = AI `used_pet_context` + `ai.chat.pet_context_enabled` (T91, ADR-0135; renumbered from V55 on 16/11); **V57** = AI `ai_chat_logs.intent` + `ai.chat.open_domain_enabled` (T92 part 2), **V58** = `online_orders.delivery_failure_reason` (task 126, ADR-0137 D2), **V59** = AI ingest limit + `risk_level` + two topics (T89, ADR-0147); **next free: V60** (V49 only by agreement with BE-2); beyond V59 ask. A DB that already ran V52 / V53 without V51 (the AI demo DB 5433 until 16/11) needs `SPRING_FLYWAY_OUT_OF_ORDER=true` once; never set it permanently. Before taking any number check `origin/dev` and the other dev's branch.
=======
- **Version numbers are shared with BE-2 and not all confirmed with the other dev** (V22–V54; note in 06 §6): V1 = whole v16 schema, V2 = [CFG] seed, V3–V33 phase 1, V34–V35 AI (FE-1, T55a), V52–V53 AI v2 (FE-1), V36–V41 BE-1 tier-3 schema, V42 `orders` source `ONLINE` (BE-2's slot, done by BE-1), V43–V48 BE-2 tier-3 schema, **V50** = index `refresh_tokens (expires_at, id)` (BE-1, ADR-0114) while **V49 stays unused** — kept spare for ADR-0117 TBD-1 / TBD-2: a DB that already ran V50 fails Flyway validation when V49 arrives (`out-of-order` is off), so BE-2 must tell BE-1 first or use a later number. **V51** = T70 PR1 (ADR-0131), **V52 exists twice** — BE-1 `drop_feedback_status_columns` (T70 PR2) and AI `ai_general_knowledge_switch` (T92, ADR-0136): Flyway fails, BE-1 renumbers (D069) — **V53** = AI `ai_business_intent_and_article_sync` (T90; no longer reserved for D062), **V54** = `orders.version` (BE-1 for BE-2, ADR-0132, awaits BE-2); **next free: V55** (V49 only by agreement with BE-2); beyond V51 ask. Before taking any number check `origin/dev` and the other dev's branch.
>>>>>>> 57d16e78 (docs: cập nhật api docs cho module nhân sự)
=======
- **Version numbers are shared with BE-2 and not all confirmed with the other dev** (V22–V56; note in 06 §6): V1 = whole v16 schema, V2 = [CFG] seed, V3–V33 phase 1, V34–V35 AI (FE-1, T55a), V52–V53 AI v2 (FE-1), V36–V41 BE-1 tier-3 schema, V42 `orders` source `ONLINE` (BE-2's slot, done by BE-1), V43–V48 BE-2 tier-3 schema, **V50** = index `refresh_tokens (expires_at, id)` (BE-1, ADR-0114) while **V49 stays unused** — kept spare for ADR-0117 TBD-1 / TBD-2: a DB that already ran V50 fails Flyway validation when V49 arrives (`out-of-order` is off), so BE-2 must tell BE-1 first or use a later number. **V51** = T70 PR1 (ADR-0131), **V52** = AI `ai_general_knowledge_switch` (T92, ADR-0136) — **V53** = AI `ai_business_intent_and_article_sync` (T90; no longer reserved for D062), **V54** = `orders.version` (BE-1 for BE-2, ADR-0132, awaits BE-2); **V55** = workforce `shift_assignments.source` + [CFG] `workforce.weekly_required_shifts` (BE-1, T58, ADR-0123), **V56** = `drop_feedback_status_columns` (BE-1, T70 PR2, ADR-0131; was V52, renumbered 2026-11-16, D069); **next free: V57** (V49 only by agreement with BE-2; BE-2 must start at V57); beyond V51 ask. Before taking any number check `origin/dev` and the other dev's branch.
>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069))
- **V16 checksum changed:** `571e0fe` removed conflict markers from `V16__appointment_code_sequence.sql` (comments only). A DB that ran the old V16 fails startup with *checksum mismatch for version 16*. No Flyway Maven plugin exists: after checking that `appointment_code_seq` matches, run `UPDATE flyway_schema_history SET checksum = -439054298 WHERE version = '16' AND checksum = -223559679`. Tell BE-2 before they merge.
- The first ADMIN is never seeded: create it with SQL — an `accounts` row (with `phone`) **plus** a `staff_profiles` row (`branch_id` NULL; without it `GET /api/me` is 500); template in system-overview §1. Don't add bootstrap code for it.

## Testing

- **Unit tests** (`*Test`, JUnit 5 + Mockito) for every service or handler that carries a rule or guard: `InOrder` for lock → guard → write order, and error branches write nothing.
- **ITs** (`*IT`, `@SpringBootTest`, `test` profile, `@Import(TestcontainersConfiguration.class)`, real Postgres, never H2) for every FSM and every complex API. FSM tests extend `FsmTransitionTestBase<S>` with an independent copy of the 03 table and **every** valid and invalid pair. One `*FsmIT` per FSM on real beans (no `@MockitoBean`). Each placeholder has a `*Tier3PlaceholderTest` (`PlaceholderAssert`); `Tier3Be2ContractWiringIT` checks the wiring. `ai` ITs use `AiItSupport` + `FakeEmbeddingClient` (never the real Gemini).
- Token in an IT: insert the account with `JdbcTemplate`, then `SessionService.open(...)` inside a `TransactionTemplate`; sessions expire on the same `Clock`, so reopen tokens after moving a `MutableClock` across days (else 401).
<<<<<<< HEAD
- **Isolation between ITs** (era registry, cleanup rules, proven techniques: `docs/architecture/testing-notes.md`): each IT uses its own `MutableClock` era and cleans only that era. A job whose scan has no lower bound (ST02, ST05, ST13, ST15, ST18 overdue, cleanup jobs) touches every older era, so an IT running it takes an era **older than all others** (next **1978**); an IT without such a job takes a **future** era (next **2052**). Rows get UUID emails or a per-test tag; `audit_logs` rows are tagged by a random `reason`; an IT that changes `system_configs` restores the V2 value and calls `SystemConfigService.reload()`.
=======
- **Isolation between ITs** (era registry, cleanup rules, proven techniques: `docs/architecture/testing-notes.md`): each IT uses its own `MutableClock` era and cleans only that era. A job whose scan has no lower bound (ST02, ST05, ST13, ST15, ST18 overdue, cleanup jobs) touches every older era, so an IT running it takes an era **older than all others** (next **1980**); an IT without such a job takes a **future** era (next **2045**). Rows get UUID emails or a per-test tag; `audit_logs` rows are tagged by a random `reason`; an IT that changes `system_configs` restores the V2 value and calls `SystemConfigService.reload()`.
>>>>>>> 20940d65 (feat: T58: nền module Nhân sự (V55, ADR-0138) + đổi V52 feedback thành V56, gỡ conflict marker (D069))
- **Mockito traps:** re-stub with `doThrow` / `doAnswer`, never `when(mock.call())`; `@MockitoSpyBean` cannot stub a CGLIB-proxied `@Transactional` bean — spy the repository or encoder instead; to call the real bean from a spy stub use `mockingDetails(spy).getMockCreationSettings().getDefaultAnswer()`.
- **Mutation testing is expected for concurrency / transaction guarantees** (remove a lock, add `noRollbackFor`, `REQUIRES_NEW`, `save` instead of `saveAndFlush`, change the isolation) — a test must turn red. Record the mutations in the ADR.

## Frontend (digest — details and traps: `docs/architecture/frontend-notes.md`; read it before writing a screen, hook or mock)

`FE/src/`: `app/` (`App.tsx` router + providers) · `pages/<domain>/<Name>Page.tsx` · `components/{admin,customer,staff,ui}/` · `shared/` (api, stores, components, hooks, types, utils, constants). Server state → React Query; app state → Zustand store per domain. New page ⇒ add the file + register the route in `src/app/App.tsx`. Tests: Vitest, Playwright. Check `docs/INDEX.md` before wiring a page: much of the FE was built against the old spec.

- **HTTP:** one client `shared/api/axios.ts` (`VITE_API_URL`, default `:8081`). Access token 10 min renewed by `refreshSession()` (D055, ADR-0091): user requests and real clicks refresh when < 5 min is left; polls pass `POLL` and never refresh. Errors: `ApiError` (`shared/api/api-error.ts`, 6 envelope fields + `ruleId`). 400 `BR-TK-17` sets `mustChangePassword`.
- **Contract mock** `shared/api/mock/` (on in `npm run dev`; `VITE_API_MOCK=false` in `FE/.env` → real BE). Demo password `Petcare123`, every OTP `123456`. Tier-3 modules (T57) started **mock-only**; `ai` (T82), the shipping fee of `fees` (T80) and `shop` #1–#18 (T67, T68) now go to the real BE in mixed mode (`VITE_API_MOCK_ONLY` in `FE/.env.example` keeps one rule per path the BE still lacks, e.g. shop `cancel-failed`). The modules still on the mock have an error order that is not measured against a real BE and differs between mocks (shop's was measured and fixed on 18/11). A mock must be **no more lenient than the BE** — read the BE controller + DTO before writing one. Mock state in `localStorage` must merge defaults on load.
- **Real-BE traps (T49):** `POST /auth/login` returns **no `branchId`** — use `useStaffBranch()` / `useStaffBranchId()`, never `account.branchId`. "Today" is always `vnDate()` / `todayVn()`. BE lists default to `size=20`. Docker Desktop restarts the user's old `petcare-api` on 8081: run your BE on **8082** and set `VITE_API_URL`.
- **Styling / tests:** un-layered rules (`.table td`, `.input`, …) beat Tailwind v4 utilities — add the `!` suffix. Importing `<App />` in a test starts GSAP intervals that make vitest exit 1 with all tests green (~1 run in 4); `src/test/intervals.ts` (wired in `setup.ts`) clears them — don't reintroduce `ScrollTrigger.killAll()`, and run the full suite several times.
- **Auth / routes:** 7 roles (`shared/types/auth.ts`); `RequireRole`; customer `/pets`, `/booking`, `/review` sit behind `RequireLinkDecision` (BR-TK-19); staff area = `StaffShell` (`/staff/*`, `/admin/*`, menu in `shared/constants/staff-menu.ts`). `App.tsx` route `*` → `NotFoundPage`. Dead code removed at T57 Bước C (7/11); still unused but kept: `AdminPurchasingPage`, `AdminVaccinesPage`, `notifications.store.ts`.

## Gotchas

- **Rebases have committed conflict markers eight times** (7th: BE-1 commit `8f8542d` put markers in `CLAUDE.md` and reached `origin/dev` via PR #30; 8th: the rebase of 2026-11-14 left them in 3 docs and 4 test files, D069). After any rebase / merge run `git grep -n '^<<<<<<< \|^>>>>>>> '` over the whole repo (tests too) and `mvn clean compile` before pushing; when markers are removed by hand, `git add` every cleaned file. When both sides re-implemented the same thing, keep the owner's version (BE-2 for customer, BE-1 for appointment) — two beans per interface or two methods with one name break the build.
- **ADR and Flyway numbers are global across branches** and have collided before (ADR 0041–0044, 0047–0051, 0088, 0091–0092, 0123–0124; V13). Check `origin/dev` and the other dev's branch first.
- **Large files:** `docs/02-business-rules.md` (~80 KB), `docs/06-module-contracts.md`, `docs/time.md` (~140 KB), `docs/05-erd.md` (1281 lines) — read by section (Grep the heading or rule id), never whole.
- **Port mismatch:** `mvn spring-boot:run` and axios default to 8081; the Vite proxy and Docker use 8080. Set `VITE_API_URL` consistently.
- **Smoke tests on Windows:** `curl.exe` corrupts non-ASCII bytes in arguments (→ 400 `MALFORMED_REQUEST`). Send UTF-8 bodies with `--data-binary @file`.
- **Cross-module `api` interfaces:** a new abstract method breaks every anonymous / stand-in implementation in the other dev's tests (`new VaccinationQueryApi() {…}` in `BoardingBookingIT`). `grep` for `new <Api>() {` and `implements <Api>` before adding one, and tell the other dev. That is why tier-3 additions are `default` placeholders.
- **Postgres must have pgvector (V34+, ADR-0110):** compose, CI and Testcontainers use `pgvector/pgvector:pg17`. Windows Postgres and `postgres:17` containers fail Flyway on V34 (`CREATE EXTENSION vector` needs a superuser). The image is Debian 12 (glibc 2.36), `postgres:17` is Debian 13 (2.41): an old volume opens but prints `collation version mismatch` and string indexes may be wrong — `pg_dump -Fc` + `pg_restore` into a new container, or in each DB (also `template1`) `REINDEX DATABASE <db>; ALTER DATABASE <db> REFRESH COLLATION VERSION;` (ADR-0110 §2). Compose still names the volume `postgres_data`, so run that fix once after pulling.
- **CI (`.github/workflows/ci.yml`, ADR-0121):** triggers on `main, dev`. A PR runs the conflict-marker `git grep`, `mvn clean compile`, `mvn test` (unit; Docker for `PetcareApplicationTests`) and the FE job (`tsc`, lint with `|| true`, build). `integration-tests` (`mvn verify`, all ITs) runs only on push to `main` or `workflow_dispatch`, so IT-only breakage shows late: still run the ITs in groups locally before merging. Never run on a real Actions runner yet. The 3 `FE/src/test/seed-dev*` files are excluded from `tsc` because they import the gitignored `FE/scripts/`.
- **FE test coupled to BE migrations (D065):** `FE/src/shared/api/mock/identity-admin.mock.test.ts` reads `BE/.../db/migration` and compares every [CFG] key / template row; its SQL reader applies `DELETE FROM system_configs WHERE key = …` and `DELETE FROM notification_templates WHERE code IN (…)` (V27, V51) and refuses every other DELETE / TRUNCATE / UPDATE form, so the next migration that edits those two tables in another way turns it red until the reader learns that form; it is not part of CI.
- **`SalesOrder` `@Version` is only a tripwire** (D045 paid, ADR-0132, V54): still lock via a locking `VisitOrderApi` method first and read after — an entity loaded before the lock now fails with 409 instead of silently overwriting `total_amount` (a line-column-only change such as `transferOwnerTo` is not covered).
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
5. **This file:** only when something system-wide changes — the state table, a lock order, a platform tool, a new gotcha, the next free V / ADR / era number. Keep feature history, per-task logs and test-run history out of it; FE detail goes to `frontend-notes.md`.
6. Never `git add` / `commit` / `push` unless the user asks.
