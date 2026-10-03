# System Overview — trạng thái kỹ thuật thực tế

> Mô tả **codebase hiện có**, không phải thiết kế mục tiêu. Nghiệp vụ xem `docs/01`–`05` (tra qua `docs/INDEX.md`); quy ước code xem `docs/convention/backend/`.
> Cập nhật: 2026-10-03, commit `aa209a2` (nhánh `manh`) + migration V1 và dọn cấu hình (chưa commit). Khi thêm module, hạ tầng hoặc migration, sửa mục 3, 4 và 5.

## 1. Tóm tắt

| Phần | Trạng thái |
|---|---|
| Đặc tả | v16, đầy đủ ở `docs/01`–`05` |
| Backend | Đã có tầng `platform/` (config, exception, FSM base, envelope, security tạm, audit). **Chưa có module nghiệp vụ hay controller nào**; entity duy nhất là `AuditLogEntity` của platform |
| Database | `V1__init_schema.sql`: đủ 55 bảng của erd (mục 4b). Chưa có dữ liệu seed |
| Frontend | Dựng theo đặc tả cũ. Màn hình nhân viên chạy trên dữ liệu giả trong trình duyệt, chưa gọi BE thật |

## 2. Thành phần runtime

```
Trình duyệt ──► FE (Vite :5173 khi dev | nginx :3000 trong Docker, /api/ → backend:8080/api/)
                 │ axios, Bearer token
                 ▼
               BE Spring Boot (petcare-api) ──► PostgreSQL 17 (petcare)
                                             ├─► Redis 7 (đã khai báo, chưa dùng)
                                             └─► SMTP Gmail (đã khai báo, chưa dùng)
```

| Môi trường | Cách chạy | BE port | Profile | DB |
|---|---|---|---|---|
| Dev | `docker compose up -d postgres redis` + `mvn spring-boot:run` (trong `BE/`) | `8081` (`SERVER_PORT`) | default | `${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:petcare}`, `postgres/123456`, Flyway bật, `ddl-auto=validate` |
| Docker | `docker compose up -d --build` | `8080` | `docker` | `postgres:5432/petcare`; log JSON; biến lấy từ `.env` gốc (mẫu `.env.example`). **`JWT_SECRET` không có mặc định**: thiếu `.env` thì mọi lệnh `docker compose`, kể cả chỉ dựng postgres/redis ở dòng Dev, đều dừng |
| Test | `mvn test` / `mvn verify` | — | `test` | **Testcontainers** `postgres:17` (`com.petcare.TestcontainersConfiguration`, `@ServiceConnection`): DB sạch mỗi lần chạy, Flyway áp migration thật, `ddl-auto=validate`. **Cần Docker đang chạy.** Surefire/Failsafe ép JVM `Asia/Ho_Chi_Minh` |

Stack: Java 21, Spring Boot 3.5.15, Spring Security, Data JPA, Validation, AOP, Actuator, Mail, Redis, springdoc-openapi, Flyway, jjwt, MapStruct, Lombok, logstash-logback-encoder. Test: JUnit 5, Mockito, spring-security-test, Testcontainers 1.21.4. Flyway 11.7.2.

## 3. Backend — đã có (`BE/src/main/java/com/petcare/`)

