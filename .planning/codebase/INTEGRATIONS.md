# External Integrations

**Analysis Date:** 2026-09-08

## APIs & External Services

No third-party SaaS APIs (payment, email, SMS, maps, etc.) detected in dependencies or source. The system is currently a self-contained backend (Spring Boot) + frontend (React) stack with no external API clients beyond its own REST API.

## Data Storage

**Databases:**
- PostgreSQL 17
  - Connection: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` (Docker) / hardcoded defaults in `BE/src/main/resources/application.yml` (`jdbc:postgresql://localhost:5432/petcare`)
  - Client/ORM: Spring Data JPA + Hibernate (`spring-boot-starter-data-jpa`), JDBC driver `org.postgresql:postgresql`
  - Schema migrations: Flyway, migration files in `BE/src/main/resources/db/migration/` (currently `V1__init_schema.sql`)

**File Storage:**
- Not detected. No S3/cloud storage SDK or local upload directory found in dependencies.

**Caching:**
- Redis 7 (Docker image `redis:7-alpine`)
  - Connection: `REDIS_HOST`, `REDIS_PORT` (Docker) / `localhost:6379` default in `application.yml`
  - Client: `spring-boot-starter-data-redis`

## Authentication & Identity

**Auth Provider:**
- Custom JWT-based authentication (no third-party identity provider/OAuth SDK detected)
  - Implementation: `BE/src/main/java/com/petcare/common/security/JwtAuthenticationFilter.java`, `JwtTokenProvider.java`, `UserPrincipal.java`
  - Library: `io.jsonwebtoken` (jjwt) 0.12.3
  - Config: `BE/src/main/resources/application.yml` — `jwt.secret` (env override `JWT_SECRET`), `jwt.access-token-ttl-min: 60`, `jwt.refresh-token-ttl-days: 30`
  - Spring Security config: `BE/src/main/java/com/petcare/common/config/SecurityConfig.java`
  - Frontend token handling: `FE/src/shared/api/axios.ts` — stores `access_token`/`refresh_token` in `localStorage`, auto-refreshes via `POST /api/auth/refresh` on 401, redirects to `/auth/login` on refresh failure
  - Auth domain entities: `BE/src/main/java/com/petcare/modules/auth/entity/`

## Monitoring & Observability

**Error Tracking:**
- None detected (no Sentry/Bugsnag/etc. dependency).

**Logs:**
- Default Spring Boot logging (no external log aggregation service detected).
- Spring Boot Actuator exposes `health` and `info` endpoints (`management.endpoints.web.exposure.include: health,info` in `application.yml`), used by Docker healthcheck (`wget http://localhost:8080/actuator/health`).

## CI/CD & Deployment

**Hosting:**
- Docker Compose based deployment (`docker-compose.yml`) — services: `postgres`, `redis`, `backend` (`BE/Dockerfile`), `frontend` (`FE/Dockerfile`, served via Nginx per `FE/nginx.conf`).

**CI Pipeline:**
- GitHub Actions referenced in commit history ("fix build tren github actions") — workflow file not located under a standard `.github/workflows` path in this scan; verify separately if present.

## Environment Configuration

**Required env vars:**
- Backend: `SPRING_PROFILES_ACTIVE`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `JWT_SECRET`
- Frontend: `VITE_API_URL`

**Secrets location:**
- Inline defaults in `docker-compose.yml` and `application.yml` (development-only values, e.g. `POSTGRES_PASSWORD: 123456`, default JWT secret). No `.env` file detected in the repo. Production secrets should be supplied via environment variables/secret manager, not committed defaults.

## Webhooks & Callbacks

**Incoming:**
- None detected.

**Outgoing:**
- None detected.

---

*Integration audit: 2026-09-08*
