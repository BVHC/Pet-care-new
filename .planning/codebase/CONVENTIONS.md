# Coding Conventions

**Analysis Date:** 2026-09-08

## Naming Patterns

**Backend (Java, `BE/src/main/java/com/petcare/`):**
- Package-per-module structure: `modules/{module}/{controller,dto,entity,repository,service,service/impl}` — see `BE/src/main/java/com/petcare/modules/pets/`
- Classes: PascalCase, suffixed by role — `PetController`, `PetService` (interface), `PetServiceImpl` (implementation), `PetRepository`, `Pet` (entity)
- DTOs split by direction and named for intent: `CreatePetRequest`, `UpdatePetRequest` in `dto/request/`; `PetResponse` in `dto/response/`
- Exceptions: `BusinessException`, `ResourceNotFoundException` in `common/exception/`
- Enums live in `common/enums/` (e.g. `UserRole.java`, `AccountStatus.java`) — not inside modules, even though they are used by module entities
- Methods: camelCase, verb-first — `createPet`, `getPetByIdForOwner`, `isOwnerOfPet`
- Private helper methods placed at bottom of class under a `// Helper methods` comment (`BE/src/main/java/com/petcare/modules/pets/service/impl/PetServiceImpl.java:133`)

**Frontend (TypeScript/React, `FE/src/`):**
- Feature/page components: PascalCase files — `HomePage.tsx`, `PetsPage.tsx` in `FE/src/pages/{domain}/`
- Shared UI primitives: PascalCase in `FE/src/shared/components/ui/` — `Button.tsx`, `Badge.tsx`, `IconCircle.tsx`
- Non-component modules use dot-suffixed lowerCamelCase / kebab: `product.api.ts`, `auth.store.ts`, `header.utils.ts`, `home.mock.ts`
- Zustand stores: `{domain}.store.ts` in `FE/src/shared/stores/`, re-exported via `FE/src/shared/stores/index.ts`
- Hooks: `use{Thing}.ts` in `FE/src/shared/hooks/` — `useDebounce.ts`, `useInViewOnce.ts`, `usePagination.ts`
- Barrel files (`index.ts`) used per directory to re-export public members: `FE/src/shared/components/index.ts`, `FE/src/shared/hooks/index.ts`, `FE/src/shared/stores/index.ts`, `FE/src/pages/auth/index.ts`

**Types:**
- TypeScript: PascalCase interfaces/types (`ApiResponse`, `PageResponse`, `AuthTokens`) centralized in `FE/src/shared/types/`
- Java: entities extend `BaseEntity` (`BE/src/main/java/com/petcare/common/model/BaseEntity.java`) for `id`, `createdAt`, `updatedAt`

## Code Style

**Backend:**
- Java 21, Spring Boot 3.5.15 (`BE/pom.xml`)
- Lombok used extensively: `@RequiredArgsConstructor` for constructor injection, `@Slf4j` for logging, `@Getter`/`@Setter`/builder pattern on entities and DTOs
- No standalone formatter/linter config detected (no checkstyle/spotless plugin in `BE/pom.xml`) — style is convention-driven only

**Frontend:**
- ESLint and Prettier are declared in `FE/package.json` devDependencies (`eslint`, `@typescript-eslint/*`, `prettier`) but **no config files exist** (no `.eslintrc*`, `eslint.config.*`, or `.prettierrc*` found in `FE/`) — `npm run lint`/`npm run format` scripts exist but rely on tool defaults or will fail without a config; treat this as a gap when adding new lint rules
- Tailwind CSS v4 used for styling; components use CSS variables (`var(--color-brand-primary)`) rather than fixed hex values — see `FE/src/shared/components/ui/Button.tsx`
- Style variants defined as typed constant maps (`SIZE_CLASSES`, `VARIANT_CLASSES`) rather than inline conditionals — follow this pattern for new variant-based components

## Import Organization

**Backend:**
- No enforced import order; wildcard imports are used freely (`org.springframework.data.domain.*`, `org.springframework.web.bind.annotation.*`, `java.util.*`) — see `BE/src/main/java/com/petcare/modules/pets/controller/PetController.java:11,15`

