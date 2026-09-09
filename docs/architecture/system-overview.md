# Tổng quan Kiến trúc Hệ thống

> **Mục đích:** Tài liệu này mô tả kiến trúc kỹ thuật thực tế của codebase (không phải kiến trúc mục tiêu/lý tưởng) — dùng để onboarding nhanh và làm điểm tham chiếu khi thiết kế module mới.
>
> **Khác với `docs/01-06`:** Các tài liệu đó đặc tả **nghiệp vụ** (business operations, rules, FSM, domain model, ERD). Tài liệu này mô tả **cách hệ thống được triển khai về mặt kỹ thuật** — layer, luồng request, stack, deployment.
>
> **Nguồn:** Tổng hợp trực tiếp từ codebase (`BE/`, `FE/`, `docker-compose.yml`) tại thời điểm phân tích **2026-09-08**. Khi codebase thay đổi đáng kể, tài liệu này cần được refresh lại.

---

## 1. Bức tranh tổng thể

Hệ thống là một **monorepo** gồm 2 ứng dụng triển khai độc lập, giao tiếp qua HTTP/JSON:

| Thành phần | Công nghệ | Vai trò |
|---|---|---|
| `BE/` | Spring Boot 3 (Java 21) | REST API — modular monolith, package-by-feature |
| `FE/` | React 18 + Vite + TypeScript | SPA — giao diện khách hàng/vận hành |
| PostgreSQL 17 | Docker (`postgres:17`) | Nguồn chân lý dữ liệu quan hệ, schema quản lý bằng Flyway |
| Redis 7 | Docker (`redis:7-alpine`) | Hạ tầng cache — **đã khai báo nhưng chưa được code BE sử dụng** (xem §7) |

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
│   BE/.../common/security/JwtAuthenticationFilter.java         │
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

**Kiểu tổ chức:** package-by-feature (`modules/<feature>/`), không phải layer-first ở cấp cao nhất.

```
BE/src/main/java/com/petcare/
├── PetcareApplication.java     # Spring Boot entry point
├── common/                     # Cross-cutting, dùng chung mọi module
│   ├── config/                 # SecurityConfig, JpaAuditingConfig
│   ├── security/                # JwtAuthenticationFilter, JwtTokenProvider, UserPrincipal
│   ├── exception/                # BusinessException, ResourceNotFoundException, GlobalExceptionHandler
│   ├── model/                   # ApiResponse<T>, PageResponse<T>, BaseEntity
│   └── enums/                   # AccountStatus, UserRole
└── modules/
    ├── auth/entity/             # Account — CHỈ có entity, chưa có service/controller
    ├── users/entity/            # User — CHỈ có entity, chưa có service/controller
    └── pets/                    # Module tham chiếu — đã hoàn thiện đủ layer
        ├── controller/
        ├── dto/{request,response}/
        ├── entity/
        ├── repository/
        └── service/{,impl}/
```

**Layer trong một module hoàn chỉnh** (tham chiếu: `modules/pets/`):

| Layer | Trách nhiệm | Phụ thuộc vào |
|---|---|---|
| Controller | HTTP endpoint, `@Valid`, `@PreAuthorize`, định dạng response | Service interface, DTO |
| Service / ServiceImpl | Business logic, `@Transactional`, map Entity ↔ DTO | Repository, Entity |
| Repository | Truy cập dữ liệu (Spring Data JPA) | Entity |
| Entity | Model JPA, kế thừa `BaseEntity` (id, createdAt, updatedAt) | — |

**Quy ước khi thêm module mới:** tạo `modules/<feature>/` với đủ 5 subpackage như `pets/`, entity kế thừa `BaseEntity`, thêm migration Flyway mới (`V2__...sql`, không sửa `V1`). Chi tiết đầy đủ về convention code (naming, exception, FSM pattern, transaction, logging...) xem `docs/convention/backend/`.

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

## 5. Luồng xử lý request điển hình

