<!-- refreshed: 2026-09-08 -->
# Architecture

**Analysis Date:** 2026-09-08

## System Overview

```text
┌─────────────────────────────────────────────────────────────┐
│                     Frontend (SPA - React)                   │
│         `FE/src/pages`, `FE/src/components`                  │
├──────────────────┬──────────────────┬───────────────────────┤
│  Zustand Stores   │  React Query     │   Shared UI/Layout    │
│ `FE/src/shared/   │  (server cache)  │  `FE/src/shared/      │
│   stores`         │                  │   components`         │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                API Client (fetch-based singleton)             │
│                `FE/src/shared/api/client.ts`                  │
└────────────────────────────┬───────────────────────────────┘
                              │ HTTP (JSON, JWT bearer)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                Spring Security Filter Chain                   │
│   `BE/.../common/security/JwtAuthenticationFilter.java`       │
└────────────────────────────┬───────────────────────────────┘
                              ▼
┌──────────────────┬──────────────────┬───────────────────────┐
│    Controllers    │     Services     │     Repositories      │
│ `.../controller`  │  `.../service`   │   `.../repository`    │
└──────────────────┴──────────────────┴───────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│           PostgreSQL 17 (Flyway-versioned schema)             │
│      `BE/src/main/resources/db/migration/V1__init_schema.sql` │
└─────────────────────────────────────────────────────────────┘
```

Note: Redis is declared in `docker-compose.yml` as a cache service but no BE code currently wires a `RedisTemplate`/cache config — it is provisioned infrastructure, not yet integrated (see `.planning/codebase/CONCERNS.md` if generated).

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| PetcareApplication | Spring Boot bootstrap / entry point | `BE/src/main/java/com/petcare/PetcareApplication.java` |
| SecurityConfig | Stateless JWT security filter chain, method security, password encoder | `BE/src/main/java/com/petcare/common/config/SecurityConfig.java` |
| JwtAuthenticationFilter | Extracts and validates bearer JWT per request, populates SecurityContext | `BE/src/main/java/com/petcare/common/security/JwtAuthenticationFilter.java` |
| JwtTokenProvider | Issues/validates JWTs, parses claims into `UserPrincipal` | `BE/src/main/java/com/petcare/common/security/JwtTokenProvider.java` |
| GlobalExceptionHandler | Centralized `@RestControllerAdvice` mapping exceptions to `ApiResponse` | `BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java` |
| PetController | REST endpoints for pet CRUD/search, role-gated via `@PreAuthorize` | `BE/src/main/java/com/petcare/modules/pets/controller/PetController.java` |
| PetServiceImpl | Business logic for pets: ownership checks, mapping entity↔DTO | `BE/src/main/java/com/petcare/modules/pets/service/impl/PetServiceImpl.java` |
| PetRepository | Spring Data JPA repository for `Pet` entity | `BE/src/main/java/com/petcare/modules/pets/repository/PetRepository.java` |
| BaseEntity | Shared `id`, `createdAt`, `updatedAt` audit fields for all entities | `BE/src/main/java/com/petcare/common/model/BaseEntity.java` |
| ApiResponse / PageResponse | Uniform response envelope and pagination wrapper | `BE/src/main/java/com/petcare/common/model/ApiResponse.java`, `BE/src/main/java/com/petcare/common/model/PageResponse.java` |
| App (React root) | Declares all client routes, wraps app in React Query provider, toaster, global modal | `FE/src/app/App.tsx` |
| ApiClient | Singleton fetch wrapper; token storage, auto-refresh-on-401, typed endpoint methods | `FE/src/shared/api/client.ts` |
| useAuthStore | Zustand store for auth state, persisted to localStorage | `FE/src/shared/stores/auth.store.ts` |
| PublicLayout / SiteHeader / SiteFooter | Shared shell for public-facing pages | `FE/src/shared/components/layout/PublicLayout.tsx`, `SiteHeader.tsx`, `SiteFooter.tsx` |

## Pattern Overview

**Overall:** Two independently deployable applications in a monorepo — a Spring Boot 3 modular-monolith REST API (`BE/`) and a Vite + React 18 SPA (`FE/`) — communicating over HTTP/JSON. The backend follows a layered, package-by-feature ("module") architecture; the frontend follows a page/feature-plus-shared-kernel structure with client-side state split between server cache (React Query) and app state (Zustand).

