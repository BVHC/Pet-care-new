# Tổng quan Kiến trúc Hệ thống

> **Mục đích:** Tài liệu này mô tả kiến trúc kỹ thuật thực tế của codebase (không phải kiến trúc mục tiêu/lý tưởng) — dùng để onboarding nhanh và làm điểm tham chiếu khi thiết kế module mới.
>
> **Khác với `docs/01-06`:** Các tài liệu đó đặc tả **nghiệp vụ** (business operations, rules, FSM, domain model, ERD). Tài liệu này mô tả **cách hệ thống được triển khai về mặt kỹ thuật** — layer, luồng request, stack, deployment.
>
> **Nguồn:** Tổng hợp trực tiếp từ codebase (`BE/`, `FE/`, `docker-compose.yml`) tại thời điểm phân tích **2026-09-12**. Khi codebase thay đổi đáng kể, tài liệu này cần được refresh lại.

---

## 1. Bức tranh tổng thể

Hệ thống là một **monorepo** gồm 2 ứng dụng triển khai độc lập, giao tiếp qua HTTP/JSON:

| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| `BE/` | Spring Boot 3 (Java 21) | REST API — modular monolith, package-by-feature |
| `FE/` | React 18 + Vite + TypeScript | SPA — giao diện khách hàng/vận hành |
| PostgreSQL 17 | Docker (`postgres:17`) | Nguồn chân lý dữ liệu quan hệ, schema quản lý bằng Flyway |
| Redis 7 | Docker (`redis:7-alpine`) | Đã được BE sử dụng thật — access-token blacklist (`TokenBlacklistService`, xem §3/§7) |

