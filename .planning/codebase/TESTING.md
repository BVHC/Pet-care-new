# Testing Patterns

**Analysis Date:** 2026-09-08

## Test Framework

**Backend (`BE/`):**
- Runner: JUnit 5 (via `spring-boot-starter-test`, declared in `BE/pom.xml`)
- Also includes `spring-security-test` for security-related test support
- Config: no dedicated test properties file found beyond the default Maven Surefire behavior; test profile is activated via `@ActiveProfiles("test")` in the one existing test class
- Test source root: `BE/src/test/java/com/petcare/` and `BE/src/test/resources/` (resources directory exists but currently empty of visible config)

**Frontend (`FE/`):**
- **No test framework is configured.** `FE/package.json` has no `test` script, and no dependency on Jest, Vitest, React Testing Library, Cypress, or Playwright is present in `dependencies`/`devDependencies`.
- No `*.test.*` or `*.spec.*` files exist anywhere under `FE/src/`.

**Run Commands:**
```bash
# Backend (from BE/)
mvn test                 # Run all tests
mvn test -Dtest=ClassName   # Run a single test class
```
No frontend test command exists yet — `npm test` is not defined in `FE/package.json`.

## Test File Organization

**Backend:**
- Location: mirrors `src/main` package structure under `src/test/java/com/petcare/` (only `com.petcare` root currently populated)
- Naming: `{ClassName}Tests.java` suffix, e.g. `PetcareApplicationTests.java`
- Currently only one test file exists in the entire backend: `BE/src/test/java/com/petcare/PetcareApplicationTests.java`. **No unit tests exist for any controller, service, or repository** (e.g., `PetController`, `PetServiceImpl`, `PetRepository` are all untested).

**Frontend:**
- No test directory convention established; none of the 8 module directories under `FE/src/shared/` or the page directories under `FE/src/pages/` contain test files.

## Test Structure

**Current backend pattern** (`BE/src/test/java/com/petcare/PetcareApplicationTests.java`):
```java
@SpringBootTest
@ActiveProfiles("test")
class PetcareApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads successfully
    }
}
```
This is a smoke test only — it verifies the Spring application context wires up, nothing more.

**Recommended structure for new backend tests** (no existing example to follow, so apply Spring Boot conventions):
- Service unit tests: `@ExtendWith(MockitoExtension.class)`, mock the repository with `@Mock`, inject with `@InjectMocks`, assert via JUnit 5 `Assertions` or AssertJ (both available transitively via `spring-boot-starter-test`)
- Controller tests: `@WebMvcTest(PetController.class)` with `MockMvc`, mock `PetService` via `@MockBean`
- Repository tests: `@DataJpaTest` with an embedded/test datasource

## Mocking

**Backend:**
- `spring-boot-starter-test` pulls in Mockito — available but not yet used anywhere in the codebase (no `@Mock`, `@MockBean`, or Mockito imports found)
- No mocking patterns established yet; first test author for `PetServiceImpl` etc. should establish the pattern (mock `PetRepository`, verify interactions with `verify()`)

**Frontend:**
- No mocking framework or pattern exists. If tests are added, note that `FE/src/shared/api/client.ts` exports a singleton `apiClient` instance — mocking will require module-level mocking (e.g. `vi.mock('../shared/api/client')` if Vitest is adopted) rather than dependency injection.

## Fixtures and Factories

- No fixture or factory utilities exist in either `BE/` or `FE/`.
- Backend entities use Lombok `@Builder` (e.g. `Pet.builder()...build()` in `BE/src/main/java/com/petcare/modules/pets/service/impl/PetServiceImpl.java:30`), which is a natural fit for constructing test fixtures once tests are added.
- Frontend has static mock data files for UI development only (not test fixtures): `FE/src/pages/customer/home.mock.ts`, `FE/src/pages/customer/news.mock.ts` — these back placeholder UI content, not test assertions.

## Coverage

**Requirements:** None enforced. No coverage tool (JaCoCo, Istanbul/nyc, c8) configured in `BE/pom.xml` or `FE/package.json`.

**View Coverage:**
Not applicable — no coverage tooling is set up in either project.

## Test Types

**Unit Tests:**
- Backend: none beyond the context-load smoke test. Service and controller logic (validation branching, ownership checks like `isOwnerOfPet`, partial-update logic in `PetServiceImpl.updatePet`) is currently unverified by any automated test.
- Frontend: none exist.

**Integration Tests:**
- Backend: none. No `@SpringBootTest` test hits a real or embedded database; Flyway migrations (`BE/src/main/resources/db/migration/`) are unverified by test suite.
- Frontend: none.

**E2E Tests:**
- Not used. No Cypress/Playwright config or dependency in either `BE/` or `FE/`.

## Common Patterns

No async, error-path, or other reusable test patterns exist yet in this codebase — there is only the single Spring context smoke test. When adding tests, establish patterns for:
- Async testing on the frontend (React Query mutations/queries via `@tanstack/react-query`, used throughout `FE/src/pages/`, will need `waitFor`/async utilities once a framework is chosen)
- Error-path testing on the backend (asserting `ResourceNotFoundException`/`BusinessException` are thrown by service methods, and that `GlobalExceptionHandler` maps them to correct HTTP status/body)

---

*Testing analysis: 2026-09-08*