Ví dụ: tạo mới một Pet (`POST /api/pets`)

1. Page component gọi `apiClient.createPet(data)` (`FE/src/shared/api/client.ts`)
2. `ApiClient.request()` gắn JWT từ `localStorage`, gọi `fetch()` tới `VITE_API_URL/api/pets`
3. Request đi qua `JwtAuthenticationFilter` → validate JWT → nạp `SecurityContextHolder`
4. `SecurityConfig` + `@PreAuthorize` trên `PetController` kiểm tra role
5. `PetController.createPet()` → `PetService.createPet()` → build entity → `PetRepository.save()`
6. Entity map ngược thành `PetResponse` DTO, bọc trong `ApiResponse`
7. Response trả về `ApiClient`; nếu `401` → tự động refresh token và retry 1 lần

**Luồng lỗi:** Exception từ controller/service → `GlobalExceptionHandler` (`@RestControllerAdvice`) → `BusinessException`/`ResourceNotFoundException` map theo HTTP status khai báo sẵn; `MethodArgumentNotValidException` → map thành field→message với HTTP 400.

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

- **Chỉ `pets` là module hoàn thiện đầu-cuối.** `auth/` và `users/` mới có `entity/`, chưa có `service/controller/repository` — cần bổ sung theo đúng khuôn mẫu `modules/pets/`.
- **Redis đã khai báo hạ tầng nhưng chưa được BE dùng** — chưa có `RedisTemplate`/cache config nào trong code, dù dependency `spring-boot-starter-data-redis` đã có.
- **2 HTTP client song song ở FE:** `shared/api/client.ts` (fetch-based, đầy đủ endpoint, có auto-refresh token) và `shared/api/axios.ts` + `product.api.ts`/`review.api.ts` (axios-based, chỉ phủ một phần endpoint). Trang mới cần biết đang dùng client nào — nên ưu tiên client fetch-based để có sẵn cơ chế refresh 401.
- **2 instance `QueryClient` được tạo độc lập** ở `main.tsx` và `App.tsx` — provider trong `App.tsx` "thắng", cấu hình ở `main.tsx` thực chất không có tác dụng.
- **Chưa có route guard xác thực ở FE:** `App.tsx` mount mọi page không điều kiện; việc kiểm tra đăng nhập/role hiện phải tự làm trong từng page/store.
- **`PetController` đang hardcode current-user** (`getCurrentUserId()`/`getCurrentUserRole()` trả về giá trị cố định `1L`/`"CUSTOMER"`) thay vì đọc từ `SecurityContextHolder` — cần sửa trước khi JWT thật được phát hành cho nhiều user, nếu không mọi kiểm tra "chủ sở hữu" đều vô nghĩa.
- **`GlobalExceptionHandler` lộ message exception gốc** ra response cho lỗi 500 chung — nên đổi thành log server-side + message chung chung cho client.

---

## 8. Tài liệu liên quan

| Tài liệu | Nội dung |
|---|---|
| `docs/01-business-operations.md` | Actor & nghiệp vụ (business, không phải kỹ thuật) |
| `docs/02-business-rules.md` | ~150+ RULE-ID — mọi validation/guard trong code phải trích dẫn đúng RULE-ID |
| `docs/03-state-machines.md` | 17 FSM — nguồn chân lý cho transition state |
| `docs/04-glossary.md` | Ubiquitous Language — tên biến/class/API/event phải khớp |
| `docs/05-domain-model.md` | DDD Aggregates/Entities/VO, Architectural Decision Locks D-01..D-04 |
| `docs/06-erd.md` | ERD & schema — nguồn chân lý cấu trúc CSDL |
| `docs/convention/backend/` | Quy ước code backend chi tiết (naming, layering, exception, FSM pattern, transaction, logging, testing) |
| `docs/INDEX.md` | Mục lục tổng, tóm tắt phase/timeline, các câu hỏi/quyết định còn mở |

---

*Phân tích kiến trúc: 2026-09-08*
