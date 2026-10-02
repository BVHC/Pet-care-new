# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pet Care — multi-branch pet clinic management (exam/vaccination, grooming, boarding, counter sales, cashier, inventory, customer care). Monorepo: `BE/` (Java 21 + Spring Boot 3.5, Maven) and `FE/` (React 18 + Vite + TypeScript). PostgreSQL 17 + Redis 7 via `docker-compose.yml`. Documentation is Vietnamese, code and identifiers are English.

**State as of 2026-10-02:** the spec was redesigned (v16) and the backend was reset to a bare skeleton — only `PetcareApplication` and a `contextLoads` test remain. There is no `platform/`, no `module/`, no Flyway migration yet. Earlier modules (`auth`, `iam`, `notification`, `pet`, order, inventory, procurement…) were removed together with the old API contracts, ADRs and diagrams. Everything is to be rebuilt from `docs/01`–`05`.

## Commands

All Maven commands run from `BE/`; npm commands from `FE/`.

```bash
# Infra (from repo root) — Postgres + Redis + BE + FE
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

**Tests need a running Postgres.** `PetcareApplicationTests` uses the `test` profile: real Postgres at `localhost:5432/petcare_test` (create it once: `docker compose exec postgres psql -U postgres -c "CREATE DATABASE petcare_test"`), `ddl-auto: create-drop`, Flyway disabled. Testcontainers is still in `pom.xml` for future `*IT` classes.

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

`docs/architecture/system-overview.md`, `docs/api/INDEX.md`, `docs/adr/INDEX.md`, `docs/diagrams/INDEX.md` are currently empty placeholders.

### Where `docs/convention/backend/` is stale

The conventions predate the v16 spec. Follow them for structure, but override these points:
- Rule IDs are `BR-TK-19` style, not `RULE-06-11`. Cite them as a string constant passed to the exception: `new BusinessRuleViolationException("BR-LH-05", ...)`.
- FSM whitelists come from the numbered transition tables in `03-state-machines.md` (columns *Từ → Sang*, *Người kích hoạt*, *Điều kiện*, *Hệ quả*), not from mermaid diagrams. `X → X` rows are guarded operations that keep the state.
- `03-naming-convention.md` requires names to match an Actor↔Command table in `01-business-operations.md` and a glossary. Neither exists any more: 01 lists Vietnamese use cases only. Naming of commands/transition methods is TBD — propose English names and ask.
- `04-exception-handling.md` / `09-testing.md` examples reference old modules (Refund, Atomic Reschedule); the mechanics still apply.

## Backend architecture (target, per conventions)

### Package layout

```
com.petcare/
├── platform/          # cross-cutting infra; must NOT depend on any module/*
│                      # security (JWT/RBAC/branch scope), exception, audit, fsm, config, model (envelopes)
└── module/<feature>/  # controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/]
```

One package per bounded context. **A module must not import another module's `entity` or `repository`** — go through the other module's `service`.

### Request flow

`Controller (@Valid, @PreAuthorize)` → `Service (@Transactional, business rules)` → `[TransitionHandler]` → `Repository`. Controllers hold no business logic and never see a rule ID. Entities never leave a controller; Entity↔DTO mapping goes through MapStruct (`@Mapper(componentModel = "spring")`), DTOs are Java records.

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

Flyway owns the schema (`BE/src/main/resources/db/migration/`, `ddl-auto=validate` in the default profile). Add `V{n}__*.sql`; never edit an applied migration.

Cross-aggregate consequences (state-machine *Phụ lục*) run in **one transaction**, coordinated by the service of the aggregate that emits the event (domain-model principle 6). Outgoing email/notifications go through the `notification_outbox` table with retry (ST20).

`@Transactional` belongs on Service/TransitionHandler and spans one complete use case — not on controllers or repositories.

### Auth (spec)

Email is the only account identifier (OTP-verified); phone is not unique anywhere and CCCD is never stored (BR-TK-01, 16, BR-KH-10). Customer is the root entity; Account is optional and linked via `customers.account_id` (BR-TK-19). Roles are a fixed enum: `CUSTOMER`, `ADMIN`, `SUPER_MANAGER`, `BRANCH_MANAGER`, `RECEPTIONIST`, `VET`, `CARETAKER`; A05–A08 are scoped to one branch. `application.yml` still carries `jwt.*` and `app.*` keys (refresh-token cleanup, otp-expiry, caregiver, order-timeout, `blacklist-fail-open`) left over from the removed implementation — reuse or prune them when auth is rebuilt.

## Testing conventions

Unit tests (`*Test`) are mandatory for any Service/TransitionHandler carrying a business rule or guard — JUnit 5 + Mockito. Integration tests (`*IT`) are mandatory for every FSM and for complex business APIs — `@SpringBootTest` on real Postgres (no H2), exercising the real Flyway schema. Plain repository CRUD needs no dedicated test.

FSM tests must enumerate **every** valid and invalid (from, to) pair from the state-machine table — no omissions.

## Frontend notes

`FE/src/` is `app/` (router + providers) · `pages/<domain>/<Name>Page.tsx` (one file per route; `admin/`, `auth/`, `customer/`, `hotel/`, `news/`…) · `components/{admin,customer,ui}/` · `shared/` (api, stores, components, hooks, types, utils, services, models). Server state → React Query; app state → Zustand store per domain. New page ⇒ add file + register the route in `App.tsx`.

The FE was built against the old spec — many admin pages (tenants, promotions, membership, refunds, incidents…) cover features that are out of scope or tier 3 in v16. Check `docs/INDEX.md` before wiring a page to an API.

Known gaps:
- One HTTP client: `shared/api/axios.ts` (bearer token interceptor, one refresh retry on 401) plus `auth.api.ts`, `product.api.ts`, `review.api.ts`. Token keys `access_token` / `refresh_token`.
- Two `QueryClient` instances are constructed (`main.tsx` and `App.tsx`); the one in `App.tsx` wins.
- No auth route guard — every page mounts unconditionally.

## Gotchas

- **Port mismatch.** `mvn spring-boot:run` listens on 8081 (`SERVER_PORT` default), but the Vite proxy defaults to 8080 (Docker). Set `VITE_API_URL` or `SERVER_PORT=8080` when running BE locally.
- CI (`.github/workflows/ci.yml`) runs `mvn flyway:migrate` + `mvn test` against a Postgres service with password `postgres`, while `application-test.yml` uses `123456`.
- Skills live in `.opencode/skills/` (`designing-apis`, `postman-api-testing`); there is no `.claude/skills/` copy at the moment.
- `hs_err_pid*.log` / `replay_pid*.log` in the repo root and `BE/` are JVM crash dumps, not source. `.ua/` is gitignored tooling scratch.