| Thành phần | File | Ghi chú cho agent |
|---|---|---|
| Entry | `PetcareApplication` | Loại `UserDetailsServiceAutoConfiguration`; xác thực sẽ do module TK cung cấp |
| Trace | `platform/config/TraceIdFilter`, `TraceContext` | Filter đầu tiên (`HIGHEST_PRECEDENCE`). Header `X-Trace-Id`, MDC key `traceId`. Nhận traceId của client nếu khớp `[A-Za-z0-9-]{1,64}` |
| Thời gian | `platform/config/TimeConfig` | Bean `Clock` theo `Asia/Ho_Chi_Minh` (`BUSINESS_ZONE`). Thời điểm dùng `Instant.now(clock)`, ngày nghiệp vụ dùng `LocalDate.now(clock)`. Hibernate `jdbc.time_zone=UTC`. **Không gọi `now()` không có clock**; test thay bằng `Clock.fixed` |
| CORS | `platform/config/CorsConfig`, `CorsProperties` | `app.cors.allowed-origins`; không dùng credentials (token qua header) |
| OpenAPI | `platform/config/OpenApiConfig` | `/v3/api-docs`, `/swagger-ui.html`. Scheme `bearer-jwt` (`OpenApiConfig.BEARER_SCHEME`) để gắn `@SecurityRequirement` |
| Exception | `platform/exception/*` | `PlatformException` (abstract, mang `ErrorCode`) và 5 lớp con theo convention 04. `BusinessRuleViolationException(ruleId, message)` tự nối `" (BR-…)"` vào message. `AccessDeniedScopeException` trả message chung cho client |
| Mã lỗi | `platform/exception/ErrorCode` | 12 mã, mỗi mã gắn sẵn HTTP status, khớp bảng của convention 04 §4.3 |
| Handler | `platform/exception/GlobalExceptionHandler` | Nơi duy nhất tạo `ErrorResponse`. Lỗi DB do khóa hoặc ràng buộc trả 409 với message chung. Lỗi 4xx khác của Spring giữ đúng status. Lỗi còn lại trả 500 kèm log ERROR |
| FSM | `platform/fsm/Transitionable`, `StateMachineBase` | `allowedTransitions()`, `initialStates()`, `canTransition`, `validateTransition`, `validateInitial`. `fsmName()` lấy tên lớp thật, kể cả khi bị Spring proxy |
| Envelope | `platform/model/ApiResponse`, `PageResponse`, `ErrorResponse` | `ApiResponse.ok(data)`, `ok(data, msg)`, `created(data, msg)`; `PageResponse.of(Page)` với `page` đánh số từ 0 |
| Base entity | `platform/model/TimestampedEntity`, `CreatedAtEntity` | ROOT, PART, REF kế thừa `TimestampedEntity`; LOG kế thừa `CreatedAtEntity`. **Không chứa `id`**, mỗi entity tự khai báo khóa chính |
| Security (tạm) | `platform/security/SecurityConfig`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` | Stateless; CSRF, form login, basic auth và logout tắt. Chỉ mở `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`. **Mọi endpoint khác trả 401** cho tới khi có JWT. Lỗi 401/403 đi qua `HandlerExceptionResolver` về `GlobalExceptionHandler` |
| Logging | `resources/logback-spring.xml` | Profile `docker` log JSON (Logstash); profile khác log dạng chữ kèm `[traceId]` |
| Audit | `platform/audit/AuditRecorder`, `AuditEntry`, `AuditPrincipal`, `AuditLogEntity`, `AuditLogRepository` | Điểm duy nhất ghi `audit_logs` (ADR-0001, convention 08 §8.2–8.3). `record()` `MANDATORY` (cùng tx nghiệp vụ); `recordIndependently()` `REQUIRES_NEW` chỉ cho sự kiện thất bại, ghi lỗi thì log `AUDIT_WRITE_FAILED`. Snapshot là record/Map, cấm entity và khóa nhạy cảm. Actor lấy từ principal `AuditPrincipal` (TK phải implement), không có → hệ thống. Repository chỉ có `save` |
| IP client | `application.yml` `server.forward-headers-strategy: native` | Tomcat `RemoteIpValve` tin `X-Forwarded-For` từ dải nội bộ (ADR-0002); `getRemoteAddr()` là IP client khi đi qua nginx |

Test hiện có (`BE/src/test/java/com/petcare/`):
- `PetcareApplicationTests` (`contextLoads` trên Testcontainers: chứng minh Flyway + `validate` khởi động được).
- `SchemaMigrationIT` (Failsafe, `mvn verify`): đối chiếu catalog với erd/03 — đủ 55 bảng, cột chung theo loại bảng, FK `RESTRICT`, `id` identity ALWAYS, enum `VARCHAR(30)` + mã ASCII, kiểu tiền/giờ/cân nặng/mã chứng từ, tập trạng thái của 15 cột = state machine; hành vi trigger audit, EXCLUDE lưu trú, phiếu thu 0đ.
- `TestcontainersConfiguration`: `@Import` vào mọi `@SpringBootTest` cần DB.
- `TraceIdFilterTest`, `GlobalExceptionHandlerTest`, `StateMachineBaseTest`, `SecurityConfigTest`.
- `platform/audit/AuditRecorderTest` (validate entry, khóa nhạy cảm, actor, IP, ghi độc lập lỗi) và `AuditRecorderIT` (Failsafe, `RANDOM_PORT`: commit/rollback cùng tx, `MANDATORY` ngoài tx, `REQUIRES_NEW` sống sót khi tx ngoài rollback, không FK actor, có `RemoteIpValve`). `audit_logs` không xóa được nên mỗi IT đánh dấu bản ghi bằng `reason` UUID thay vì dọn bảng.
- **`platform/fsm/FsmTransitionTestBase<S>`**: lớp cha bắt buộc cho test FSM. Lớp con khai báo `handler()`, `stateType()`, `expectedTransitions()`, `expectedInitialStates()`, chép độc lập từ bảng 03. Base tự sinh test cho mọi cặp from × to.

## 4. Backend — chưa có (phải xây)

| Hạng mục | Nguồn đặc tả | Ghi chú |
|---|---|---|
| Xác thực JWT, phiên (`sessions`), OTP, RBAC 7 role, phạm vi chi nhánh A05–A08 | BR-TK, BR-QT; erd §1 | Thay `SecurityConfig` tạm. Hiện chưa có lớp nào đọc các key `jwt.*` |
| Màn hình xem audit (UC11) và gọi `AuditRecorder` ở từng thao tác | convention 08 §8.3; BR-QT-15, 16 | Hạ tầng ghi đã có (mục 3). UC11 cần repository đọc riêng trong module QT |
| `notification_outbox` + gửi email/push và thử lại | ST20; erd §11 | Chưa có lớp gửi email nào. `notification_outbox.template_code` có FK tới `notification_templates`, nên phải seed mẫu trước khi ghi outbox |
| Job định kỳ ST01–ST20 (tầng 1–2) | 01 mục B | Chưa có `@Scheduled` nào |
| Seed `system_configs`, `notification_templates`, ADMIN đầu tiên | BR-QT-13, 14; erd L166–195 | Docs chưa có nội dung mẫu, khoảng min–max của phần lớn [CFG], và cách tạo ADMIN đầu tiên — làm cùng TK/QT |
| Toàn bộ `module/<feature>/` | Bảng 2 của `docs/INDEX.md` | Chưa có package `module/`. Không làm module tầng 3 |
| Cơ chế TTL/khóa đồng thời (quota khung giờ, sức chứa chuồng, trừ kho khi thu) | convention 07 nói thuộc `docs/architecture/` | **Chưa quyết định**: cần ADR trước khi cài |

Thứ tự phụ thuộc: mọi endpoint nghiệp vụ cần xác thực và phạm vi chi nhánh, nên TK/QT (cùng `accounts`, `staff_profiles`, `branches`) đi trước. Các luồng sau đều trỏ tới Customer, Pet, Branch và danh mục SP. *(Đây là nhận xét từ phụ thuộc dữ liệu, chưa phải lộ trình đã chốt.)*

## 4b. Database — schema V1 (`BE/src/main/resources/db/migration/V1__init_schema.sql`)

Một file, sắp theo thứ tự phụ thuộc FK, mỗi nhóm ghi `-- erd §n`. Khi viết entity, đọc bảng tương ứng trong erd **và** file này.

- **Quy ước:** PK `id BIGINT GENERATED ALWAYS AS IDENTITY` (entity dùng `GenerationType.IDENTITY`), trừ 9 bảng PK đặc biệt: 1–1 (`staff_profiles`, `kennel_types`, `medical_records`, `boarding_check_ins`), ghép (`branch_services`, `branch_quota_defaults`), chuỗi (`system_configs`, `notification_templates`, `page_contents`). 8 bảng LOG chỉ có `created_at` (`otp_tokens`, `audit_logs`, `weight_records`, `visit_assignments`, `medical_record_addenda`, `care_log_addenda`, `stock_movements`, `notification_outbox`) → kế thừa `CreatedAtEntity`; còn lại kế thừa `TimestampedEntity`. Mọi FK `ON DELETE RESTRICT`.
- **Tên ràng buộc:** `ck_<bảng>_…` (CHECK), `uq_…` (unique, gồm partial unique index), `ix_…`, `ex_…`; FK để Postgres tự đặt `<bảng>_<cột>_fkey`. Client không thấy tên này (`GlobalExceptionHandler` trả 409 message chung).
- **Enum:** mọi cột có CHECK danh sách giá trị là `VARCHAR(30)`, giá trị là mã ASCII của erd L28–37 → entity map `@Enumerated(EnumType.STRING)`.
- **Lệch erd có chủ đích** (erd §13 mục 6–10): `audit_logs` chỉ INSERT bằng trigger (UPDATE/DELETE/TRUNCATE đều lỗi `BR-QT-16`); enum `VARCHAR(30)`; `CREATE EXTENSION btree_gist` cho EXCLUDE của `boarding_bookings`; `audit_logs.actor_account_id` **không có FK** (luôn ghi kèm `actor_email`); `payments.amount >= 0`.
- **Thứ tự xóa cứng** (FK RESTRICT, không CASCADE — app xóa con trước, cùng transaction): BR-TK-08 `otp_tokens` → hồ sơ online → `accounts` (outbox OTP của tài khoản PENDING nên chỉ ghi `recipient_email`); BR-TK-19 `addresses` của hồ sơ online → hồ sơ online → gán `account_id` vào hồ sơ tại quầy (`customers.account_id` UNIQUE); BR-KH-06 `weight_records` → `pets`; BR-KB-04 hoàn kho + `stock_movements` → `vaccinations` → `order_lines`.
- **TBD nghiệp vụ phát hiện khi viết V1** (schema không chặn cách giải nào): (1) BR-TK-19 xóa hồ sơ online làm mất sổ địa chỉ; (2) xóa mũi tiêm ghi nhầm có khôi phục `superseded_at`/Care Task của mũi cũ không; (3) chuyển chủ (BR-KH-08): Care Task `OPEN` vẫn mang `customer_id` chủ cũ.
- **DB cũ:** `baseline-on-migrate` đã tắt. Volume `postgres_data` còn schema/lịch sử Flyway của bản trước reset → backend **không khởi động** (Flyway báo non-empty schema hoặc lệch checksum). Xử lý: `docker compose down -v` (mất dữ liệu dev) rồi dựng lại.

## 5. Luồng request (mục tiêu, đã có nền)

```
TraceIdFilter → SecurityFilterChain → Controller (@Valid, @PreAuthorize; trả ApiResponse<…>)
  → Service (@Transactional; guard BR-… → BusinessRuleViolationException)
  → {Entity}TransitionHandler extends StateMachineBase (validateTransition/validateInitial)
  → Repository (JpaRepository)
