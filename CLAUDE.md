# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Pet Care 2.0 — multi-store pet shop management (vet clinic / grooming / retail). Monorepo: `BE/` (Java 21 + Spring Boot 3.5, Maven) and `FE/` (React 18 + Vite + TypeScript). PostgreSQL 17 + Redis 7 via `docker-compose.yml`. Documentation is Vietnamese, code and identifiers are English.

The spec (`docs/`) describes 25 business modules; **the code implements 4 so far** (`auth`, `iam`, `notification`, `pet`). Treat `docs/` as the target design, not as a description of what exists.

## Commands

All Maven commands run from `BE/`; npm commands from `FE/`.

```bash
# Infra (from repo root) — Postgres + Redis + BE + FE
docker compose up -d postgres redis      # just the dependencies for local dev

# Backend
mvn clean compile -DskipTests
mvn spring-boot:run                      # http://localhost:8080, Swagger at /swagger-ui.html
mvn test                                 # Surefire: *Test.java / *Tests.java
mvn verify                               # Failsafe: *IT.java (adds integration tests)
mvn test -Dtest=PetServiceImplTest                       # one unit test class
mvn test -Dtest=PetServiceImplTest#update_nonOwner_forbidden   # one method
mvn verify -Dit.test=PetFlowIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false  # one IT only

# Frontend
npm ci && npm run dev                    # http://localhost:5173, proxies /api -> :8080
npm run build                            # tsc && vite build
npm run lint                             # eslint, --max-warnings 0
npx tsc --noEmit                         # there is no `npm run typecheck` script

# API contract validation (repo root)
node docs/api/check-contracts.mjs        # validates docs/api/openapi/*.yaml
```

**Docker is required for most tests.** Every `*IT` plus `PetRepositoryTest` spins up `postgres:17` through Testcontainers with `@ServiceConnection`. `PetRepositoryTest` ends in `Test`, so a bare `mvn test` already needs Docker running.

## Documentation is the source of truth — read before coding

Each `docs/` file owns exactly one kind of truth. Never invent a rule, RULE-ID, FSM edge, or enum value that isn't in these files; when information is missing, record it as an assumption/TBD rather than making something up.

| Need | File |
|---|---|
| Scope, actors, requirements | `docs/00-requirements.md` |
| Business operations, command names per actor | `docs/01-business-operations.md` |
| 217 business rules (`RULE-XX-YY`) | `docs/02-business-rules.md` |
| 19 FSMs (mermaid transition diagrams) | `docs/03-state-machines.md` |
| Ubiquitous Language — naming for classes/DTOs/events | `docs/04-glossary.md` |
| Aggregates + decision locks D-01..D-04 | `docs/05-domain-model.md` |
| ERD / schema truth for migrations | `docs/06-erd.md` |
| Backend code conventions (9 files) | `docs/convention/backend/` |
| API contract method + V1–V5 checklist | `docs/api/00-method.md` |
| Per-module API contracts | `docs/api/<module>-v1.md` + `docs/api/openapi/<module>-v1.yaml` |
| Infra decisions already settled | `docs/adr/` |
| **Actual** technical state of the codebase | `docs/architecture/system-overview.md` |

`docs/INDEX.md` maps task type → which file to open first.

Note: `docs/architecture/system-overview.md` §7 is dated 2026-09-12 and still claims `module/` is empty. It is stale — `auth`, `iam`, `notification`, and `pet` modules now exist, and migrations run to `V4`. Trust the code over that section, and refresh it when making significant architectural changes.

## Backend architecture

### Package layout

```
com.petcare/
├── platform/          # cross-cutting infra; must NOT depend on any module/*
│   ├── config/        # SecurityConfig, CorsConfig, JpaAuditingConfig, SchedulingConfig, OpenApiConfig
│   ├── security/      # JwtAuthenticationFilter, TraceIdFilter, UserPrincipal, token/ (refresh + Redis blacklist)
│   ├── exception/     # 5 standard exceptions + GlobalExceptionHandler
│   ├── fsm/           # Transitionable, StateMachineBase
│   ├── model/         # ApiResponse<T>, PageResponse<T>, ErrorResponse, BaseEntity
│   ├── outbox/        # OutboxEvent (transactional outbox)
│   └── enums/         # every FSM status enum + UserRole, SecurityScope, ...
└── module/<feature>/  # controller/ service/ repository/ entity/ dto/ mapper/ [fsm/] [exception/]
```

One package per bounded context. **A module must not import another module's `entity` or `repository`** — go through the other module's `service` (see `PetServiceImpl` → `UserProvisioningService`).

### Request flow

`Controller (@Valid, @PreAuthorize)` → `Service (@Transactional, business rules)` → `[TransitionHandler]` → `Repository`. Controllers hold no business logic and never see a RULE-ID. Entities never leave a controller; Entity↔DTO mapping goes through MapStruct (`@Mapper(componentModel = "spring")`), DTOs are Java records.

### Response envelopes

Success: `ApiResponse<T>{ data, message, code }` — payload is always one level deep in `data`. Lists nest `PageResponse<T>` inside `data`.