**Key Characteristics:**
- Backend organized as `modules/<feature>/{controller,service,dto,entity,repository}` rather than by technical layer at the top level — see `BE/src/main/java/com/petcare/modules/pets/`.
- Only one module (`pets`) is fully implemented end-to-end; `auth` and `users` currently expose only `entity` classes with no controller/service/repository yet (see Anti-Patterns / Concerns).
- Stateless authentication: no server-side sessions (`SessionCreationPolicy.STATELESS` in `SecurityConfig.java`); JWT carried in `Authorization: Bearer` header.
- Frontend has zero routed authentication guarding at the router level — `App.tsx` mounts all pages unconditionally; role/auth checks are expected to happen inside pages/stores.
- Database schema is Flyway-migration-driven (`BE/src/main/resources/db/migration/V1__init_schema.sql`), single monolithic PostgreSQL database.

## Layers

**Controller Layer (Backend):**
- Purpose: HTTP endpoint definitions, request validation (`@Valid`), authorization annotations (`@PreAuthorize`), response shaping
- Location: `BE/src/main/java/com/petcare/modules/*/controller/`
- Contains: `@RestController` classes, one per feature module
- Depends on: Service interfaces, DTOs
- Used by: Spring MVC dispatcher (external HTTP clients / frontend `ApiClient`)

**Service Layer (Backend):**
- Purpose: Business logic, transaction boundaries (`@Transactional`), entity↔DTO mapping
- Location: `BE/src/main/java/com/petcare/modules/*/service/` (interfaces) and `.../service/impl/` (implementations)
- Contains: `PetService` interface + `PetServiceImpl`
- Depends on: Repositories, entities, DTOs
- Used by: Controllers

**Repository Layer (Backend):**
- Purpose: Data access via Spring Data JPA
- Location: `BE/src/main/java/com/petcare/modules/*/repository/`
- Contains: `PetRepository extends JpaRepository`
- Depends on: JPA entities
- Used by: Services

**Entity Layer (Backend):**
- Purpose: JPA-mapped persistence model, one class per DB table
- Location: `BE/src/main/java/com/petcare/modules/*/entity/`
- Contains: `Pet`, `Account`, `User` — all extend `BaseEntity` for `id`/`createdAt`/`updatedAt`
- Depends on: `BaseEntity` (`BE/src/main/java/com/petcare/common/model/BaseEntity.java`)
- Used by: Repositories, services (never exposed directly to controllers — DTOs used instead)

**Common/Cross-Cutting Layer (Backend):**
- Purpose: Security, exception handling, shared response models, enums, JPA config
- Location: `BE/src/main/java/com/petcare/common/`
- Contains: `security/`, `exception/`, `model/`, `enums/`, `config/`
- Depends on: Nothing feature-specific
- Used by: Every module

**Pages Layer (Frontend):**
- Purpose: Route-level screens, one file per URL route
- Location: `FE/src/pages/<domain>/`
- Contains: `HomePage.tsx`, `ShopPage.tsx`, `PetsPage.tsx`, `LoginPage.tsx`, etc., plus co-located `.mock.ts` data files
- Depends on: `shared/api`, `shared/stores`, `shared/components`
- Used by: `FE/src/app/App.tsx` router

**Shared Kernel (Frontend):**
- Purpose: Cross-page reusable code
- Location: `FE/src/shared/`
- Contains: `api/` (HTTP clients), `stores/` (Zustand), `components/` (UI + layout), `hooks/`, `types/`, `utils/`, `models/`, `constants/`, `data/` (mocks), `services/` (toast wrapper)
- Depends on: External libs (axios, zustand, react-query)
- Used by: Pages and components

**App Shell (Frontend):**
- Purpose: Root composition — router, providers, global overlays
- Location: `FE/src/app/App.tsx`, `FE/src/app/GlobalModal.tsx`, `FE/src/main.tsx`
- Depends on: Pages, shared layout
- Used by: Vite entry (`index.html` → `main.tsx`)

## Data Flow

### Primary Request Path (Pet CRUD example)

1. User action in a page component calls `apiClient.createPet(data)` (`FE/src/shared/api/client.ts:163`)
2. `ApiClient.request()` attaches JWT from `localStorage` and issues `fetch()` to `VITE_API_URL`/`api/pets` (`FE/src/shared/api/client.ts:52`)
3. Request enters Spring's filter chain; `JwtAuthenticationFilter` validates the JWT and populates `SecurityContextHolder` (`BE/src/main/java/com/petcare/common/security/JwtAuthenticationFilter.java:24`)
4. `SecurityConfig`'s authorization rules and `PetController`'s `@PreAuthorize` check the caller's role (`BE/src/main/java/com/petcare/modules/pets/controller/PetController.java:25`)
5. `PetController.createPet()` delegates to `PetService.createPet(ownerId, request)` (`PetController.java:30`)
6. `PetServiceImpl.createPet()` builds a `Pet` entity and persists via `PetRepository.save()` (`PetServiceImpl.java:27-44`)
7. Entity is mapped back to `PetResponse` DTO and wrapped in `ApiResponse` (`PetServiceImpl.java:139`)
8. Response returns to `ApiClient`, which returns it to the caller; on `401`, `ApiClient` auto-refreshes the token and retries once (`FE/src/shared/api/client.ts:75-87`)

