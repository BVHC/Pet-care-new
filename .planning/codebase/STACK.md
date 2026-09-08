# Technology Stack

**Analysis Date:** 2026-09-08

## Languages

**Primary:**
- Java 21 - Backend (`BE/src/main/java`)
- TypeScript 5.3 - Frontend (`FE/src`)

**Secondary:**
- SQL (Flyway migrations) - `BE/src/main/resources/db/migration/V1__init_schema.sql`
- CSS (Tailwind utility classes) - `FE/src`

## Runtime

**Environment:**
- JVM 21 (Spring Boot backend), built/run via Maven wrapper implied by `BE/pom.xml`
- Node.js (frontend tooling, version not pinned via `.nvmrc` — not detected)

**Package Manager:**
- Maven - `BE/pom.xml`, lockfile: N/A (Maven does not use a lockfile)
- npm - `FE/package.json`, lockfile: `FE/package-lock.json` (present), also a root-level `package-lock.json`

## Frameworks

**Core:**
- Spring Boot 3.5.15 - `BE/pom.xml` (parent `spring-boot-starter-parent`)
  - `spring-boot-starter-web` - REST API
  - `spring-boot-starter-data-jpa` - ORM/persistence
  - `spring-boot-starter-security` - authentication/authorization
  - `spring-boot-starter-validation` - request validation
  - `spring-boot-starter-data-redis` - Redis integration
  - `spring-boot-starter-actuator` - health/monitoring endpoints
- React 18.2 - `FE/package.json` (SPA UI)
- React Router 6.22 - client-side routing (`react-router-dom`)
- Vite 5.0 - frontend build tool/dev server (`FE/vite.config.ts`)

**Testing:**
- `spring-boot-starter-test`, `spring-security-test` - `BE/pom.xml` (JUnit-based backend tests)
- No frontend test framework detected in `FE/package.json` (no Jest/Vitest/Playwright dependency)

**Build/Dev:**
- Maven Spring Boot plugin - `BE/pom.xml`
- Vite + TypeScript compiler (`tsc && vite build`) - `FE/package.json` build script
- ESLint 8.56 + Prettier 3.2 - `FE/.eslintrc`-style config via `eslint` devDependency, `FE/package.json` lint/format scripts
- Tailwind CSS 4.3 (via `@tailwindcss/postcss`) - `FE/tailwind.config.js`, `FE/postcss.config.js`

## Key Dependencies

**Critical (Backend):**
- `org.postgresql:postgresql` (runtime) - PostgreSQL JDBC driver
- `org.flywaydb:flyway-core` + `flyway-database-postgresql` - schema migrations, `BE/src/main/resources/db/migration/`
- `io.jsonwebtoken:jjwt-api/impl/jackson` 0.12.3 - JWT issuing/parsing, used in `BE/src/main/java/com/petcare/common/security/JwtTokenProvider.java`
- `org.projectlombok:lombok` (optional, excluded from fat jar) - boilerplate reduction

**Critical (Frontend):**
- `axios` 1.6 - HTTP client, `FE/src/shared/api/axios.ts`
- `@tanstack/react-query` 5.17 - server-state/data fetching
- `zustand` 4.5 - client state management (`FE/src/shared/stores`)
- `zod` 3.22 + `@hookform/resolvers` + `react-hook-form` 7.49 - form schema validation
- `gsap` 3.15 - animation
- `sonner` - toast notifications
- `date-fns` - date utilities
- `lucide-react` - icon set

**Infrastructure:**
- PostgreSQL 17 (Docker image `postgres:17`) - `docker-compose.yml`
- Redis 7 (Docker image `redis:7-alpine`) - `docker-compose.yml`

## Configuration

**Environment:**
- Backend: Spring profiles via `SPRING_PROFILES_ACTIVE` (`default` = `application.yml`, `docker` = `application-docker.yml`)
  - Key env vars referenced: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET` (see `docker-compose.yml`)
  - `application.yml` defaults: local Postgres (`jdbc:postgresql://localhost:5432/petcare`), local Redis (`localhost:6379`), JWT secret fallback `petcare-256-bit-secret-key-for-jwt-signing-min-32-chars`, access token TTL 60 min, refresh token TTL 30 days
- Frontend: `VITE_API_URL` env var (defaults to `http://localhost:8080`) - `FE/src/shared/api/axios.ts`
- `.env` files: none detected as tracked; `docker-compose.yml` supports `JWT_SECRET` override via shell env

**Build:**
- `BE/pom.xml` - Maven build/dependency config
- `FE/vite.config.ts`, `FE/tsconfig.json`, `FE/tsconfig.node.json` - frontend build/type config
- `FE/tailwind.config.js`, `FE/postcss.config.js` - styling pipeline
- `BE/Dockerfile`, `FE/Dockerfile`, `docker-compose.yml` - containerized build/run

## Platform Requirements

**Development:**
- JDK 21
- Node.js (compatible with Vite 5 / TS 5.3, Node 18+ recommended)
- Docker + Docker Compose (for Postgres/Redis/full-stack local run)

**Production:**
- Deployment target: Docker containers per `docker-compose.yml` — `petcare-postgres`, `petcare-redis`, `petcare-api` (Spring Boot on port 8080, exposes `/actuator/health`), `petcare-web` (Nginx-served static build on port 80, mapped to host 3000, see `FE/nginx.conf`)
- Network: dedicated Docker network `petcare-network`

---

*Stack analysis: 2026-09-08*
