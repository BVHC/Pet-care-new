# System Overview — trạng thái kỹ thuật thực tế

> Mô tả **codebase hiện có**, không phải thiết kế mục tiêu. Nghiệp vụ: `docs/01`–`05` (tra qua `docs/INDEX.md`); quy ước code: `docs/convention/backend/`; quy tắc toàn hệ thống, công cụ platform: `CLAUDE.md`; thứ tự khóa: `docs/architecture/concurrency-locking.md`; bẫy FE: `docs/architecture/frontend-notes.md`. **Chi tiết hành vi, thứ tự khóa / lỗi và danh sách test nằm trong ADR của từng dòng** — file này chỉ là bản đồ.
> Cập nhật: 2026-11-08 (đối chiếu lại với code tại `8f8542d` = `origin/manh`, hơn `origin/dev` @ `3a65850` một commit). Trạng thái task: `docs/time.md`.
>
> Nhật ký (mới nhất trước, giữ 5 dòng; cũ hơn: `git log` của file này):
> - 2026-11-13 — AI v2 đặc tả **Proposed** (ADR-0134 nạp file + quản trị kho, 0135 ngữ cảnh thú + Gemini trả phí, 0136 kiến thức chung có kiểm soát; `07` §4.7 BR-AI-29…35; `time.md` T88–T93; V52 giữ chỗ). **Chưa cài**: mã chờ Hiến duyệt spec. Kho nguồn `docs/docs_RAG` (21 tài liệu DRAFT) vào repo, script nạp `load-docs-rag.mjs`.
> - 2026-11-10 — T77a (AI-1, ADR-0130, Proposed): module `ai` có phần BE đầu tiên: lớp Gemini, UC93 (ai-v1 #5–#11), FSM #13, ST22 (nạp chỉ mục, đồng bộ phác đồ), ST23; không migration; chưa có chat, chưa nạp KB demo, chưa chạy với Gemini thật.
> - 2026-11-09 — ADR-0124 (nội trú: số ngày gồm cả hai đầu, một dòng Order lúc xuất viện + tiền tạm tính, giữ chuồng cũ khi nhập từ lưu trú, một y lệnh một lần, theo dõi nội trú) và ADR-0125 (bỏ phẫu thuật: UC50, FSM #23, `surgery-v1`). **Code chưa theo** (V37 còn `surgeries`, V41 còn `SURGERY`; FE còn màn phẫu thuật; nội trú chưa có Java): nợ D063, D064.
> - 2026-11-08 — ADR-0122 (Accepted, Dũng đã chốt): bỏ khiếu nại; feedback sẽ không trạng thái, giữ 30 ngày (ST24). **Code chưa theo** (V39 / V40 còn bảng, 6 khóa, 7 mẫu; feedback còn `status`; FE còn trang khiếu nại): nợ D061.
> - 2026-11-08 — Đối chiếu docs với code: FE đã nối refresh token (D055 trả trong code, `3a65850`; chưa chạy với BE thật), T57-fe đã dựng mock + màn hình cho 15 module tầng 3 (mock có 85 khóa [CFG], 70 mẫu thông báo); chuyển nguyên văn thứ tự khóa / quy ước cô lập IT / bẫy FE từ `CLAUDE.md` sang `docs/architecture/{concurrency-locking,testing-notes,frontend-notes}.md`.

## 1. Tóm tắt

| Phần | Trạng thái |
|---|---|
| Đặc tả | v16 `docs/01`–`05`; tầng 3 + AI duyệt T54 (5/11) ở `docs/07`–`10`; hợp đồng giữa module `docs/06-module-contracts.md` (tầng 3: §6b); hợp đồng FE↔BE `docs/api/` (pha 1: 12 module, 232 endpoint; pha 2: 12 module, 113 endpoint chưa cài; sinh từ `docs/api/generator/`) |
| Backend | 14 thư mục `module/`: 12 module pha 1 + `payment`, `shop` (chỉ `api` + placeholder, T55c). 230 handler (`@*Mapping`); `OpenApiContractParityIT` ghim 187 operation trong 10 module có annotation. Bảng module ở `CLAUDE.md` *Current state*; từng feature ở mục 3.2. **Chưa có Java** cho Nhân sự, nội trú, shop, payment, `ai` (phẫu thuật đã bỏ, ADR-0125) |
| Database | V1 = 55 bảng erd; V2 = 52 [CFG] (nay 85 khóa); V3–V48 và V50 seed mẫu / index / CHECK / cột / bảng tầng 3 và AI (mục 4b). Số V chưa xác nhận chéo với bên kia: V22, V23, V27–V32, V36–V41 (BE-1), V24–V26, V33, V42–V48 (BE-2; V42 do BE-1 làm thay); V34–V35 là AI (FE-1). **Số kế tiếp V51**; V50 (BE-1) là index `refresh_tokens (expires_at, id)` của ADR-0114, V49 (cuối dải BE-2, để dành cho ADR-0117 TBD-1 / TBD-2) còn trống — DB đã chạy V50 sẽ fail validate Flyway khi V49 xuất hiện (`out-of-order` tắt). ADMIN đầu tiên không seed: dev tạo bằng SQL, **gồm cả dòng `staff_profiles`** (thiếu thì `GET /api/me` trả 500): `INSERT INTO accounts (email, phone, password_hash, role, status) VALUES ('<email>', '<sđt>', '<bcrypt>', 'ADMIN', 'ACTIVE') RETURNING id;` rồi `INSERT INTO staff_profiles (account_id, full_name) VALUES (<id>, '<họ tên>');` (`branch_id` NULL — BR-QT-03) |
| Frontend | Dựng theo đặc tả cũ. Auth, hồ sơ, catalog, branch, kho, Order / thu ngân, bàn làm việc nhân viên, báo cáo gọi BE thật hoặc mock theo hợp đồng. Refresh token đã nối (D055, `session.store.ts` / `axios.ts`; chưa chạy với BE thật). T57-fe đã dựng mock + màn hình tầng 3 cho 15 module (chạy trên mock, chưa có BE thật) — mục 6 |
| Test | 328 lớp `*Test`, 196 lớp `*IT`. Nền chạy và các test đỏ đã biết: `CLAUDE.md` *Tests baseline* |

## 2. Thành phần runtime

```
Trình duyệt ──► FE (Vite :5173 khi dev | nginx :3000 trong Docker, /api/ → backend:8080/api/)
                 │ axios, Bearer token
                 ▼
               BE Spring Boot (petcare-api) ──► PostgreSQL 17 + pgvector (petcare)
                                             ├─► Redis 7 (chỉ bộ đếm giới hạn tần suất, ADR-0100)
                                             └─► SMTP (worker ST20; dev: Mailpit :1025, UI :8025)
```

| Môi trường | Cách chạy | BE port | Profile | DB |
|---|---|---|---|---|
| Dev | `docker compose up -d postgres redis mailpit` + `mvn spring-boot:run` (trong `BE/`) | `8081` (`SERVER_PORT`) | default | `${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:petcare}`, `postgres/123456`, Flyway bật, `ddl-auto=validate`. Postgres phải có pgvector (V34) |
| Docker | `docker compose up -d --build` | `8080` | `docker` | `postgres:5432/petcare` (ảnh `pgvector/pgvector:pg17`); log JSON; biến từ `.env` gốc (mẫu `.env.example`). **`JWT_SECRET` không có mặc định**: thiếu `.env` thì mọi lệnh `docker compose` dừng. Cron mỗi job là env (`SESSION_CLEANUP_CRON`, `PENDING_ACCOUNT_CLEANUP_CRON`, `NOTIFICATION_OUTBOX_*_CRON`, `NOTIFICATION_IN_APP_CRON`, `NO_SHOW_CRON`, `VACCINE_REMINDER_CRON`…) |
| Test | `mvn test` / `mvn verify` | — | `test` | Testcontainers `pgvector/pgvector:pg17` (`TestcontainersConfiguration`, `@ServiceConnection`), Flyway thật + `V9000` của test (TTL access token 720), `ddl-auto=validate`. **Cần Docker.** Surefire / Failsafe ép `Asia/Ho_Chi_Minh`; `application-test.yml` đặt cron mọi job `"-"` và tắt rate limit |

Stack: Java 21, Spring Boot 3.5.15, Spring Security, Data JPA, Validation, AOP, Actuator, Mail, Redis, springdoc-openapi, Flyway 11.7.2, jjwt, MapStruct, Lombok, logstash-logback-encoder, jsoup. Test: JUnit 5, Mockito, spring-security-test, Testcontainers 1.21.4, GreenMail (SMTP :3025). Chưa có thư viện Java cho AI (spike ở `BE/scripts/spike/`, Node, FE-1).

## 3. Backend — đã có (`BE/src/main/java/com/petcare/`)

### 3.1 Platform (50 lớp, không import `module/*`)

| Thành phần | File | Ghi chú cho agent |
|---|---|---|
| Entry | `PetcareApplication` | Loại `UserDetailsServiceAutoConfiguration`; xác thực do filter JWT + phiên DB |
| Trace | `platform/config/TraceIdFilter`, `TraceContext` | Filter đầu tiên; header `X-Trace-Id` (`[A-Za-z0-9-]{1,64}`), MDC `traceId` |
| Thời gian | `platform/config/TimeConfig` | Bean `Clock` theo `BUSINESS_ZONE`. Không đặt `hibernate.jdbc.time_zone` (cột `TIME` sẽ lệch); không gọi `now()` thiếu clock |
| CORS | `platform/config/CorsConfig`, `CorsProperties` | `app.cors.allowed-origins`; không credentials |
| OpenAPI | `platform/config/{OpenApiConfig, ApiErrorDoc, ApiErrorDocs, ApiErrorDocCustomizer, PublicPathSecurityCustomizer}` | ADR-0040. `/v3/api-docs`, `/swagger-ui.html` public; path public → `security: []`; 401 / 500 tự thêm; 4xx khai bằng `@ApiErrorDoc`. `OpenApiContractParityIT` so 10 module với `docs/api/openapi` (ghim 187) và ghi `BE/target/openapi.json`; branch / catalog chưa annotation (D026) |
| Exception | `platform/exception/*` | `PlatformException` + lớp con (convention 04, gồm `TooManyRequestsException`); `ErrorCode` gắn HTTP status; `GlobalExceptionHandler` là nơi duy nhất tạo `ErrorResponse` (lỗi DB khóa / ràng buộc → 409 message chung; 4xx khác của Spring giữ status; còn lại 500 + log ERROR) |
| FSM | `platform/fsm/{Transitionable, StateMachineBase}` | `fsmName()` lấy tên lớp thật kể cả khi bị proxy; test kế thừa `FsmTransitionTestBase<S>` (chép bảng 03, sinh mọi cặp from × to) |
| Envelope | `platform/model/{ApiResponse, PageResponse, ErrorResponse}` | `ok`, `created`, `accepted`; `PageResponse.of(Page)`, `page` từ 0 |
| Base entity | `platform/model/{TimestampedEntity, CreatedAtEntity}`, `JpaAuditingConfig` | ROOT / PART / REF → `TimestampedEntity`, LOG → `CreatedAtEntity`; không chứa `id`; `created_at` / `updated_at` từ `Clock` (ADR-0015) |
| Mật khẩu | `platform/security/PasswordConfig` | BCrypt 10 (ADR-0009); `BCRYPT_MAX_BYTES = 72` kiểm trước khi băm (> 72 byte → BR-TK-03) |
| Security | `platform/security/{SecurityConfig, JwtTokenService, JwtAuthenticationFilter, SecurityPrincipal, SessionAuthenticator, …}` | ADR-0003, 0005. Stateless, `@EnableMethodSecurity`; JWT HS256 (`JWT_SECRET` ≥ 32 byte) chỉ `sub`, `sid`, `jti`; mỗi request tra phiên DB. `PUBLIC_REQUESTS`: health, info, API docs, `/api/auth/register[/**]`, `/api/auth/login`, `/api/auth/refresh` (ADR-0091), `/api/auth/password/**`, `/api/public/**` — luôn ẩn danh |
| Giới hạn tần suất | `platform/security/{RateLimitFilter, RateLimiter, RedisRateLimiter, RateLimitProperties}` | ADR-0100 (D011). Filter không phải bean, `SecurityConfig` gắn sau `CorsFilter`; đếm `POST` login / register / password theo IP (script Lua trên Redis); vượt ngưỡng → 429 + `Retry-After`; Redis lỗi → fail-open + breaker. `app.rate-limit.*`, tắt ở profile `test`. Nhóm AI (ADR-0110) chưa cài |
| Phạm vi chi nhánh | `platform/security/{BranchScope, AccessScope}` | ADR-0003, 0006. `CHAIN` / `BRANCH` / `OWNER`; `resolve(id)` cho danh sách, `check(id)` cho dòng đã đọc; OWNER luôn 403 (lọc theo chủ) |
| Audit | `platform/audit/{AuditRecorder, AuditEntry, …}` | ADR-0001, 0098. `record()` `MANDATORY`; `recordIndependently()` `REQUIRES_NEW` chỉ cho thất bại, gọi sau rollback. Snapshot là JSON object, cấm khóa nhạy cảm; `audit_logs` chỉ-thêm ở tầng app (trigger đã gỡ ở V31) |
| Mã chứng từ | `platform/sequence/DocumentCodeGenerator` | ADR-0043, 0107. Liên tục theo `(doc_type, period)` trong `document_counters` (V14); trần 999 999 / tháng |
| Job | `platform/config/SchedulingConfig` | ADR-0007. 4 thread, một instance; job ở `module/<m>/job`, cron `app.jobs.<job>.cron`, không `@Transactional`, lỗi log `<JOB>_FAILED`. Job dài chọn phút lệch nhau (ADR-0056 Q11) |
| Sự kiện đồng bộ | `ApplicationEventPublisher` + `@EventListener` | Convention 07 §7.2; `DomainEventTransactionIT`. Đang dùng: `AccountLockedEvent` / `AccountUnlockedEvent` (→ visit), `BranchClinicCancellationEvent`, `PetDeceasedEvent`, `PetOwnerTransferredEvent` (→ appointment, boarding, visit, care). Khai báo cho tầng 3, chưa có listener: `StaffLeftBranchEvent` (identity), `CustomerMergedEvent` (customer) |
| IP client | `server.forward-headers-strategy: native` | ADR-0002 (dải proxy tin cậy gồm cả `172.x` — mục 7) |
| Logging | `resources/logback-spring.xml` | Profile `docker` log JSON; khác: chữ kèm `[traceId]` |

### 3.2 Module (một dòng mỗi feature; chi tiết ở ADR)

| Module · feature | Endpoint / phạm vi | Lớp chính | ADR · nợ · ghi chú |
|---|---|---|---|
| identity · xác thực | mọi request | `SessionAuthenticationService`, `SessionService`, `MustChangePasswordInterceptor`, `fsm/AccountTransitionHandler` | 0003, 0005, 0021, 0091. Một JOIN `sessions`+`accounts`+`staff_profiles` mỗi request, **chỉ đọc**; BR-TK-17 miễn `GET /me`, `POST /me/password`, logout |
| identity · làm mới access token, online (D024) | #39 (`POST /auth/refresh`, public) | `RefreshTokenService`, `SessionService.open`, `SessionCleanupService`, `StaffPresence` | 0091. Access token 10 phút [CFG], refresh token một lần xoay vòng, dùng lại ⇒ hủy phiên + audit; online ⇔ phiên chưa hủy có `access_expires_at > now`; khóa `refresh_tokens → sessions`; dọn phiên xóa token trước. **FE chưa nối (D055)**; D054 dọn token |
| identity · đăng ký, xác thực, gửi lại OTP | #1–3 (UC01–02) | `RegistrationService`, `OtpService` | 0010, 0011, 0013. Khóa `accounts` theo email trước OTP; `noRollbackFor = OtpRejectedException` giữ bộ đếm |
| identity · đăng nhập (ST01) | #4 | `LoginService` (không tx), `LoginAttemptService` | 0019, 0020. BCrypt ngoài connection; 401 chung (BR-TK-10); audit thất bại `recordIndependently` sau rollback |
| identity · đăng xuất, đổi mật khẩu | #5, #9 | `LogoutService`, `ChangePasswordService` | 0021, 0022. Khóa `accounts → sessions`; đổi mật khẩu dùng chung bộ đếm đăng nhập |
| identity · quên / đặt lại mật khẩu | #6, #7 | `PasswordResetService` (không tx) | 0023. Forgot luôn 202 cùng body; mẫu `prepare / issuePrepared / findActive / settleCheckedOtp` |
| identity · `/me`, tự sửa hồ sơ, bác sĩ công khai | #8, #10, #37 | `MeService`, `StaffProfileService`, `PublicVetService` | 0026. Nhân viên thiếu `staff_profiles` = 500 |
| identity · liên kết hồ sơ khách (UC07) | #11–14 | `LinkProfileService` (không tx), `LinkProfileAttemptService` | 0025, 0027. OTP gửi email hồ sơ tại quầy; customer kiểm lại dưới khóa |
| identity · quản trị nhân viên | #15–24 | `StaffAdminService` (facade), `StaffAccountService`, `StaffHierarchyPolicy`, `StaffEmailChangeService` | 0029–0031, 0033, 0034. Khóa `branches` (id ↑) → `accounts`; 403 phân cấp audit sau rollback; BR-QT-08 qua `VisitQueryApi` + `CashierShiftQueryApi` |
| identity · nhân viên có thể gán lượt | #38 (UC45) | `StaffController.listAssignableStaff`, `AssignableStaffService`, `AssignableRole`, `BranchSelectionRequiredException` | 0089 (Proposed). `GET /api/staff/assignable?role=VET/CARETAKER[&branchId]` cho lễ tân, quản lý, SUPER_MANAGER; dùng `StaffDirectoryApi.findAssignableStaff`; mảng không phân trang; SUPER_MANAGER thiếu `branchId` → 400 `VALIDATION_FAILED`; `GET /api/staff` vẫn 403 với lễ tân |
| identity · UC22 sửa hộ email tài khoản khách, liên kết hộ tại quầy | #25–27 | `CustomerAccountController`, `CustomerAccountService` (không tx), `CustomerEmailChangeService`, `CounterLinkService` (không tx), `CounterLinkStepService` | 0097 (Proposed; FE-1 làm thay; mục 9a–c cho BE-2). Chỉ RECEPTIONIST, không phân cấp / phạm vi chi nhánh. #25 / #26 theo khuôn ADR-0034 (mã `CHANGE_EMAIL` gắn `(account, email mới)`, khóa `accounts → otp_tokens`, tài khoản khóa vẫn sửa được, `PENDING` / hồ sơ chưa gắn tài khoản → 400 không mã BR, audit `CUSTOMER_EMAIL_CHANGED`); #27 không OTP, hai tx (đọc hồ sơ rồi khóa `accounts → customers`), chặn `PENDING`, audit `CUSTOMER_PROFILE_LINKED`. `customers.email` không đồng bộ sau #26 |
| identity · độ dài mã OTP trong response | #1, #3, #6, #12, #23, #25 | `OtpSentResponse.codeLength`, `RegistrationResponse.otpCodeLength` | 0096 (Proposed). Giá trị = `code().length()` của mã vừa sinh; forgot trả cùng giá trị cho email lạ (BR-TK-10) |
| identity · tìm / khóa / mở khóa | #28–30 | `AccountLockService`, `PendingWorkCollector` | 0035. Đọc module khác trước lệnh ghi đầu; phát `AccountLockedEvent`; mở khóa flush rồi phát `AccountUnlockedEvent` (visit gỡ `needs_reassign`, ADR-0088 n5) |
| identity · [CFG], mẫu thông báo, audit | #31–36 | `SystemConfigService` (cache, fail-fast), `SystemConfigEditService`, `NotificationTemplateAdminService`, `AuditLogQueryService` | 0004, 0036–0038, 0098. Reload [CFG] sau commit ở facade; IT đổi [CFG] / mẫu phải khôi phục V2 / `default_*`. Nợ D018–D020 |
| identity · API cho module khác | — | `StaffDirectoryService`, `AccountContactService` | 0024, 0052, 0061. `countBranchManagers` kể cả đang bị khóa; `findStaffForShare` khóa `accounts FOR SHARE` |
| identity · jobs | dọn phiên 03:00, dọn refresh token 03:10:11, ST02 `0 */15`, dọn OTP 03:20:17 | `SessionCleanupService`, `RefreshTokenCleanupService`, `PendingAccountCleanupService`, `OtpTokenCleanupService` | 0008, 0013, 0111 (D012: xóa `otp_tokens` hết hạn quá 1 ngày, lô 1000 `SKIP LOCKED`, ngân sách 60 s), 0114 (D054: xóa `refresh_tokens` có `expires_at` cũ hơn 1 ngày; `expires_at` = hạn phiên nên không mất phát hiện dùng lại). Một tài khoản / tx |
| care · outbox, ST20 | — | `NotificationService` (`MANDATORY`), `NotificationDispatchService`, `SmtpBatchSender`, `InAppNotificationDeliveryService` | 0012, 0014, 0016, 0017. HIGH (`OTP_*`, `STAFF_TEMP_PASSWORD`) / NORMAL theo lô / IN_APP; dọn outbox 03:40, dọn `notifications` 03:50:23 (0112); `enqueue` **không** kiểm `required_vars` |
| care · thông báo của tôi (UC88) | care-v1 #5–8 | `MyNotificationService` | 0039, 0099 (V32), 0112 (job dọn đã đọc 30 / chưa đọc 90 ngày; #7 có thể 404 với dòng vừa bị xóa, D057). #9–10 chưa làm (D025) |
| care · Care Task nền | — | `CareTask`, `CareTaskTransitionHandler`, `CareTaskService` (`CareTaskApi`: `create`, `cancelForRevaccination`, `cancelFollowUpTasks`, `cancelPickupOverdue`) | 0079, 0107. `create`: bên gọi khóa `pets FOR SHARE` trước nguồn, READ COMMITTED; 2 loại quá hạn chống trùng bằng `ON CONFLICT DO NOTHING`; hủy hệ thống = một UPDATE `WHERE status = 'OPEN'`, `handled_by` NULL. Index / CHECK ở V22 (0081) |
| care · Care Task của lễ tân | care-v1 #1–4 (UC87) | `CareTaskController`, `CareTaskDeskService`, `CareTaskMapper` | 0080. Chỉ RECEPTIONIST, chi nhánh từ `BranchScope`. Thứ tự: 400 hình thức → 403 role → 404 → 403 chi nhánh → 400 BR-TB-05 → khóa → 409. Danh sách REPEATABLE READ, `status` vắng = `OPEN`, sắp `due_date, id`. Không audit. Nợ D050 |
| care · hủy / chuyển chủ theo sự kiện | — | `CarePetDeceasedListener` (`@Order` 200), `CarePetOwnerTransferredListener` | 0084. Thú mất: mọi task `OPEN` → `CANCELLED` (ST19); chuyển chủ: task `OPEN` sang chủ mới. Cùng tx với customer; khóa `care_tasks` sau `medical_records` |
| customer · hồ sơ khách tự sửa, sổ địa chỉ | customer-v1 #1–7 | `MyCustomerProfileService`, `AddressBookService` | 0028 |
| customer · tại quầy, thú cưng, cân nặng, API | #8–21 | `CounterCustomerService`, `PetService`, `PetLifecycleService`, `CustomerApiService`, `CustomerQueryService`, `PetQueryService`, `PetWeightApiService` | 0041, 0068, 0104 (tra cứu không dấu, V33). Phát `PetDeceasedEvent` / `PetOwnerTransferredEvent`; `findPetForShare` là nghĩa vụ của bên chèn dòng trỏ tới thú |
| catalog · danh mục nhân viên | catalog-v1 #1–18 (UC28–31) | `catalog/{service,controller}`, `CatalogAccess` | 0106. Đọc 5 role nhân viên, ghi chỉ SUPER_MANAGER; audit `PRICE_CHANGED`; trùng tên / SKU → 400 `VALIDATION_FAILED`; SKU, loại, nhóm dịch vụ, loài / loại / mũi của phác đồ không đổi sau khi tạo; hàm sửa khóa trước khi đọc. Swagger chưa annotation (D026) |
| catalog · công khai + `CatalogQueryApi` | #19–22 | `PublicCatalogService`, `CatalogQueryService` | 0071, 0073 (`findVaccineType`, `findProductsByVaccineType`) |
| branch · chi nhánh, giờ, ngày nghỉ, dịch vụ, quota | branch-v1 #1–19 | `BranchService`, `BranchSchedule`, `ScheduleImpactFinder`, `QuotaService`, `BranchQueryService` | 0106. Mọi ghi khóa `branches`; có ảnh hưởng mà không `cancelAffected` → 400 BR-CN-04 / BR-LH-10, có thì phát `BranchClinicCancellationEvent` cùng tx. Giờ mở cửa hiệu lực: `DRAFT` từ hôm nay, `ACTIVE` từ ngày mai. Entity `BranchServiceSetting` = bảng `branch_services`. Quota 0–32767, chỉ MEDICAL / GROOMING. `BranchApi.lockForStaffChange` / `lockForBooking` (0029, 0045) |
| branch · công khai | #20–21 | `PublicBranchService` | 0071 |
| appointment · nền, khung giờ, đặt lịch | appointment-v1 #1–2 | `AppointmentLocks`, `AvailableSlotService`, `AppointmentBookingService` | 0045–0047. `advisory(1001, thú) → branches → appointments`; mã `LH-yyMMdd-<seq>`. D027 chấp nhận (0092), D032 đã trả (0094, V29); MEDICAL gọi `FollowUpApi` (0084) |
| appointment · xem, đổi, hủy | #3–6 | `AppointmentQueryService`, `AppointmentChangeService`, `BookingRestrictionService` | 0048, 0049. Hủy muộn → `evaluateAfterViolation` (advisory 1002 cuối) |
| appointment · hạn chế đặt online | #7–9 | `BookingRestrictionStatusService` | 0055 |
| appointment · API, hủy do phòng khám | — | `AppointmentVisitService`, `AppointmentLookupService`, 3 listener | 0050, 0052, 0057 |
| appointment · ST05, ST03 | `0 */5`, `0 2/5` | `AppointmentNoShowService`, `AppointmentReminderService` | 0053, 0054, 0056. Câu quét lô đầu `findDueForReminderIds` có cận dưới (D053 đã trả) |
| visit · tiếp nhận, hàng đợi, gán / gọi / hủy / gán lại | visit-v1 #1–9, #11 | `VisitCheckInService`, `VisitQueueService`, `VisitDispatchService`, `VisitProgressService`, `VisitReassignService`, `AccountLockedListener` | 0058–0063; mã `TN-yyMM-nnnnn`. Khung khóa chung ở `CLAUDE.md`; ngoại lệ: tiếp nhận không khóa `branches`; gọi lượt khóa `accounts FOR SHARE` của người gọi trước `visits`; sắp lại khóa mọi lượt `WAITING` của chi nhánh trong một câu `ORDER BY id`; gán lại khóa người cũ / mới `FOR SHARE` (id ↑); listener khóa tài khoản `accounts → visits`; hủy lượt `visits → appointments → orders`. Check-in lịch mồ côi sau chuyển chủ → 400 `BR-TN-01` (0092). Nợ D029, D041 (đã trả, V30), D042 |
| visit · bệnh án, cân nặng, hoàn tất, bản bổ sung | #10, #12–15 | `MedicalRecordService`, `VisitCompletionService` | 0066–0070. Ghi bệnh án và cân nặng #14 dừng ở `visits`. Hoàn tất `visits → appointments → orders`, đọc dịch vụ sau khi khóa Order (D045). D043 đã trả một phần (V28) |
| visit · mũi tiêm: nền + `VaccinationQueryApi` | (trong #12/#13) | `Vaccination`, `VaccinationQueryService`, `VaccinationRecordReader` | 0072. Đọc không khóa, tham gia tx bên gọi. Lô null (D046). 0080 thêm `findVaccineTypeIds` |
| visit · gợi ý mũi tiêm | #18 | `VaccinationController`, `VaccinationOptionService` | 0073. Chỉ đọc, mọi trạng thái lượt; chỉ sản phẩm đang kinh doanh còn tồn. **Chưa có:** #21 hồ sơ sức khỏe (T28b, ADR-0108) |
| visit · ghi / xóa mũi tiêm | #19, #20 | `VaccinationRecordService` (`record`, `delete`) | 0074, 0075. `pets FOR SHARE` → advisory 1004 → `visits` → (#19) `products FOR SHARE` → `orders` → lô / (#20) mũi `FOR UPDATE` → xóa mũi → `orders` → lô. #19: mũi cũ `superseded_at`, hủy Care Task `VACCINE_*`; #20: người phụ trách hiện tại, mũi cũ mới nhất hết `superseded`, task đã hủy không mở lại. Không audit. Nợ D046, D047 |
| visit · đơn thuốc: kê #16, xóa dòng #17, `PrescriptionApi` | #16, #17 (+ đọc trong #12/#13) | `PrescriptionController`, `PrescriptionService`, `PrescriptionApiService`, `PrescriptionItem` | 0076–0078. #16: `pets FOR SHARE` → `visits` → `products FOR SHARE` → `orders` (`addDrugLine`), không lô; tồn = lô chưa hết hạn, không trừ thuốc đã kê (A28); đủ tồn bỏ qua `shortageResolution`; audit `PRESCRIPTION_ITEM_ADDED`. #17: `visits` → dòng đơn `FOR UPDATE` → DELETE dòng đơn **trước** → `orders` (`removeDrugLine`) → audit `PRESCRIPTION_ITEM_REMOVED`; VET phụ trách, lượt `IN_PROGRESS`. `markExternalPurchaseAtPayment` = một UPDATE, khóa `orders → prescription_items` (không khóa `visits`), chạy được trên bệnh án đã khóa (BR-BH-04); dùng bởi sales #11 (0103) |
| visit · ST04 nhắc tái chủng / tái khám | cron `30 10 7-20 * * *`, `30 40 7-20 * * *` | `VaccineReminderJob` / `FollowUpReminderJob`, `VaccineReminderService` / `FollowUpReminderService` (`remind`: một bản ghi một tx), `DueDateWindow`, `ReminderChannels` | 0082. Chọn mũi chưa thay thế + chưa nhắc, lượt `COMPLETED` / bệnh án đã khóa chưa nhắc chưa hủy; kênh BR-TB-02 (tài khoản → IN_APP + EMAIL, email hồ sơ → EMAIL, không → Care Task `*_DUE`); thú mất → cờ. Link `_APP` `/booking?petId=&branchId=` (D051). Hoãn khi `AppointmentQueryApi.hasBookedMedical`, lịch hủy / `NO_SHOW` thì nhắc quay lại (0088 n5) |
| visit · hủy nhắc tái khám (`FollowUpApi`) | — | `FollowUpCancellationService`, `VisitPetDeceasedListener` (`@Order` 100) | 0084. Chỉ bệnh án đã khóa: hủy nhắc chờ gửi, rồi task `FOLLOW_UP_DUE` đã nhắc. Check-in EXAM gọi `cancelPendingFollowUps`; đặt lịch MEDICAL gọi `cancelRemindedFollowUpTasks` (khóa bệnh án chờ nhắc, chỉ hủy task đã sinh — 0088 n5); thú mất chỉ hủy nhắc |
| visit · ST18 quá hạn tái chủng | cron `30 25 7-20 * * *` | `VaccineOverdueJob`, `VaccineOverdueService` (`raise`) | 0083, V23. `next_due_date <= hôm nay − N` [CFG]; khóa như ST04 → cờ `overdue_handled_at` (đúng 1 dòng) → `CareTaskApi.create` `VACCINE_OVERDUE`; thú mất / không SĐT chỉ đặt cờ |
| sales · Order lõi, bán lẻ | sales-v1 #1–8, #10 | `VisitOrderService` (`VisitOrderApi`), `OrderLineService`, `RetailOrderService`, `OrderLinePolicy` | 0044, 0051. Khóa `orders` trước khi đọc; không có `@Version` (D045); `lines` nạp theo lô 20. #9 ở dòng boarding |
| sales · chuyển thuốc mua ngoài lúc thu | #11 | `OrderExternalPurchaseService` | 0103. Chi nhánh đọc không khóa → khóa Order → `PrescriptionApi.markExternalPurchaseAtPayment` → xóa dòng → flush → audit `ORDER_LINE_MOVED_EXTERNAL`; chỉ dòng `DRUG` của Order VISIT `PENDING` còn thiếu tồn trong Order đó. Bấm đúp → 404 |
| sales · thu ngân, ST13 | #12–21 | `CashierShiftService`, `PaymentService`, `CashierShiftAutoCloseService`, `PendingOrderDigestService` | 0064, 0103, 0107, V19. Thu tiền: ca `FOR SHARE` → chi nhánh Order (đọc không khóa) → Order một câu → lô một câu → `document_counters`. ST13: hạn tính trước, chỉ ca đã đến hạn mới `SKIP LOCKED`, `closed_at` sau khóa; ca ngoài giờ không còn tự chốt 00:00 |
| inventory · kho | inventory-v1 #1–17 | `StockReceiptService`, `StockAdjustmentService`, `StockLedger`, `FefoAllocator`, `StockQueryService`, `StockIssueService`, `InventoryService` | 0042, 0043, 0088 (inv), V14. `StockLedger` là nơi duy nhất đổi `stock_lots.quantity`; hết hạn = `expiry_date < hôm nay`. Dòng tồn và lô có cờ `expiringSoon` theo [CFG] `stock.expiry_warning_days` (không trả N; Proposed). `StockReportApi`, ST08 `0 0 7` (0087) |
| boarding · chuồng, đặt chỗ, gia hạn, hủy, ST05 | boarding-v1 #1–12 | `KennelService`, `BoardingBookingService`, `BoardingChangeService`, `BoardingNoShowService`, 3 listener | 0065, V20. Cảnh báo mũi tiêm qua `VaccinationQueryApi` |
| boarding · nhận thú, hủy vì hết chuồng, nhật ký, bất thường | #13–15, #19–22 | `BoardingCheckInService`, `CareLogService`, `BoardingManagerNotices`, `CareLogAbnormalNotices` | 0085, V24. Mũi bắt buộc qua `VaccinationQueryApi`, cân qua `PetApi.recordWeight(INTAKE)`; bất thường → outbox khách + lễ tân cùng tx |
| boarding · bắt đầu trả, giao thú, thú mất, ST15 | #16–18 + sales #9 | `BoardingCheckoutService`, `BoardingOrderService` (`BoardingOrderApi`), `OrderCancellationService.abortCheckout`, `BoardingOverdueService`, `BoardingOverdueJob` | 0086, 0107, V25. `openOrReuse` kiểm lại `PAID` sau khóa; giao thú / thú mất gọi `CareTaskApi.cancelPickupOverdue`; ST15 `0 1/5`, mốc quá hạn = 12:00 ngày trả, Care Task `PICKUP_OVERDUE` |
| content · trang, bài viết, feedback | content-v1 #1–22 | `PageContentService`, `ArticleService`, `ArticleHtml` (jsoup), `FeedbackSubmitService`, `FeedbackReviewService` | 0071, V21. Feedback: advisory 1003 theo khách |
| report · báo cáo UC89 | report-v1 #1–7 | `ReportController`, `ReportService`, `ReportPeriod`; `SalesReportService`, `StockReportService`, `FeedbackReportService`, `AppointmentReportService`, `VaccinationReportService` | 0087, 0090 (`pendingCount` của #5, Proposed). Không bảng, không khóa; phạm vi → kỳ → API → gắn tên; kỳ nửa mở giờ VN; `SalesReportApi.lostRevenue` chỉ `UNPAID` |
| ai · tri thức UC93, FSM #13 | ai-v1 #5–#11 (7/13) | `KnowledgeController`, `KnowledgeService`, `fsm/AiDocumentTransitionHandler`, `AiDocument` (+ `AiChunk`, `AiChatLog`, `AiItemProfile`) | 0130 (Accepted 12/11). Khóa `FOR NO KEY UPDATE` trước khi đọc, audit `AI_DOCUMENT_*`; nháp thiếu nguồn lưu `source_note = ''` (V34); `ai_chunks.embedding` không ánh xạ entity, ghi bằng native `CAST(? AS vector)` |
| ai · ST22 nạp chỉ mục, ST23, lớp Gemini | cron `45 */5 * * * *` (ST22), `29 0 4 * * *` (ST23) | `KnowledgeIndexer` (luồng nền `ai-index`), `KnowledgeIndexWriter.swap`, `DocumentChunker`, `CatalogProtocolSync` / `Writer`, `KnowledgeIndexJob`, `ChatLogCleanupJob`, `GeminiClient` | 0130. Nhúng ngoài transaction, hoán đổi đoạn một transaction (kiểm lại phiên bản dưới khóa), kích hoạt `AFTER_COMMIT`; khóa `GEMINI_API_KEY` chỉ ở header; profile `test` tắt ST22; chưa có chat (T77b) |

### 3.3 Interface chéo tầng 3 chưa cài (placeholder, T55b / T55c)

Quy ước (CLAUDE.md *Backend architecture*): ném `UnsupportedOperationException`, không trả giá trị giả. Dạng `default` ở interface cũ (không phá stand-in `new Xxx() {…}` của bên kia); dạng lớp `@Component` ở interface mới. Mỗi cái có `*Tier3PlaceholderTest` (`PlaceholderAssert`); `Tier3Be2ContractWiringIT` kiểm nối bean. Chữ ký là đề xuất, chỉ đổi qua ADR của task cài. Nguồn: `docs/06` §6b, ADR-0113, 0117.

| Interface (bên cung cấp) | Method placeholder | Bản thật trước (06 §6b) |
|---|---|---|
| `StaffDirectoryApi` · `AppointmentQueryApi` · `VisitQueryApi` · `CareTaskApi` · `BoardingQueryApi` · `CashierShiftQueryApi` · `OrderQueryApi` · `StockQueryApi` | `countActiveByBranch` · `countBookedByBranch` · `countOpenByBranch` (cả `VisitQueryApi` lẫn `OrderQueryApi`) · `reassignBranch(from, to)` · `countActiveStaysByBranch` · `hasOpenShift` · `sumQuantityByBranch` — số đếm tồn đọng khi tạm ngừng / đóng chi nhánh (#13) | 7/12 (T75) |
| `AppointmentQueryApi` · `VaccinationQueryApi` · `PetQueryApi` · `CatalogQueryApi` · `BranchQueryApi` (AI nhóm 1, #15) | `findUpcomingBookedOfCustomer` · `findDoseHistory` · `findPetProfile`, `findLivingPetsOfCustomer` · `listActiveProtocols` · `findEmergencyBranches`, `findServiceIdsEnabledAnywhere` | đã cài thật 9/11 (AI-0, ADR-0129) |
| `CatalogQueryApi` · `StockQueryApi` · `VisitQueryApi` · `BoardingQueryApi` · `OrderQueryApi` (AI nhóm 2, #16) | `listRecommendable` · `findProductIdsInStockAnywhere` · `findLastServiceDates`, `countCompletedByService` · `findStaysOfPet`, `countStaysByKennelType` · `findLastPurchaseDates`, `sumSoldUnitsByProduct` | đã cài thật 9/11 (AI-0, ADR-0129) |
| `StockApi` · `StockQueryApi` · `CustomerQueryApi` (thương mại điện tử, #3) | `reserve`, `release`, `consumeReservation`, `restock` · `findAvailableQuantity` · `findAddress` | 17/11 (T63) |
| `OnlineOrderApi` (shop, **mới**, #4) | `markPaid`, `markPaymentCancelled` — lớp `OnlineOrderApiPlaceholder` | 24/11 (T65) |
| `PaymentGatewayClient` (payment, **mới**) | cổng thanh toán trung lập (BR-TG-11) — lớp `PaymentGatewayClientPlaceholder`; bản cài VNPay sandbox hoặc `FakeGatewayClient` | T65; `refund` T73 |
| `StockApi` · `OrderQueryApi` · `CashierShiftApi` (sales, **mới**) (trả hàng, #8) | `restockToOriginalLot`, `discardReturned` · `findLinesForReturn` · `recordRefund` — lớp `CashierShiftApiPlaceholder` | 30/11 (T73) |
| `StockApi` · `StockQueryApi` · `VaccinationQueryApi` (chuyển kho, #12) | `transferOut`, `transferIn`, `transferCancel` · `findMovementsOfLot` · `findByStockLot` | 3/12 (T74) |
| Sự kiện (không placeholder) | `StaffLeftBranchEvent` (#1, workforce T58), `CustomerMergedEvent` (#14, T75): đã khai báo, chưa có bên phát / nhận | |

Còn thiếu hẳn (chưa khai báo ở T55, `docs/06` §6b #2, #5, #6, #9–11): `CatalogQueryApi.findMaterialNorms`, `VisitOrderApi.addServiceLine`, `KennelApi.occupyForTreatment`, `AdmissionQueryApi`, `VisitApi.startTreatmentVisit`, `StockApi.issueMaterials`.

## 4. Backend — chưa có

| Hạng mục | Nguồn | Ghi chú |
|---|---|---|
| visit #21 hồ sơ sức khỏe thú (T28b) | BR-KH-07, ADR-0108 | D045 (khóa Order trước khi đọc) |
| care #9–10 cài đặt nhận thông báo | UC88 | D025, chờ PO |
| Tầng 3 BE-1: Nhân sự (T58–T59), nội trú (T69; ADR-0124; phẫu thuật T61 đã bỏ, ADR-0125), feedback không trạng thái + ST24 (T70, ADR-0122; khiếu nại đã bỏ), trừ vật tư ST07 (T71) | `docs/09`, ADR-0113 | Schema đã có (V36–V41), chưa có Java |
| Tầng 3 BE-2: phí / định mức vật tư, thương mại điện tử, thanh toán online, trả hàng – hoàn tiền, kho mở rộng, vòng đời chi nhánh, gộp khách, bình luận bài viết | `docs/08`, ADR-0116, 0117 | Schema đã có (V42–V48), interface ở mục 3.3. T62 phí / định mức, T63–T64 đơn online, T65 thanh toán, T73 trả hàng, T74 kho mở rộng, T75 chi nhánh / khách / bài viết |
| Phân hệ AI (chatbot RAG, gợi ý) | `docs/07`, ADR-0109, 0110 | Có V34–V35 (4 bảng, 18 khóa `ai.*`); chưa có module `ai`, rate limit nhóm AI, job ST22–23 (T77 chatbot, T78 gợi ý) |
| Gọi `AuditRecorder` ở module còn lại | BR-QT-15 | Snapshot JSON object |
| Công thức báo cáo G11 | BR-BC | Chờ PO (ADR-0090 chỉ thêm `pendingCount`) |

## 4b. Database — migration (`BE/src/main/resources/db/migration/`)

Không bao giờ sửa migration đã chạy (checksum). Số V dùng chung với BE-2; **kế tiếp V51** (V50 = BE-1, ADR-0114; V49 để trống cho BE-2). "Chưa xác nhận" = bên kia chưa đồng ý số (note ở `docs/06` §6).

| V | Nội dung (ADR) | Chủ |
|---|---|---|
| 1 | `V1__init_schema.sql` — 55 bảng erd, sắp theo FK. Lệch erd có chủ đích (§13): enum `VARCHAR(30)`, `btree_gist` cho EXCLUDE lưu trú, `audit_logs.actor_account_id` không FK, `payments.amount >= 0` (trigger chỉ-thêm của `audit_logs` đã gỡ ở V31) | |
| 2 | 52 [CFG] khớp `ConfigKey` (0004) | |
| 3–12 | Mẫu `OTP_REGISTER` (V3), index ST20 HIGH (V4, 0012), `pending_expires_at` (V5, 0013), index IN_APP (V6, 0014), 38 index FK → `accounts` (V7, 0018), mẫu đăng nhập / mật khẩu (V8, 0019), `OTP_PROFILE_LINK` + `ix_otp_tokens_customer_id` (V9, 0025), `ix_addresses_customer_id` (V10, 0028), mẫu quản trị nhân viên (V11, 0029), mẫu khóa tài khoản (V12, 0035) | BE-1 |
| 13–15 | Index FK `pets` / `customers` (V13, 0041); `document_counters` + index kho (V14, 0042–0043); `ix_order_lines_order_id` (V15, 0044) | BE-2 |
| 16–18 | `appointment_code_seq` (V16, 0045; **checksum đổi ở `571e0fe`** — cách sửa DB cũ ở `CLAUDE.md` *Persistence*); mẫu hủy do phòng khám (V17, 0052), nhắc lịch (V18, 0056) | BE-1 |
| 19–21 | Thu ngân (V19, 0064); ST05 lưu trú (V20, 0065); seed `page_contents` (V21, 0071) | BE-2 |
| 22 | Mẫu `VACCINE_REMINDER`, `FOLLOW_UP_REMINDER` (+`_APP`); index `care_tasks (vaccination_id)`, `(medical_record_id)`; 3 CHECK trạng thái ↔ cột xử lý (0081, D049). Chưa xác nhận | BE-1 |
| 23 | `vaccinations.overdue_handled_at` (không backfill) + partial index `ix_vaccinations_overdue_scan (next_due_date, id)` (0083). Chưa xác nhận | BE-1 |
| 24–26 | Mẫu `CARE_LOG_ABNORMAL`, `BOARDING_NO_KENNEL_APP` + 2 index (V24, 0085); 3 index boarding / orders + mẫu `BOARDING_OVERDUE*`, `BOARDING_CAPACITY_WARNING_APP` (V25, 0086); mẫu `STOCK_ALERT_DIGEST_APP` (V26, 0087). Chưa xác nhận | BE-2 |
| 27 | Bảng `refresh_tokens` (+ UNIQUE partial một token còn dùng mỗi phiên), `sessions.access_expires_at`, bỏ `accounts.last_seen_at`, [CFG] `session.access_ttl_minutes` (10, 5–720) thay `staff.online_window_minutes` (0091, D024). Profile test thêm `V9000` (`src/test/resources/db/test-migration`, TTL 720) | BE-1 |
| 28 | 4 index FK (`medical_record_addenda.medical_record_id`, `prescription_items.medical_record_id`, `vaccinations.visit_id`, `.vaccine_type_id`) + CHECK `ck_prescription_items_external_reason_tied` (0093, D043). Hai trigger chặn sửa bệnh án khóa chấp nhận không làm | BE-1 |
| 29, 30 | `ix_appointments_branch_slot (branch_id, slot_date, slot_start)` (0094, D032); `ix_visits_branch_checked_in_at (branch_id, checked_in_at)` (0095, D041) | BE-1 |
| 31, 32 | `ix_audit_logs_action_created_at` + gỡ 2 trigger chỉ-thêm của `audit_logs` và `forbid_audit_log_change()` (0098, D021); `ix_notifications_account_created (account_id, created_at DESC, id DESC)` (0099, D022) | BE-1 |
| 33 | `unaccent` + `pg_trgm`, `f_unaccent` IMMUTABLE, GIN `ix_customers_full_name_unaccent_trgm`, `ix_pets_name_unaccent_trgm` (0104) | BE-2 |
| 34, 35 | Extension `vector` (cần superuser → ảnh `pgvector/pgvector:pg17`) + `ai_documents`, `ai_chunks`, `ai_chat_logs`, `ai_item_profiles`; 18 khóa [CFG] `ai.*` (DECIMAL đầu tiên, khóa ba đoạn) (0110, T55a) | FE-1 |
| 36–39 | Nhân sự: `shift_definitions`, `shift_assignments`, `shift_requests` (V36); khám tại nhà: cột `is_home_visit`, `home_address`, `home_contact_phone`, `home_fee` của `appointments` (3 CHECK) + `visits.is_home_visit`, `surgeries`, `visit_material_usages` (V37); nội trú: `admissions`, `admission_orders`, `admission_observations` (V38); khiếu nại: `complaints`, `complaint_messages` (V39) (0113, T55b) | BE-1 |
| 40, 41 | 8 khóa [CFG] (`complaint.*` 6, `workforce.*` 2 ⇒ 78) + 40 mẫu thông báo tầng 3 (tên mã / biến là giả định) (V40); `services.medical_type` thêm `SURGERY`, `INPATIENT`, `order_lines.line_type` thêm `HOME_FEE` (V41; bảng của BE-2, chưa xác nhận) (0113) | BE-1 |
| 42 | 4 CHECK của `orders` cho nguồn `ONLINE` (không Visit / lưu trú, `payment_id` luôn NULL, `cancel_type` thêm `ONLINE_CUSTOMER`, `ONLINE_EXPIRED`); tiền online ở `payment_transactions`; Java `OrderSource` chưa có ONLINE (T63) (0116) | BE-2 (BE-1 làm thay) |
| 43–48 | Phí / định mức vật tư (V43), đơn online + giỏ + giữ hàng (V44), `payment_transactions` (V45), trả hàng – hoàn tiền (V46), chuyển kho + kiểm kê (V47), vòng đời chi nhánh + gộp khách + bình luận bài viết (V48); 7 khóa [CFG] ⇒ 85 (V44–V46, V48) (0117, T55c). V49 để dành cho TBD-1 / TBD-2 | BE-2 |
| 50 | Index `refresh_tokens (expires_at, id)` cho job dọn refresh token (0114, trả D054); V49 bỏ trống cho BE-2 | BE-1 |

- **PK đặc biệt (9 bảng):** 1–1 (`staff_profiles`, `kennel_types`, `medical_records`, `boarding_check_ins`), ghép (`branch_services`, `branch_quota_defaults`), chuỗi (`system_configs`, `notification_templates`, `page_contents`). Bảng LOG chỉ `created_at`. Tên ràng buộc `ck_ / uq_ / ix_ / ex_`.
- **Thứ tự xóa cứng** (app xóa con trước, cùng tx): BR-TK-08 `otp_tokens` → hồ sơ online → `accounts`; BR-TK-19 `addresses` → hồ sơ online → gán `account_id` (flush trước, `uq_customers_account_id` không deferrable); BR-KH-06 `weight_records` → `pets`; BR-KB-04 hoàn kho + `stock_movements` → `vaccinations` → `order_lines`; BR-KB-03 `prescription_items` → `order_lines` (dòng `DRUG`, bệnh án giữ).
- **TBD còn mở:** Care Task `VACCINE_*` đã hủy lúc ghi mũi không mở lại khi xóa mũi — PO chưa xác nhận (ADR-0075, visit-v1 Q4); hủy nhắc tái khám không khôi phục khi lịch bị hủy / `NO_SHOW` và không xét bệnh án chưa khóa (D052, ADR-0084).
- **DB cũ:** `baseline-on-migrate` tắt; volume còn lịch sử Flyway lạ → không khởi động → `docker compose down -v` (hỏi trước, mất dữ liệu). Volume do `postgres:17` tạo cần xử lý collation khi đổi sang ảnh pgvector (`CLAUDE.md` *Gotchas*).

## 5. Luồng request

```
TraceIdFilter → [RateLimitFilter cho POST login/register/password] → SecurityFilterChain (JwtAuthenticationFilter: verify JWT → SessionAuthenticator tra phiên → principal)
  → MustChangePasswordInterceptor (BR-TK-17, /api/**)
  → Controller (@Valid, @PreAuthorize; ApiResponse<…>)
  → Service (@Transactional; BranchScope; guard → BusinessRuleViolationException) → [TransitionHandler] → Repository
Lỗi → GlobalExceptionHandler → ErrorResponse{success,errorCode,message,statusCode,timestamp,traceId}
Job: Scheduler (4 thread) → {Việc}Job.run() (không tx, MDC traceId) → Service (tx theo bản ghi / lô); lỗi log <JOB>_FAILED
```

## 6. Frontend (`FE/`)

- **Stack:** React 18, Vite, TypeScript, React Router 6, React Query 5, Zustand, react-hook-form + zod, Tailwind 4 + Radix, Vitest, Playwright. Chi tiết, bẫy và quy ước mock: `docs/architecture/frontend-notes.md`.
- **Routes** (`src/app/App.tsx`): công khai; mọi role đã đăng nhập; khách A02 (`/booking`, `/pets`, `/review`, `/boarding-bookings…` sau `RequireLinkDecision`); bàn làm việc `/staff/*` và back office `/admin/*` trong `StaffShell`. Chặn route: `RequireRole`. Nhiều route thuộc phạm vi cũ / tầng 3 (shop, membership, vouchers…) — đối chiếu `docs/INDEX.md` trước khi nối API.
- **API:** một client `shared/api/axios.ts` (`VITE_API_URL`, mặc định 8081), lỗi `ApiError` + `ruleId`. Mock theo hợp đồng `shared/api/mock/` bật ở `npm run dev` (`VITE_API_MOCK=false` để gọi BE thật). Đã chạy với BE thật: bàn làm việc, thu tiền, báo cáo, Care Task, content (T49, 27/10 – 1/11); mock lưu trú #13–22 chưa chạy với BE thật. Trang quản trị nội dung (`/admin/page-contents`, `/admin/articles`), góp ý (`/admin/feedbacks`, `/review`) và Care Task (`/admin/care-tasks`) chạy mock. `clinic.api.ts` / `clinic-db.ts` / `useClinic.ts` là mã chết (xóa ở T57). `mock-api.test.ts` cố ý assert mock trả 404 cho path chưa có mock (`/api/visits/1/not-yet-built`).
- **Chạy với BE thật (T49):** BE `SERVER_PORT=8082`, `VITE_API_MOCK=false VITE_API_URL=http://localhost:8082 npx vite`; BE **không trả `branchId` ở `POST /auth/login`** (dùng `useStaffBranch()`). Cách dựng lại và màn bị BE chặn: `docs/testcases/T49-fe.md` §D, §F.
- **Khuyết tật:** 2 `QueryClient` (`main.tsx`, `App.tsx` thắng); Vite proxy 8080 ≠ axios 8081; refresh token đã nối (D055) nhưng chưa chạy với BE thật.

## 7. Nợ cấu hình & tài liệu (đừng tin, hãy kiểm tra)

| Chỗ | Vấn đề |
|---|---|
| `application*.yml` | `open-in-view: false` (ADR-0020); `accept-float-as-int: false` (ADR-0032); access token 10 phút + refresh (ADR-0091, [CFG] `session.access_ttl_minutes`); mail health bật → chạy BE ngoài Docker cần `mailpit`. Tham số job `app.jobs.*` và `app.rate-limit.*` là vận hành, không phải [CFG] |
| `.github/workflows/ci.yml` | Trigger `main, dev`; PR chạy kiểm conflict marker + compile + unit + FE; `integration-tests` chỉ push `main` / chạy tay; lint còn `\|\| true` (ADR-0121, trả D060; chưa chạy thật trên Actions) |
| `.gitignore` `docs/dept` | Sổ nợ chỉ có trên máy BE-1; điều BE-2 cần ghi ở `06-module-contracts.md` §6 |
| `docs/convention/backend/` | TBD còn mở: tên command, cách trả cảnh báo không chặn, khóa sức chứa chuồng |
| `server.forward-headers-strategy: native` | Dải proxy tin cậy gồm gateway Docker `172.x`: ở dev client giả được `X-Forwarded-For`. Môi trường thật: không publish 8080 hoặc thu hẹp `internal-proxies` (ADR-0002) |
| Redis | Cổng publish, không mật khẩu (D056, chấp nhận — ADR-0115) |
| ADR trùng số | `0088` có hai file (BE-2 `inventory-expiring-soon-flag`, BE-1 `n5-unlock-flag-follow-up-deferral-stopped-service`); ADR-0113/0116/0117 còn Proposed (0110 đã Accepted 12/11) |