**Frontend:**
- `import type { ... }` used for type-only imports (`FE/src/shared/components/ui/Button.tsx:1`, `FE/src/shared/api/client.ts:5`)
- Relative imports for local modules (`../types`), no path aliases configured in `FE/tsconfig.json`

## Error Handling

**Backend:**
- Centralized via `@RestControllerAdvice` in `BE/src/main/java/com/petcare/common/exception/GlobalExceptionHandler.java`
- Two domain exception types: `BusinessException` (400, carries a `code` + `HttpStatus`, defaults to `BAD_REQUEST`) and `ResourceNotFoundException` (404, formatted `"{resource} not found with {field}: '{value}'"`)
- Validation errors (`MethodArgumentNotValidException`) mapped to a field→message map with a 400 response
- Fallback generic `Exception` handler returns 500 with the raw exception message included in the response body — **this leaks internal error detail to clients** (`GlobalExceptionHandler.java:47-52`); should be tightened before production use
- Service layer throws `ResourceNotFoundException` via `.orElseThrow(() -> new ResourceNotFoundException("Pet", "id", petId))` — follow this pattern for other modules' services

**Frontend:**
- `ApiClient.request()` in `FE/src/shared/api/client.ts` throws on non-OK responses (`throw new Error(data.message || 'Request failed')`) and logs via `console.error('API Error:', error)` — no centralized error boundary or toast wiring shown in this file; `sonner` is a dependency, implying toast-based error surfacing is expected but not consistently wired at the client level
- 401 handling includes an inline access-token refresh-and-retry flow directly inside `request()` (`client.ts:73-89`) rather than a separate interceptor — note there is also a separate `FE/src/shared/api/axios.ts`, indicating two parallel HTTP client implementations (fetch-based `client.ts` and axios-based `axios.ts`) coexist; confirm which is canonical before adding new API calls

## Logging

**Backend:**
- Lombok `@Slf4j` on service classes; log at `info` for lifecycle events, e.g. `log.info("Creating pet for owner: {}", ownerId)` and `log.info("Created pet with id: {}", savedPet.getId())` (`PetServiceImpl.java:28,42`)
- Use parameterized `{}` placeholders, not string concatenation

**Frontend:**
- `console.error` used ad hoc in the API client (`client.ts:93`); no structured logging framework

## Comments

- Sparse inline comments; used mainly to mark section boundaries (`// Auth endpoints`, `// Helper methods`, `// Token storage`) rather than explaining logic
- Explicit placeholder markers for incomplete work: `PetController.java:100` — `// Placeholder methods - implement with SecurityContext` above stub methods `getCurrentUserId()`/`getCurrentUserRole()` that return hardcoded values (`1L`, `"CUSTOMER"`); this is a known gap, not a real auth integration yet

## Function Design

**Backend:**
- Controllers are thin: extract current user context, delegate to service, wrap in `ResponseEntity<ApiResponse<T>>`
- Services own business logic and transaction boundaries via `@Transactional` (class-level default read-write, method-level `@Transactional(readOnly = true)` for query methods) — see `PetServiceImpl.java:21,48`
- Partial-update pattern: null-check each field on the request DTO before applying to entity (`PetServiceImpl.java:74-91`) — follow this pattern for other `update*` service methods

**Frontend:**
- `ApiClient` is a single large class with one method per REST endpoint, grouped by domain via comments (`// Pet endpoints`, `// Cart endpoints`) — new endpoints should be added as additional methods in the matching section of `FE/src/shared/api/client.ts`
- Components are function components using named exports (`export function Button(...)`), destructuring props with defaults inline

## Module Design

**Backend:**
- Service interface (`PetService`) + implementation (`PetServiceImpl` in `service/impl/`) separation used consistently — implement new module services the same way
- Repositories are Spring Data JPA interfaces (`PetRepository extends JpaRepository`) with derived and custom query methods (`findByIdAndOwnerId`, `searchByName`)

**Frontend:**
- Barrel exports (`index.ts`) per shared directory for consumer-facing imports
- Singleton export pattern for the API client: `export const apiClient = new ApiClient(API_BASE_URL); export default apiClient;` (`client.ts:317-319`)

---

*Convention analysis: 2026-09-08*