### Error Flow

1. Any exception thrown in a controller/service bubbles to `GlobalExceptionHandler` (`BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java`)
2. `BusinessException` / `ResourceNotFoundException` map to their declared HTTP status with a structured `ApiResponse.error(...)`
3. `MethodArgumentNotValidException` (bean validation failures) map to a field-name→message map with HTTP 400
4. All other exceptions fall through to a generic 500 handler that leaks `ex.getMessage()` into the response body (see Anti-Patterns)

**State Management (Frontend):**
- Server-derived data: React Query (`QueryClient` instantiated in both `App.tsx` and `main.tsx` — see Anti-Patterns)
- Client/app state: Zustand stores under `FE/src/shared/stores/` (`auth.store.ts`, `cart.store.ts`, `order.store.ts`, `ui.store.ts`), with `auth.store.ts` using `zustand/middleware persist` to localStorage

## Key Abstractions

**ApiResponse<T> (Backend):**
- Purpose: Uniform envelope for all REST responses (`data`, `message`, status code)
- Examples: `BE/src/main/java/com/petcare/common/model/ApiResponse.java`
- Pattern: Static factory methods (`ApiResponse.ok(...)`, `ApiResponse.created(...)`, `ApiResponse.error(...)`)

**PageResponse<T> (Backend):**
- Purpose: Wraps Spring Data `Page<T>` into a serializable pagination DTO
- Examples: `BE/src/main/java/com/petcare/common/model/PageResponse.java`
- Pattern: `PageResponse.of(page)` factory used in controllers

**BaseEntity (Backend):**
- Purpose: Shared identity + audit timestamp fields for all JPA entities
- Examples: `BE/src/main/java/com/petcare/common/model/BaseEntity.java`, extended by `Pet.java`
- Pattern: `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener.class)`, enabled via `JpaAuditingConfig.java`

**Module (Backend):**
- Purpose: Feature-scoped vertical slice bundling controller/service/dto/entity/repository
- Examples: `BE/src/main/java/com/petcare/modules/pets/`
- Pattern: Package-by-feature; only `pets` is fully built out, `auth`/`users` currently contain entities only

**ApiClient (Frontend):**
- Purpose: Single point of HTTP access to the backend, with token lifecycle management
- Examples: `FE/src/shared/api/client.ts`
- Pattern: Class instantiated once and exported as a singleton (`export const apiClient = new ApiClient(...)`)

**Zustand Store (Frontend):**
- Purpose: Typed, minimal global state containers per domain (auth, cart, order, ui)
- Examples: `FE/src/shared/stores/auth.store.ts`
- Pattern: `create<State>()(persist(...))` for state needing localStorage persistence; plain `create<State>()` otherwise

## Entry Points

**Backend HTTP Server:**
- Location: `BE/src/main/java/com/petcare/PetcareApplication.java`
- Triggers: `mvn spring-boot:run` or the packaged JAR / Docker container (`BE/Dockerfile`)
- Responsibilities: Boots Spring context, auto-configures JPA/Security/Web

**Frontend SPA:**
- Location: `FE/src/main.tsx` (mounts `App` from `FE/src/app/App.tsx`)
- Triggers: `vite` dev server (`npm run dev`) or static build served by any web server
- Responsibilities: Mounts React tree, wraps with `BrowserRouter` and `QueryClientProvider`

**Database Migrations:**
- Location: `BE/src/main/resources/db/migration/V1__init_schema.sql`
- Triggers: Flyway on application startup (implied by presence of `db/migration` folder in a Spring Boot project)
- Responsibilities: Establishes/evolves PostgreSQL schema

## Architectural Constraints

- **Threading:** Backend is a standard synchronous Spring MVC servlet app (thread-per-request via embedded Tomcat); no reactive/async stack detected.
- **Global state:** Frontend `ApiClient` is a module-level singleton holding `baseUrl` and reading/writing `localStorage` directly (`FE/src/shared/api/client.ts:318`). Zustand stores are also module-level singletons by design.
- **Duplicate API clients:** Two parallel HTTP client implementations exist on the frontend — `FE/src/shared/api/client.ts` (fetch-based, full endpoint surface) and `FE/src/shared/api/axios.ts` plus `FE/src/shared/api/product.api.ts`/`review.api.ts` (axios-based, partial surface). Consumers must know which one a given page uses.
- **Duplicate QueryClient instances:** `QueryClient` is created independently in both `FE/src/main.tsx` and `FE/src/app/App.tsx`, and both are wrapped in `QueryClientProvider` — the inner (`App.tsx`) provider wins, making the outer one in `main.tsx` dead configuration.
- **No auth guard at router level:** `FE/src/app/App.tsx` does not gate any route by `isAuthenticated`; pages such as `AccountPage`/`OrderHistoryPage` render even when logged out.
- **Hardcoded auth in backend controller:** `PetController.getCurrentUserId()`/`getCurrentUserRole()` return hardcoded placeholder values (`1L`, `"CUSTOMER"`) instead of reading `SecurityContextHolder` (`BE/src/main/java/com/petcare/modules/pets/controller/PetController.java:101-107`), despite `JwtAuthenticationFilter` correctly populating the security context.