Lỗi bất kỳ → GlobalExceptionHandler → ErrorResponse{success,errorCode,message,statusCode,timestamp,traceId}
```

Hệ quả liên aggregate (Phụ lục của 03) chạy trong cùng transaction, do service của aggregate phát sự kiện điều phối. Module chỉ gọi module khác qua service; `platform/` không import `module/`.

## 6. Frontend (`FE/`)

- **Stack:** React 18, Vite, TypeScript, React Router 6, React Query 5, Zustand, react-hook-form + zod, Tailwind 4 + Radix, Vitest, Playwright.
- **Routes** (`src/app/App.tsx`):
  - Trang khách: `/`, `/shop`, `/booking`, `/pets`, `/hotel`…
  - Auth: `/auth/*`.
  - Nhân viên: `/staff`, gồm `pages/staff/*Workspace` cho lễ tân, bác sĩ, grooming.
  - Admin: `/admin/*`, 24 trang.
  - **Nhiều route thuộc phạm vi đã bỏ hoặc tầng 3** (shop/cart/checkout/payment, membership, vouchers, caregivers, refunds, promotions, tenants, incidents, workforce, AI…). Đối chiếu `docs/INDEX.md` trước khi nối API.
- **API:**
  - `shared/api/axios.ts`: baseURL là `VITE_API_URL`, mặc định `http://localhost:8081`. Interceptor gắn bearer token, khi 401 thì refresh một lần. Key lưu token: `access_token` / `refresh_token`.
  - `auth.api.ts`, `product.api.ts`, `review.api.ts` gọi endpoint của BE cũ, **các endpoint này không còn tồn tại**.
  - `clinic.api.ts` chạy trên **`clinic-db.ts`**, một kho dữ liệu giả trong `localStorage`, dùng mã rule của đặc tả v13. Khi BE có endpoint, thay từng hàm bằng lời gọi axios.
- **Khuyết tật đã biết:**
  - Có 2 `QueryClient`, ở `main.tsx` và `App.tsx`; bản trong `App.tsx` thắng.
  - Không có route guard theo đăng nhập hay role.
  - Vite proxy `/api` mặc định trỏ 8080, trong khi axios mặc định gọi thẳng 8081.

## 7. Nợ cấu hình & tài liệu (đừng tin, hãy kiểm tra)

| Chỗ | Vấn đề |
|---|---|
| `application*.yml` | `jwt.*` vẫn chưa có code đọc (giữ cho TK). Các key cũ khác đã xóa. `management.health.mail.enabled=false` cho tới khi có ST20 (SMTP chưa cấu hình làm health 503) — nhớ bật lại |
| `.github/workflows/ci.yml` | Bước `mvn flyway:migrate` không có plugin Flyway trong `pom.xml` → fail. Service Postgres và cờ `-Dflyway.*` giờ thừa (test dùng Testcontainers; runner GitHub có Docker). Trigger `main, develop` trong khi nhánh làm việc là `dev`. Lint chạy với `\|\| true` |
| `BE/BE-TIMELINE.md` | Tiến độ của bản trước khi reset (25 module, `docs/00-requirements.md`), đã lỗi thời |
| `docs/convention/backend/` | Đã cập nhật theo v16 (2026-10-03). Còn TBD, liệt kê ở `INDEX.md` của thư mục đó: tên command, cách trả cảnh báo không chặn, chiến lược khóa đồng thời, cách đọc [CFG] |
| `docs/api/`, `docs/diagrams/` | Đang trống. `docs/adr/` có ADR-0001 (audit), ADR-0002 (IP client) |
| `platform/model/CreatedAtEntity`, `TimestampedEntity` | `created_at`/`updated_at` do `@CreationTimestamp`/`@UpdateTimestamp` đặt theo giờ JVM, không qua bean `Clock` → test với `Clock.fixed` không điều khiển được các cột này |
| `server.forward-headers-strategy: native` | Dải proxy tin cậy gồm cả gateway Docker `172.x`: ở dev (gọi thẳng `:8080`, hoặc browser trên host qua nginx) client giả được `X-Forwarded-For`. Môi trường thật: không publish 8080 hoặc thu hẹp `server.tomcat.remoteip.internal-proxies` (ADR-0002) |
