# Codebase Structure

**Analysis Date:** 2026-09-08

## Directory Layout

```
Pet-care-new/
├── BE/                          # Spring Boot 3 backend (Java, Maven)
│   ├── src/main/java/com/petcare/
│   │   ├── PetcareApplication.java   # Boot entry point
│   │   ├── common/                   # Cross-cutting: config, security, exception, model, enums
│   │   └── modules/                  # Feature modules (package-by-feature)
│   │       ├── auth/entity/          # Account entity only (no service/controller yet)
│   │       ├── users/entity/         # User entity only (no service/controller yet)
│   │       └── pets/                 # Fully implemented: controller/dto/entity/repository/service
│   ├── src/main/resources/
│   │   └── db/migration/             # Flyway SQL migrations
│   ├── src/test/                     # JUnit test tree (mirrors main)
│   ├── pom.xml
│   └── Dockerfile
├── FE/                          # Vite + React 18 + TypeScript SPA
│   ├── public/                       # Static assets (brands/, imgs/)
│   ├── src/
│   │   ├── app/                      # Root composition: App.tsx, GlobalModal.tsx
│   │   ├── pages/<domain>/           # Route-level screens, grouped by domain
│   │   ├── components/customer/      # Domain-specific components not yet moved to shared/
│   │   └── shared/                   # Shared kernel (api, stores, components, hooks, types, utils...)
│   ├── index.html
│   ├── vite.config.ts
│   ├── tsconfig.json
│   └── package.json
├── docs/                        # Business/domain documentation (Markdown), diagrams/db-schema
├── plans/                       # Planning artifacts (non-GSD)
├── .planning/                   # GSD planning state (this document lives in .planning/codebase/)
├── .claude/ , .agents/          # GSD tooling: skills, commands, hooks, scripts (not application code)
├── .github/workflows/           # CI pipelines
└── docker-compose.yml           # postgres, redis (local dev infra)
```

## Directory Purposes

**`BE/src/main/java/com/petcare/common/`:**
- Purpose: Shared infrastructure used by every feature module
- Contains: `config/` (`SecurityConfig.java`, `JpaAuditingConfig.java`), `security/` (`JwtAuthenticationFilter.java`, `JwtTokenProvider.java`, `UserPrincipal.java`), `exception/` (`BusinessException.java`, `ResourceNotFoundException.java`, `GlobalExceptionHandler.java`), `model/` (`ApiResponse.java`, `PageResponse.java`, `BaseEntity.java`), `enums/` (`AccountStatus.java`, `UserRole.java`)
- Key files: `BE/src/main/java/com/petcare/common/config/SecurityConfig.java`

**`BE/src/main/java/com/petcare/modules/<feature>/`:**
- Purpose: One vertical slice per business feature
- Contains (when fully built, see `pets/`): `controller/`, `dto/request/`, `dto/response/`, `entity/`, `repository/`, `service/`, `service/impl/`
- Key files: `BE/src/main/java/com/petcare/modules/pets/controller/PetController.java`
- Note: `auth/` and `users/` currently only have `entity/` subfolders (`Account.java`, `User.java`) — no service/controller/repository implemented yet. New auth/user endpoints should follow the same subfolder pattern as `pets/`.

**`BE/src/main/resources/db/migration/`:**
- Purpose: Versioned SQL schema changes applied by Flyway on startup
- Contains: `V1__init_schema.sql`
- Key files: `BE/src/main/resources/db/migration/V1__init_schema.sql`

**`FE/src/app/`:**
- Purpose: Root React composition — router setup, global providers/overlays
- Contains: `App.tsx` (routes + QueryClientProvider + Toaster + GlobalModal), `GlobalModal.tsx`

**`FE/src/pages/<domain>/`:**
- Purpose: One folder per route domain; each file is a route-level page component
- Contains: `about/`, `auth/`, `customer/`, `hotel/`, `news/`, `recommend/`, `review/`
- Key files: `FE/src/pages/customer/HomePage.tsx`, `FE/src/pages/auth/LoginPage.tsx`
- Note: `customer/` also contains co-located mock data files (`home.mock.ts`, `news.mock.ts`) alongside pages — duplicated by near-identical files in `FE/src/shared/data/`.

**`FE/src/components/customer/`:**
- Purpose: Domain-specific components used only by customer pages, not yet promoted to `shared/components`
- Contains: `HeroBanner.tsx`

**`FE/src/shared/`:**
- Purpose: Cross-cutting frontend kernel reused across pages
- Contains:
  - `api/` — HTTP clients: `client.ts` (fetch-based, primary), `axios.ts` + `product.api.ts`/`review.api.ts` (axios-based, secondary — see ARCHITECTURE.md Anti-Patterns)
  - `stores/` — Zustand state: `auth.store.ts`, `cart.store.ts`, `order.store.ts`, `ui.store.ts`, plus `index.ts` barrel
  - `components/` — `Button.tsx`, `Card.tsx`, `Input.tsx`, `Select.tsx`, `layout/` (site chrome), `ui/` (design-system primitives: `Badge.tsx`, `Modal.tsx`, `Stars.tsx`, etc.)
  - `hooks/` — `useDebounce.ts`, `useInViewOnce.ts`, `usePagination.ts`
  - `types/` — shared TypeScript interfaces (`index.ts`)
  - `models/` — domain model types (`product.model.ts`)
  - `utils/` — `format.ts`, `order.utils.ts`
  - `constants/` and `constants.ts` — route/config constants (two locations, see Naming Conventions)
  - `data/` — mock data (`home.mock.ts`, `news.mock.ts`)
  - `services/` — `toast.service.ts` (wraps `sonner`)