## Anti-Patterns

### Hardcoded "current user" resolution bypassing the security context

**What happens:** `PetController` has placeholder methods `getCurrentUserId()` and `getCurrentUserRole()` that return literal values (`1L`, `"CUSTOMER"`) regardless of the authenticated principal (`BE/src/main/java/com/petcare/modules/pets/controller/PetController.java:100-107`).
**Why it's wrong:** Every authenticated user is treated as user ID 1 with role CUSTOMER — ownership checks (`getPetByIdForOwner`, `updatePet`, `deletePet`) become meaningless and constitute a data-isolation/security defect once real JWTs are issued.
**Do this instead:** Read `Authentication` from `SecurityContextHolder.getContext()` and cast to `UserPrincipal` (`BE/src/main/java/com/petcare/common/security/UserPrincipal.java`), which `JwtAuthenticationFilter` already populates correctly.

### Two competing HTTP client layers on the frontend

**What happens:** `FE/src/shared/api/client.ts` implements a full fetch-based API client with token refresh, while `FE/src/shared/api/axios.ts` + `product.api.ts`/`review.api.ts` implement a second, axios-based client for a subset of endpoints.
**Why it's wrong:** Token handling, error handling, and base-URL logic are duplicated and can drift; new features may pick the wrong client and lose 401-refresh behavior.
**Do this instead:** Consolidate on one HTTP client (prefer whichever supports interceptor-based token refresh) and route all `shared/api/*.api.ts` files through it.

### Duplicate QueryClient / provider nesting

**What happens:** `FE/src/main.tsx` creates a `QueryClient` and wraps `<App />` in `QueryClientProvider`; `FE/src/app/App.tsx` creates a second `QueryClient` and wraps its own tree in another `QueryClientProvider`.
**Why it's wrong:** The outer provider/config in `main.tsx` is shadowed and unused, wasting a client instance and creating confusion about which `staleTime`/retry config is actually active.
**Do this instead:** Instantiate `QueryClient` once (in `main.tsx`) and pass it down; remove the second instantiation from `App.tsx`.

### Generic exception handler leaks internal error messages

**What happens:** `GlobalExceptionHandler.handleGenericException` returns `"Internal server error: " + ex.getMessage()` in the HTTP response body (`BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java:47-52`).
**Why it's wrong:** Raw exception messages (potentially including SQL fragments, file paths, or stack info) are exposed to API clients — an information-disclosure risk.
**Do this instead:** Log the full exception server-side and return a generic, non-identifying message to the client.

## Error Handling

**Strategy:** Centralized via `@RestControllerAdvice` on the backend; try/catch with console logging plus `sonner` toasts on the frontend.

**Patterns:**
- Backend: throw `BusinessException`/`ResourceNotFoundException` from services; `GlobalExceptionHandler` converts to `ApiResponse.error(...)` with the exception's declared HTTP status (`BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java`)
- Frontend: `ApiClient.request()` catches fetch/parse errors, logs via `console.error('API Error:', error)`, and rethrows for the caller to handle (`FE/src/shared/api/client.ts:92-95`); UI-level errors surface via the `Toaster` (`sonner`) mounted in `App.tsx`

## Cross-Cutting Concerns

**Logging:** Backend uses Lombok `@Slf4j` (`PetServiceImpl.java`); no structured/JSON logging config detected. Frontend uses `console.error` only.
**Validation:** Backend uses Jakarta Bean Validation (`@Valid` on DTOs, e.g. `CreatePetRequest`); frontend uses `zod` + `react-hook-form` (`@hookform/resolvers` dependency in `FE/package.json`) though usage was not confirmed in every form.
**Authentication:** JWT bearer tokens, stateless sessions, `@PreAuthorize` role checks on backend (`BE/src/main/java/com/petcare/common/config/SecurityConfig.java`, `.../security/JwtAuthenticationFilter.java`); token persisted in `localStorage` and Zustand `auth.store.ts` on frontend.

---

*Architecture analysis: 2026-09-08*