```text
┌─────────────────────────────────────────────────────────────┐
│                  Frontend (SPA - React 18)                   │
│         FE/src/pages          FE/src/components              │
├──────────────────┬──────────────────┬───────────────────────┤
│  Zustand Stores   │  React Query     │   Shared UI/Layout    │
│ FE/src/shared/    │  (server cache)  │  FE/src/shared/       │
│   stores          │                  │   components          │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│              API Client (fetch-based singleton)               │
│                FE/src/shared/api/client.ts                    │
└────────────────────────────┬───────────────────────────────┘
                              │ HTTP (JSON, JWT bearer)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                Spring Security Filter Chain                   │
│   BE/.../platform/security/JwtAuthenticationFilter.java       │
└────────────────────────────┬───────────────────────────────┘
                              ▼
┌──────────────────┬──────────────────┬───────────────────────┐
│    Controllers    │     Services     │     Repositories      │
│ .../controller     │  .../service    │   .../repository      │
└──────────────────┴──────────────────┴───────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│           PostgreSQL 17 (schema quản lý bằng Flyway)          │
│      BE/src/main/resources/db/migration/V1__init_schema.sql   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Ngăn xếp công nghệ (Tech Stack)

**Backend:**
- Java 21, Spring Boot 3.5.15 (`spring-boot-starter-web`, `-data-jpa`, `-security`, `-validation`, `-data-redis`, `-actuator`)
- Build: Maven (`BE/pom.xml`)
- DB access: Spring Data JPA + Hibernate, driver `org.postgresql:postgresql`
- Migration: Flyway (`flyway-core` + `flyway-database-postgresql`)
- Auth: JWT tự triển khai qua `io.jsonwebtoken` (jjwt) 0.12.3 — không dùng OAuth/IdP bên thứ ba

**Frontend:**
- React 18.2 + TypeScript 5.3, build bằng Vite 5.0
- Routing: React Router 6.22 (`react-router-dom`)
- Server-state: TanStack React Query 5.17
- Client-state: Zustand 4.5 (một phần dùng `persist` middleware → `localStorage`)
- Form/validation: React Hook Form 7.49 + Zod 3.22
- Styling: Tailwind CSS 4.3
- HTTP: có **2 client song song** — xem §7 (Known Gaps)

**Hạ tầng:**
- PostgreSQL 17, Redis 7 — chạy qua `docker-compose.yml`
- Containerize: `BE/Dockerfile`, `FE/Dockerfile` (FE build tĩnh, phục vụ qua Nginx — `FE/nginx.conf`)
- Không phát hiện SaaS/API bên thứ ba nào (payment gateway, email/SMS, maps...) trong dependency hiện tại — hệ thống hiện tại "tự cung tự cấp"

---

## 3. Kiến trúc Backend

**Kiểu tổ chức:** package-by-feature, khớp `docs/convention/backend/01-package-structure.md` — hạ tầng dùng chung nằm ở `platform/`, mỗi Bounded Context nghiệp vụ (25 module) sẽ có package con riêng dưới `module/`.

```
BE/src/main/java/com/petcare/
├── PetcareApplication.java     # Spring Boot entry point
├── platform/                   # Cross-cutting, dùng chung mọi module — ĐÃ triển khai
│   ├── config/                 # SecurityConfig, CorsConfig, JpaAuditingConfig (+ AuditorAware<UUID>), SchedulingConfig (@EnableScheduling)
│   ├── security/                # JwtAuthenticationFilter, JwtTokenProvider, UserPrincipal, RestAuthenticationEntryPoint, RestAccessDeniedHandler, TraceIdFilter
│   │   └── token/                # RefreshTokenEntity/Repository/Service, TokenBlacklistService (Redis, fail-open — ADR-0002), TokenIssuanceFacade(Impl), RefreshTokenCleanupJob/Service (ADR-0001/0003)
│   ├── exception/                # 5 exception chuẩn + GlobalExceptionHandler (xem 04-exception-handling.md)
│   ├── fsm/                      # StateMachineBase, Transitionable (xem 05-fsm-pattern.md)
│   ├── model/                   # ApiResponse<T>, PageResponse<T>, ErrorResponse, BaseEntity
│   ├── outbox/                  # OutboxEvent, OutboxEventRepository
│   └── enums/                   # Toàn bộ Status-Enum của 19 FSM + UserRole, SecurityScope...
└── module/                     # CHƯA có module nghiệp vụ nào được triển khai (thư mục rỗng)
```

**Layer chuẩn cho một module hoàn chỉnh** (theo `docs/convention/backend/02-layering-and-dto.md`, chưa có ví dụ thực tế trong code vì `module/` hiện rỗng):

| Layer | Trách nhiệm | Phụ thuộc vào |
|---|---|---|
| Controller | HTTP endpoint, `@Valid`, `@PreAuthorize`, trả `Response` record | Service interface, DTO |
| Service / ServiceImpl | Business logic, RULE-ID validation, `@Transactional`, map Entity ↔ DTO qua MapStruct | Repository, Entity, TransitionHandler (nếu có FSM) |
| TransitionHandler | Guard + transition state, extend `StateMachineBase<S>` (chỉ module có FSM) | Repository |
| Repository | Truy cập dữ liệu (Spring Data JPA) | Entity |
| Entity | Model JPA, kế thừa `platform.model.BaseEntity` (id UUID, createdAt/updatedAt, createdBy/updatedBy, deletedAt, version) | — |

**Quy ước khi thêm module mới:** tạo `module/<feature>/` với đủ subpackage (`controller/service/repository/entity/dto/mapper/`, thêm `fsm/`/`exception/` nếu cần) như mô tả ở `docs/convention/backend/01-package-structure.md`, entity kế thừa `platform.model.BaseEntity`, thêm migration Flyway mới (`V2__...sql`, không sửa `V1`) đã bao gồm đủ cột audit (`created_by`, `updated_by`, `deleted_at`, `version`) khớp `BaseEntity`. Chi tiết đầy đủ về convention code (naming, exception, FSM pattern, transaction, logging...) xem `docs/convention/backend/`.

**Bảo mật:** Stateless — không session server-side (`SessionCreationPolicy.STATELESS`). Mỗi request mang JWT trong header `Authorization: Bearer`; `JwtAuthenticationFilter` validate và nạp `SecurityContextHolder`; phân quyền theo role qua `@PreAuthorize`.

---

## 4. Kiến trúc Frontend

```
FE/src/
├── app/                # Root composition: App.tsx (router + providers), GlobalModal.tsx, main.tsx
├── pages/<domain>/      # 1 file = 1 route (customer/, auth/, hotel/, news/, recommend/, review/, about/)
├── components/customer/ # Component đặc thù customer, chưa promote lên shared/
└── shared/              # Shared kernel dùng chung toàn app
    ├── api/             # HTTP client(s) — xem §7
    ├── stores/          # Zustand: auth, cart, order, ui
    ├── components/      # UI dùng chung + layout (site chrome) + ui/ (design-system primitives)
    ├── hooks/, types/, models/, utils/, constants/, data/ (mock), services/ (toast wrapper)