**`docs/`:**
- Purpose: Business/domain specification documents (Markdown) — business operations, business rules, state machines, glossary, domain model — plus `diagrams/db-schema/`
- Contains: `01-business-operations.md` through `05-domain-model.md`

**`.claude/` and `.agents/`:**
- Purpose: GSD workflow tooling (skills, commands, hooks) — not application source code, should not be treated as part of the product codebase when reasoning about features

## Key File Locations

**Entry Points:**
- `BE/src/main/java/com/petcare/PetcareApplication.java`: Spring Boot main class
- `FE/src/main.tsx`: React root render, mounts `App.tsx`
- `FE/index.html`: Vite HTML entry (implied by Vite convention)

**Configuration:**
- `BE/pom.xml`: Maven dependencies/build
- `BE/Dockerfile`: Backend container build
- `BE/src/main/java/com/petcare/common/config/SecurityConfig.java`: Security filter chain
- `FE/vite.config.ts`: Dev server proxy (`/api` → `localhost:8080`), `@` path alias
- `FE/tsconfig.json`: TypeScript compiler options, `@/*` path alias
- `FE/package.json`: Scripts, dependencies
- `docker-compose.yml`: Local Postgres + Redis infra

**Core Logic:**
- `BE/src/main/java/com/petcare/modules/pets/`: Reference implementation of a complete feature module
- `FE/src/shared/api/client.ts`: Primary API access layer
- `FE/src/shared/stores/`: App state

**Testing:**
- `BE/src/test/java/com/petcare/`: JUnit test tree (currently sparse — verify coverage before assuming parity with `main`)
- No FE test directory/framework detected (no `*.test.*`/`*.spec.*` files or test runner config found under `FE/`)

## Naming Conventions

**Files (Backend):**
- One public class per file, `PascalCase.java` matching the class name
- Suffix indicates role: `*Controller.java`, `*Service.java`, `*ServiceImpl.java`, `*Repository.java`, `*Request.java`/`*Response.java` (DTOs), `*Exception.java`

**Files (Frontend):**
- Page components: `PascalCase.tsx` ending in `Page` (e.g., `HomePage.tsx`, `CartPage.tsx`)
- Shared components: `PascalCase.tsx` (e.g., `Button.tsx`, `Modal.tsx`)
- Stores: `camelCase.store.ts` (e.g., `auth.store.ts`)
- API modules: `camelCase.api.ts` (e.g., `product.api.ts`) or plain `client.ts`/`axios.ts`
- Mock data: `camelCase.mock.ts`
- Hooks: `useXxx.ts` in `shared/hooks/`
- Barrel files: `index.ts` re-exporting a directory's public members (`shared/components/index.ts`, `shared/hooks/index.ts`, `shared/stores/index.ts`, `pages/auth/index.ts`)

**Directories:**
- Backend: lowercase, singular-technical-role names (`controller`, `service`, `entity`, `repository`, `dto/request`, `dto/response`)
- Frontend: lowercase domain names under `pages/` (`customer`, `auth`, `hotel`) and technical-role names under `shared/` (`api`, `stores`, `components`, `hooks`, `types`, `utils`)

## Where to Add New Code

**New Backend Feature/Module:**
- Create `BE/src/main/java/com/petcare/modules/<feature>/` with `controller/`, `service/`, `service/impl/`, `repository/`, `entity/`, `dto/request/`, `dto/response/` subpackages, mirroring `modules/pets/`
- Entities extend `com.petcare.common.model.BaseEntity`
- Add a new Flyway migration file in `BE/src/main/resources/db/migration/` (e.g., `V2__<description>.sql`) rather than editing `V1__init_schema.sql`
- Tests: mirror path under `BE/src/test/java/com/petcare/modules/<feature>/`

**New Frontend Page:**
- Add `FE/src/pages/<domain>/<Name>Page.tsx` (create a new domain folder if needed)
- Register the route in `FE/src/app/App.tsx`
- Prefer `FE/src/shared/api/client.ts` for new API calls unless the page already depends on the axios-based clients

**New Shared Frontend Component:**
- Generic/reusable: `FE/src/shared/components/` (or `shared/components/ui/` for design-system primitives)
- Domain-specific but reused only within one page domain: co-locate under `FE/src/components/<domain>/` similar to `components/customer/`

**New Frontend State:**
- Server-derived/cacheable data: use React Query (`@tanstack/react-query`), not a new Zustand store
- Client-only app state: add a new `*.store.ts` in `FE/src/shared/stores/` following the `create<State>()(...)` pattern in `auth.store.ts`

**Utilities:**
- Backend shared helpers: `BE/src/main/java/com/petcare/common/`
- Frontend shared helpers: `FE/src/shared/utils/`

## Special Directories

**`.planning/`:**
- Purpose: GSD workflow state and generated codebase docs (this file's location)
- Generated: Yes (by GSD tooling)
- Committed: Project-dependent — verify `.gitignore` before assuming

**`.claude/` and `.agents/`:**
- Purpose: GSD agent/skill/command definitions
- Generated: Partially (scaffolded by GSD install/update)
- Committed: Yes (tracked in this repo per `git status` showing no untracked entries there)

**`docs/diagrams/db-schema/`:**
- Purpose: Database schema diagrams supporting the domain model docs
- Generated: No (manually authored/exported)
- Committed: Yes

**`plans/`:**
- Purpose: Non-GSD planning artifacts (predates or supplements `.planning/`)
- Generated: No
- Committed: Yes

**`FE/public/`:**
- Purpose: Static assets served as-is by Vite (`brands/`, `imgs/`)
- Generated: No
- Committed: Yes

---

*Structure analysis: 2026-09-08*