Errors: a completely different shape, `ErrorResponse{ success:false, errorCode, message, statusCode, timestamp, traceId }`, all 6 fields always present, produced only by `GlobalExceptionHandler`. `traceId` comes from MDC, set by `TraceIdFilter`.

Exception → errorCode → HTTP mapping is fixed by `docs/convention/backend/04-exception-handling.md`; the five base exceptions are `BusinessRuleViolationException` (400), `InvalidStateTransitionException` (409), `ResourceNotFoundException` (404), `AccessDeniedScopeException` (403), `ConcurrencyConflictException` (409). **No 422 — conflicts are 409.** Only subclass a base exception when it carries ≥2 extra fields, needs a different HTTP status, or has special FSM/TTL retry semantics; state which criterion in the PR.

### FSM pattern

Hand-rolled enum + transition map, not Spring StateMachine. A `{Entity}TransitionHandler extends StateMachineBase<S>` declares `allowedTransitions()` copied **exactly** from the mermaid diagram in `docs/03-state-machines.md` — never add an edge by inference. Business guards (RULE-ID → `BusinessRuleViolationException`) are checked *before* `validateTransition()` (→ `InvalidStateTransitionException`).

Transition method names must match the command name in `docs/01-business-operations.md` character-for-character (`checkInAppointment()`, not `checkIn()`).

### Entities, persistence, events

Business entities extend `platform.model.BaseEntity`: UUID id, `createdAt`/`updatedAt`, `createdBy`/`updatedBy` (filled by `AuditorAware`), `deletedAt` (soft delete), `@Version` (optimistic locking). Entity classes take no `Entity` suffix (`Pet`, `Invoice`) — that suffix is reserved for shared infra entities in `platform/` such as `RefreshTokenEntity`.

Schema is owned by Flyway (`BE/src/main/resources/db/migration/`, currently `V1`–`V4`) with `ddl-auto=validate`. Add a new `V{n}__*.sql`; never edit an applied migration. New tables need the four audit columns plus `version` to match `BaseEntity`.

Cross-aggregate events go through the transactional outbox (`OutboxEventRepository`) written in the same transaction as the state change.

`@Transactional` belongs on Service/TransitionHandler and spans one complete use case — not on controllers or repositories. The documented exception is batched cleanup jobs (`RefreshTokenCleanupService`, ADR-0003).

### Auth

Stateless JWT. `/api/auth/{register,verify-otp,otp/resend,login,refresh}`, `/actuator/{health,info}` and Swagger are public; everything else (including `/api/auth/logout`) requires a Bearer token. Access tokens are blacklisted in Redis on logout with a **fail-open** policy when Redis is down (`app.security.blacklist-fail-open`, ADR-0002); refresh tokens live in PostgreSQL (ADR-0001) and are cleaned by a nightly job (ADR-0003). Method-level `@PreAuthorize` is enabled.

## Testing conventions

Unit tests (`*Test`) are mandatory for any Service/TransitionHandler carrying a business rule or guard — JUnit 5 + Mockito. Integration tests (`*IT`) are mandatory for every FSM and for complex business APIs — `@SpringBootTest` + Testcontainers on real Postgres (no H2), with `spring.flyway.enabled=true` and `ddl-auto=validate` so the real `V1..Vn` schema is exercised. Plain repository CRUD needs no dedicated test.

FSM tests extend `platform/fsm/FsmTransitionTestBase` and must enumerate **every** valid and invalid (from, to) pair from the mermaid diagram — no omissions.

## Frontend notes

`FE/src/` is `app/` (router + providers) · `pages/<domain>/<Name>Page.tsx` (one file per route) · `components/customer/` · `shared/` (api, stores, components, hooks, types, utils). Server state → React Query; app state → Zustand store per domain. New page ⇒ add file + register the route in `App.tsx`.

Known gaps to be aware of before adding code (from `system-overview.md` §7):
- **Two parallel HTTP clients**: `shared/api/client.ts` (fetch, full endpoint coverage, auto 401-refresh) and `shared/api/axios.ts` + `*.api.ts` (partial). Prefer the fetch client. They even use different localStorage key names (`accessToken` vs `access_token`).
- Two `QueryClient` instances are constructed (`main.tsx` and `App.tsx`); the one in `App.tsx` wins.
- No auth route guard — every page mounts unconditionally.

## Gotchas

- **Two OpenAPI sources that disagree.** `docs/api/openapi/*.yaml` is the hand-written target design: paths omit the `/api` prefix and there is no `servers:` block, so importing them into a client produces 404s. `/v3/api-docs` (springdoc, runtime) reflects only the controllers that exist today. Use the yaml for direction, the runtime spec for anything runnable.
- Writing or changing an API contract means running the full V1–V5 checklist in `docs/api/00-method.md`, including `node docs/api/check-contracts.mjs`, before claiming a module is done.
- Guard/validation code should cite its RULE-ID as a string constant passed to the exception (`new BusinessRuleViolationException("RULE-04-01", ...)`).
- Skills live in both `.claude/skills/` and `.opencode/skills/` as copies (symlinks need admin on this machine) — edit both when changing one.
- `hs_err_pid*.log` / `replay_pid*.log` in the repo root and `BE/` are JVM crash dumps, not source. `.ua/` is gitignored tooling scratch.