```

**Quản lý state:**
- Dữ liệu từ server → React Query (cache, refetch)
- State riêng của app (giỏ hàng, phiên đăng nhập, UI toggle...) → Zustand store theo domain (`*.store.ts`)

**Quy ước khi thêm mới:** trang mới → `pages/<domain>/<Name>Page.tsx` + đăng ký route trong `App.tsx`; component dùng chung → `shared/components/`; state server-derived → React Query (không tạo Zustand store mới cho việc này).

---

## 5. Luồng xử lý request điển hình (khung hạ tầng — `platform/`)

Chưa có module nghiệp vụ nào được triển khai để minh họa luồng end-to-end thực tế; khung hạ tầng đã sẵn sàng vận hành theo luồng chuẩn sau (mọi Controller module mới sẽ đi qua đúng luồng này):

1. `ApiClient` (FE) gắn JWT từ `localStorage`, gọi `fetch()` tới `VITE_API_URL/api/...`
2. Request đi qua `JwtAuthenticationFilter` (`platform/security`) → gắn `traceId` vào MDC → validate JWT → nạp `UserPrincipal` vào `SecurityContextHolder`
3. `SecurityConfig` (stateless, `@PreAuthorize` theo role) kiểm tra quyền truy cập endpoint
4. Controller (`@Valid` DTO) → Service (business rule + `@Transactional`, dùng `AuditorAware` để tự điền `created_by`/`updated_by`) → Repository
5. Entity map ngược thành `Response` DTO qua MapStruct, bọc trong `ApiResponse<T>` (`platform/model`)
6. Response trả về `ApiClient`; nếu `401` → tự động refresh token và retry 1 lần

**Luồng lỗi:** Exception từ controller/service → `GlobalExceptionHandler` (`platform/exception`, `@RestControllerAdvice`) → map 1 trong 5 exception chuẩn (`BusinessRuleViolationException`, `InvalidStateTransitionException`, `ResourceNotFoundException`, `AccessDeniedScopeException`, `ConcurrencyConflictException`) sang `ErrorResponse` 6-field theo bảng ở `docs/convention/backend/04-exception-handling.md`; `MethodArgumentNotValidException` → `VALIDATION_FAILED` HTTP 400.

---

## 6. Dữ liệu & Triển khai

**Database:** PostgreSQL 17, schema quản lý bằng Flyway (`BE/src/main/resources/db/migration/`, hiện có `V1__init_schema.sql`). Đây là nguồn chân lý schema — đối chiếu với đặc tả nghiệp vụ tại `docs/06-erd.md`.

**Deployment:** `docker-compose.yml` định nghĩa 4 service:

| Service | Container | Port (host:container) | Ghi chú |
|---|---|---|---|
| `postgres` | petcare-postgres | 5432:5432 | Volume `postgres_data` |
| `redis` | petcare-redis | 6379:6379 | Volume `redis_data` |
| `backend` | petcare-api | 8080:8080 | Healthcheck qua `/actuator/health`, phụ thuộc postgres+redis healthy |
| `frontend` | petcare-web | 3000:80 | Build tĩnh, phục vụ qua Nginx |

Biến môi trường chính: `DB_HOST/PORT/NAME/USERNAME/PASSWORD`, `REDIS_HOST/PORT`, `JWT_SECRET` (BE); `VITE_API_URL` (FE). **Lưu ý:** giá trị mặc định trong `docker-compose.yml`/`application.yml` (mật khẩu DB, JWT secret) chỉ dùng cho local dev — không dùng nguyên trạng khi triển khai thật.

---

## 7. Tình trạng hiện tại & Giới hạn đã biết

> Mục này ghi nhận **hiện trạng thực tế của code**, không phải lời phê bình — dùng để biết chỗ nào "chưa xong" trước khi build tính năng mới lên trên.

- **Đã triển khai 4 module nghiệp vụ:** `auth` (register/OTP/login/logout/refresh), `iam` (user provisioning), `notification` (enqueue/dispatch qua `EmailGateway`), `pet` (Pets CRUD + caregiver delegation FSM-3: invite/accept/reject/revoke, `PetAccessGuard`, job hết hạn 15 phút/lần). Hạ tầng `platform/` (security + security/token, exception, FSM base, model, outbox, enums) làm nền cho các module tiếp theo.
- **Migration đã có 5 phiên bản** (`V1__init_schema.sql`: 25 bảng theo `docs/06-erd.md`, bao gồm cột audit chuẩn `created_by`/`updated_by`/`deleted_at`/`version` cho `accounts`/`users`/`pets`; `V2__auth_session_tokens.sql`: bảng refresh token; `V3__refresh_token_cleanup_index.sql`: index phục vụ job dọn dẹp; `V4__pets_audit_columns.sql`: cột audit cho `pets`; `V5__caregiver_delegations.sql`: cột audit + định danh email + `valid_until` + index cho `pet_caregiver_delegations`). Các bảng còn lại nên được rà soát cột audit tương tự khi entity tương ứng được triển khai.
- **Redis đã được BE dùng thật** (không còn là hạ tầng khai báo suông) — `TokenBlacklistService` (`platform/security/token/`) dùng `StringRedisTemplate` làm access-token blacklist khi logout/revoke, với chính sách **fail-open** khi Redis lỗi/timeout (`app.security.blacklist-fail-open`, mặc định `true` — xem ADR-0002). Refresh token thì lưu ở PostgreSQL (không phải Redis), theo ADR-0001, có job định kỳ `RefreshTokenCleanupJob` (cron `0 30 2 * * *`, Asia/Ho_Chi_Minh) dọn token hết hạn/bị revoke theo ADR-0003.
- **2 HTTP client song song ở FE:** `shared/api/client.ts` (fetch-based, đầy đủ endpoint, có auto-refresh token) và `shared/api/axios.ts` + `product.api.ts`/`review.api.ts` (axios-based, chỉ phủ một phần endpoint). Trang mới cần biết đang dùng client nào — nên ưu tiên client fetch-based để có sẵn cơ chế refresh 401.
- **2 instance `QueryClient` được tạo độc lập** ở `main.tsx` và `App.tsx` — provider trong `App.tsx` "thắng", cấu hình ở `main.tsx` thực chất không có tác dụng.
- **Chưa có route guard xác thực ở FE:** `App.tsx` mount mọi page không điều kiện; việc kiểm tra đăng nhập/role hiện phải tự làm trong từng page/store.

---

## 8. Tài liệu liên quan

| Tài liệu | Nội dung |
|---|---|
| `docs/01-business-operations.md` | Actor & nghiệp vụ (business, không phải kỹ thuật) |
| `docs/02-business-rules.md` | 217 RULE-ID — mọi validation/guard trong code phải trích dẫn đúng RULE-ID |
| `docs/03-state-machines.md` | 19 FSM — nguồn chân lý cho transition state |
| `docs/04-glossary.md` | Ubiquitous Language — tên biến/class/API/event phải khớp |
| `docs/05-domain-model.md` | DDD Aggregates/Entities/VO, Architectural Decision Locks D-01..D-04 |
| `docs/06-erd.md` | ERD & schema — nguồn chân lý cấu trúc CSDL |
| `docs/convention/backend/` | Quy ước code backend chi tiết (naming, layering, exception, FSM pattern, transaction, logging, testing) |
| `docs/INDEX.md` | Mục lục tổng, tóm tắt phase/timeline, các câu hỏi/quyết định còn mở |

---

*Phân tích kiến trúc: 2026-09-16 (cập nhật §7 sau đợt caregiver delegation — V5, FSM-3, 4 endpoint, job hết hạn)*
