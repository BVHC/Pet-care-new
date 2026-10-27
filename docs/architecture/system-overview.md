# System Overview — trạng thái kỹ thuật thực tế

> Mô tả **codebase hiện có**, không phải thiết kế mục tiêu. Nghiệp vụ: `docs/01`–`05` (tra qua `docs/INDEX.md`); quy ước code: `docs/convention/backend/`. **Chi tiết hành vi, thứ tự khóa / lỗi và danh sách test nằm trong ADR của từng dòng** — file này chỉ là bản đồ.
> Cập nhật: 2026-10-27 — T39 phần 3 + 4: ST04 nhắc tái chủng / tái khám (ADR-0082). Trạng thái task: `docs/time.md`.
>
> Nhật ký (mới nhất trước; cũ hơn: `git log` của file này):
> - 2026-10-27 — T39 phần 3 + 4: ST04 — 2 job ở `visit` (`VaccineReminderJob`, `FollowUpReminderJob`, mỗi giờ 07–20h), cửa sổ `[hôm nay, hôm nay + N]` [CFG] (ngày đã qua bỏ qua), mỗi bản ghi một tx: `pets FOR SHARE` → nguồn `SKIP LOCKED` → cờ `*_reminded_at` (đúng 1 dòng) → outbox (BR-TB-02) hoặc Care Task `*_DUE` cho chủ hiện tại; thú mất → đặt cờ, không gửi; không API / migration mới, test V22 đã sửa (D049 trả); ADR-0082, nợ D051 (FE chưa đọc link), D029 có hệ quả thật; unit 5273/5273; IT xanh: nhóm V22 89 (`SchemaMigrationIT`, `CareTask*IT`, `VaccinationRecordIT`, `VaccinationDeleteIT`), ST04 35 (`VaccineReminderIT` 17, `FollowUpReminderIT` 13, `VaccineReminderRaceIT` 4, `ReminderScanIndexIT` 1); **chưa chạy:** `ReminderScheduleIT`, hồi quy IT trọn nhóm visit / care + parity / boarding / appointment (lượt chạy bị dừng vì thiếu RAM sau 232 ca visit xanh) và smoke Docker ST04.
> - 2026-10-27 — T39 phần 2: `V22` seed 4 mẫu nhắc ST04 (`VACCINE_REMINDER`, `FOLLOW_UP_REMINDER` + `_APP`), index `care_tasks (vaccination_id)`, `(medical_record_id)`, 3 CHECK trạng thái ↔ cột xử lý (D049); chỉ file SQL (test sửa ở phần 3); chờ BE-2 xác nhận số V22.
> - 2026-10-27 — T39 phần 1: care-v1 #1–4 (`GET /api/care-tasks[/{id}]`, `POST …/complete`, `…/cancel`), chỉ RECEPTIONIST và chỉ chi nhánh của mình; BR-TB-05 kiểm ở service (400), khóa `care_tasks FOR NO KEY UPDATE` sau khi phân quyền bằng `branch_id` vô hướng; ghép tên / SĐT / loại vaccine theo lô (thêm `VaccinationQueryApi.findVaccineTypeIds`); không migration (ADR-0080; D050 mở, D049 giữ mở); unit 5206, IT care + parity 128, visit + boarding 454, smoke Docker 18/18.
> - 2026-10-27 — T39 phần 0: entity `CareTask` + FSM 03 §10 + `CareTaskRepository` (thay `CareTaskWriteRepository`); `CareTaskApi` đủ (`create` chống trùng quá hạn bằng `ON CONFLICT DO NOTHING`, `cancelFollowUpTasks`); hủy do hệ thống ghi `cancel_reason` + `handled_at`; không endpoint / migration (ADR-0079; D047 trả, D049 mở); unit + IT care xanh.
> - 2026-10-27 — T38 phần 3: tích hợp, không đổi `src/main`, không ADR / migration mới; IT `PrescriptionIT` thêm chuỗi thu từ chối BR-BH-04 → mua ngoài (stand-in sales #11, D048) → thu lại → bệnh án hiện lý do; unit 5134, IT 2203 theo nhóm (chỉ 2 ca đỏ đã biết), smoke Docker #16 / #17 → hoàn tất → thu tiền 27/27; báo FE T46 (mock #16 / #17).

## 1. Tóm tắt

| Phần | Trạng thái |
|---|---|
| Đặc tả | v16, `docs/01`–`05`; hợp đồng giữa module `docs/06-module-contracts.md`; hợp đồng FE↔BE `docs/api/` (12 module, 230 endpoint, sinh từ `docs/api/generator/`) |
| Backend | Bảng module ở `CLAUDE.md` *Current state*; từng feature ở mục 3. Không còn `*Placeholder` nào trong `src/main` (2026-10-25) |
| Database | V1 = 55 bảng của erd, V2 = 52 [CFG], V3–V22 seed mẫu / index / CHECK (mục 4b). V22 chờ BE-2 xác nhận; **V23** đề nghị giữ cho BE-1 (D043), BE-2 từ V24 (thống nhất trước khi tạo). ADMIN đầu tiên không seed: dev tạo bằng SQL, **gồm cả dòng `staff_profiles`** (thiếu dòng này `GET /api/me` trả 500): `INSERT INTO accounts (email, phone, password_hash, role, status) VALUES ('<email>', '<sđt>', '<bcrypt>', 'ADMIN', 'ACTIVE') RETURNING id;` rồi `INSERT INTO staff_profiles (account_id, full_name) VALUES (<id>, '<họ tên>');` (`branch_id` NULL — BR-QT-03) |
| Frontend | Dựng theo đặc tả cũ. Auth, hồ sơ, catalog, branch, kho, Order / thu ngân và bàn làm việc nhân viên (lễ tân, phòng khám, grooming — T33–T37) gọi BE hoặc mock theo hợp đồng; `clinic-db` (localStorage) chỉ còn nuôi KPI dashboard và mã chết — mục 6. Kê đơn (visit-v1 #16–17) chưa nối: FE T46 |

## 2. Thành phần runtime

```
Trình duyệt ──► FE (Vite :5173 khi dev | nginx :3000 trong Docker, /api/ → backend:8080/api/)
                 │ axios, Bearer token
                 ▼
               BE Spring Boot (petcare-api) ──► PostgreSQL 17 (petcare)
                                             ├─► Redis 7 (đã khai báo, chưa dùng)
                                             └─► SMTP (worker ST20; dev: Mailpit :1025, UI :8025)
```

| Môi trường | Cách chạy | BE port | Profile | DB |
|---|---|---|---|---|
| Dev | `docker compose up -d postgres redis mailpit` + `mvn spring-boot:run` (trong `BE/`) | `8081` (`SERVER_PORT`) | default | `${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:petcare}`, `postgres/123456`, Flyway bật, `ddl-auto=validate` |
| Docker | `docker compose up -d --build` | `8080` | `docker` | `postgres:5432/petcare`; log JSON; biến từ `.env` gốc (mẫu `.env.example`). **`JWT_SECRET` không có mặc định**: thiếu `.env` thì mọi lệnh `docker compose` dừng. Cron mỗi job là env (`SESSION_CLEANUP_CRON`, `PENDING_ACCOUNT_CLEANUP_CRON`, `NOTIFICATION_OUTBOX_*_CRON`, `NOTIFICATION_IN_APP_CRON`, `NO_SHOW_CRON`…) |
| Test | `mvn test` / `mvn verify` | — | `test` | Testcontainers `postgres:17` (`TestcontainersConfiguration`, `@ServiceConnection`), Flyway thật, `ddl-auto=validate`. **Cần Docker.** Surefire / Failsafe ép `Asia/Ho_Chi_Minh`; `application-test.yml` đặt cron mọi job `"-"` |

Stack: Java 21, Spring Boot 3.5.15, Spring Security, Data JPA, Validation, AOP, Actuator, Mail, Redis, springdoc-openapi, Flyway 11.7.2, jjwt, MapStruct, Lombok, logstash-logback-encoder, jsoup. Test: JUnit 5, Mockito, spring-security-test, Testcontainers 1.21.4.

## 3. Backend — đã có (`BE/src/main/java/com/petcare/`)

### 3.1 Platform

| Thành phần | File | Ghi chú cho agent |
|---|---|---|
| Entry | `PetcareApplication` | Loại `UserDetailsServiceAutoConfiguration`; xác thực do filter JWT + phiên DB |
| Trace | `platform/config/TraceIdFilter`, `TraceContext` | Filter đầu tiên; header `X-Trace-Id` (`[A-Za-z0-9-]{1,64}`), MDC `traceId` |
| Thời gian | `platform/config/TimeConfig` | Bean `Clock` theo `BUSINESS_ZONE`. Không đặt `hibernate.jdbc.time_zone` (cột `TIME` sẽ lệch); không gọi `now()` thiếu clock |
| CORS | `platform/config/CorsConfig`, `CorsProperties` | `app.cors.allowed-origins`; không credentials |
| OpenAPI | `platform/config/{OpenApiConfig, ApiErrorDoc, ApiErrorDocs, ApiErrorDocCustomizer, PublicPathSecurityCustomizer}` | ADR-0040. `/v3/api-docs`, `/swagger-ui.html` public; path public → `security: []`; 401 / 500 tự thêm; 4xx khai bằng `@ApiErrorDoc`. `OpenApiContractParityIT` so 9 module với `docs/api/openapi` (162 endpoint) và ghi `BE/target/openapi.json`; branch / catalog chưa annotation (D026) |
| Exception | `platform/exception/*` | `PlatformException` + 5 lớp con (convention 04); `ErrorCode` 12 mã gắn HTTP status; `GlobalExceptionHandler` là nơi duy nhất tạo `ErrorResponse` (lỗi DB khóa / ràng buộc → 409 message chung; 4xx khác của Spring giữ status; còn lại 500 + log ERROR) |
| FSM | `platform/fsm/{Transitionable, StateMachineBase}` | `fsmName()` lấy tên lớp thật kể cả khi bị proxy; test kế thừa `FsmTransitionTestBase<S>` (chép bảng 03, sinh mọi cặp from × to) |
| Envelope | `platform/model/{ApiResponse, PageResponse, ErrorResponse}` | `ok`, `created`, `accepted`; `PageResponse.of(Page)`, `page` từ 0 |
| Base entity | `platform/model/{TimestampedEntity, CreatedAtEntity}`, `JpaAuditingConfig` | ROOT / PART / REF → `TimestampedEntity`, LOG → `CreatedAtEntity`; không chứa `id`; `created_at` / `updated_at` từ `Clock` (ADR-0015) |
| Mật khẩu / OTP | `platform/security/PasswordConfig` | BCrypt 10 (ADR-0009); `BCRYPT_MAX_BYTES = 72` kiểm trước khi băm (> 72 byte → BR-TK-03) |
| Security | `platform/security/{SecurityConfig, JwtTokenService, JwtAuthenticationFilter, SecurityPrincipal, SessionAuthenticator, …}` | ADR-0003, 0005. Stateless, `@EnableMethodSecurity`; JWT HS256 (`JWT_SECRET` ≥ 32 byte) chỉ `sub`, `sid`, `jti`; mỗi request tra phiên DB. `PUBLIC_REQUESTS`: health, info, API docs, `/api/auth/register[/**]`, `/api/auth/login`, `/api/auth/password/**`, `/api/public/**` — luôn ẩn danh |
| Phạm vi chi nhánh | `platform/security/{BranchScope, AccessScope}` | ADR-0003, 0006. `CHAIN` / `BRANCH` / `OWNER`; `resolve(id)` cho danh sách, `check(id)` cho dòng đã đọc; OWNER luôn 403 (lọc theo chủ) |
| Audit | `platform/audit/{AuditRecorder, AuditEntry, …}` | ADR-0001. `record()` `MANDATORY`; `recordIndependently()` `REQUIRES_NEW` chỉ cho thất bại, gọi sau rollback. Snapshot là JSON object, cấm khóa nhạy cảm; `audit_logs` chỉ INSERT (trigger) |
| Mã chứng từ | `platform/sequence/DocumentCodeGenerator` | ADR-0043. Liên tục theo `(doc_type, period)` trong `document_counters` (V14) |
| Job | `platform/config/SchedulingConfig` | ADR-0007. 4 thread, một instance; job ở `module/<m>/job`, cron `app.jobs.<job>.cron`, không `@Transactional`, lỗi log `<JOB>_FAILED`. Job dài chọn phút lệch nhau (ADR-0056 Q11) |
| Sự kiện đồng bộ | `ApplicationEventPublisher` + `@EventListener` | Convention 07 §7.2; chứng minh ở `DomainEventTransactionIT`. Sự kiện: `AccountLockedEvent` (→ visit), `BranchClinicCancellationEvent`, `PetDeceasedEvent`, `PetOwnerTransferredEvent` (→ appointment, boarding) |
| IP client | `server.forward-headers-strategy: native` | ADR-0002 (dải proxy tin cậy gồm cả `172.x` — mục 7) |
| Logging | `resources/logback-spring.xml` | Profile `docker` log JSON; khác: chữ kèm `[traceId]` |

### 3.2 Module (một dòng mỗi feature; chi tiết ở ADR)

| Module · feature | Endpoint / phạm vi | Lớp chính | ADR · nợ · ghi chú |
|---|---|---|---|
| identity · xác thực | mọi request | `SessionAuthenticationService`, `SessionService`, `MustChangePasswordInterceptor`, `fsm/AccountTransitionHandler` | 0003, 0005, 0021. Một JOIN `sessions`+`accounts`+`staff_profiles` mỗi request; `last_seen_at` ≤ 1 lần/60 s (`SKIP LOCKED`); BR-TK-17 miễn `GET /me`, `POST /me/password`, logout |
| identity · đăng ký, xác thực, gửi lại OTP | #1–3 (UC01–02) | `RegistrationService`, `OtpService` | 0010, 0011, 0013. Khóa `accounts` theo email trước OTP; `noRollbackFor = OtpRejectedException` giữ bộ đếm |
| identity · đăng nhập (ST01) | #4 | `LoginService` (không tx), `LoginAttemptService` | 0019, 0020. BCrypt ngoài connection; 401 chung (BR-TK-10); audit thất bại `recordIndependently` sau rollback |
| identity · đăng xuất, đổi mật khẩu | #5, #9 | `LogoutService`, `ChangePasswordService` | 0021, 0022. Thứ tự `accounts → sessions`; đổi mật khẩu dùng chung bộ đếm đăng nhập |
| identity · quên / đặt lại mật khẩu | #6, #7 | `PasswordResetService` (không tx) | 0023. Forgot luôn 202 cùng body; mẫu `prepare / issuePrepared / findActive / settleCheckedOtp` để BCrypt ngoài khóa |
| identity · `/me`, tự sửa hồ sơ, bác sĩ công khai | #8, #10, #37 | `MeService`, `StaffProfileService`, `PublicVetService` | 0026. Nhân viên thiếu `staff_profiles` = 500 |
| identity · liên kết hồ sơ khách (UC07) | #11–14 | `LinkProfileService` (không tx), `LinkProfileAttemptService` | 0025, 0027. OTP gửi email hồ sơ tại quầy; customer kiểm lại dưới khóa |
| identity · quản trị nhân viên | #15–24 | `StaffAdminService` (facade), `StaffAccountService`, `StaffHierarchyPolicy`, `StaffEmailChangeService` | 0029–0031, 0033, 0034. Khóa `branches` (id ↑) → `accounts`; 403 phân cấp audit sau rollback; BR-QT-08 qua `VisitQueryApi` + `CashierShiftQueryApi` |
| identity · tìm / khóa / mở khóa | #28–30 | `AccountLockService`, `PendingWorkCollector` | 0035. Đọc module khác trước lệnh ghi đầu; phát `AccountLockedEvent`; mở khóa không gỡ `needs_reassign` (D042) |
| identity · [CFG], mẫu thông báo, audit | #31–36 | `SystemConfigService` (cache, fail-fast), `SystemConfigEditService`, `NotificationTemplateAdminService`, `AuditLogQueryService` | 0004, 0036, 0037, 0038. Reload [CFG] sau commit ở facade; IT đổi [CFG] / mẫu phải khôi phục V2 / `default_*`. Nợ D018–D021 |
| identity · API cho module khác | — | `StaffDirectoryService`, `AccountContactService` | 0024, 0052, 0061. `countBranchManagers` kể cả đang bị khóa; `findStaffForShare` khóa `accounts FOR SHARE` |
| identity · jobs | dọn phiên 03:00, ST02 `0 */15` | `SessionCleanupService`, `PendingAccountCleanupService` | 0008, 0013. Một tài khoản / tx, `SKIP LOCKED` |
| care · outbox, ST20 | — | `NotificationService` (`MANDATORY`), `NotificationDispatchService`, `SmtpBatchSender`, `InAppNotificationDeliveryService` | 0012, 0014, 0016, 0017. HIGH (`OTP_*`, `STAFF_TEMP_PASSWORD`) / NORMAL theo lô / IN_APP; dọn outbox 03:40; `enqueue` **không** kiểm `required_vars`. Nợ D019, D023 |
| care · thông báo của tôi (UC88) | care-v1 #5–8 | `MyNotificationService` | 0039. Nợ D022, D024; #9–10 chưa làm (D025) |
| care · Care Task nền | — | `CareTask`, `CareTaskTransitionHandler`, `CareTaskRepository`, `CareTaskService` (`CareTaskApi`: `create`, `cancelForRevaccination`, `cancelFollowUpTasks`) | 0079. `create`: bên gọi khóa `pets FOR SHARE` trước nguồn, READ COMMITTED; 2 loại quá hạn chống trùng bằng `ON CONFLICT DO NOTHING` (trả id cũ dù trạng thái nào); hủy hệ thống = một UPDATE `WHERE status = 'OPEN'`, `handled_by` NULL. Index / CHECK ở V22 (0081; D049 đã trả) |
| care · Care Task của lễ tân | care-v1 #1–4 (UC87) | `CareTaskController`, `CareTaskDeskService`, `CareTaskMapper`, `CareTask.complete / cancel` | 0080. Chỉ RECEPTIONIST, chi nhánh từ `BranchScope`. Thứ tự: 400 hình thức → 403 role → 404 → 403 chi nhánh → 400 BR-TB-05 → khóa → 409. Danh sách REPEATABLE READ, `status` vắng = `OPEN`, sắp `due_date, id`; tên khách / thú / SĐT / loại vaccine ghép theo lô; PICKUP_OVERDUE lấy SĐT khẩn qua `findBooking` từng đặt chỗ. Không audit. Nợ D050 |
| customer · hồ sơ khách tự sửa, sổ địa chỉ | customer-v1 #1–7 | `MyCustomerProfileService`, `AddressBookService` | 0028 (BE-1 tạo khung entity `Customer`) |
| customer · tại quầy, thú cưng, cân nặng, API | #8–21 | `CounterCustomerService`, `PetService`, `PetLifecycleService`, `CustomerApiService`, `CustomerQueryService`, `PetQueryService`, `PetWeightApiService` | 0041, 0068. Phát `PetDeceasedEvent` / `PetOwnerTransferredEvent`; `findPetForShare` là nghĩa vụ của bên chèn dòng trỏ tới thú |
| catalog · danh mục nhân viên | catalog-v1 #1–18 (UC28–31) | `catalog/{service,controller}`, `CatalogAccess` | Không ADR. Đọc 5 role nhân viên, ghi chỉ SUPER_MANAGER; audit `PRICE_CHANGED`; trùng tên / SKU → `DuplicateCatalogEntryException` (400 `VALIDATION_FAILED`); SKU, loại sản phẩm, nhóm dịch vụ, loài / loại / mũi của phác đồ không đổi sau khi tạo (catalog-v1 A1, A3); tắt loại vaccine không tắt phác đồ. Swagger chưa annotation (D026) |
| catalog · công khai + `CatalogQueryApi` | #19–22 | `PublicCatalogService`, `CatalogQueryService` | 0071; 0073 (BE-1 thêm `findVaccineType`, `findProductsByVaccineType` ở cuối file) |
| branch · chi nhánh, giờ, ngày nghỉ, dịch vụ, quota | branch-v1 #1–19 | `BranchService`, `BranchSchedule`, `ScheduleImpactFinder`, `QuotaService`, `BranchQueryService` | Không ADR riêng. Mọi ghi khóa `branches`; có ảnh hưởng mà không `cancelAffected` → 400 BR-CN-04 / BR-LH-10, có thì phát `BranchClinicCancellationEvent` cùng tx. Giờ mở cửa hiệu lực: `DRAFT` từ hôm nay, `ACTIVE` từ ngày mai (branch-v1 A2). Entity `BranchServiceSetting` = bảng `branch_services` (đặt khác tên docs/04 để không trùng class service); chưa có dòng = chưa bật. Quota 0–32767, chỉ MEDICAL / GROOMING, quota riêng chỉ trên khung sẽ sinh. `BranchApi.lockForStaffChange` / `lockForBooking` (BE-1 viết, 0029, 0045) |
| branch · công khai | #20–21 | `PublicBranchService` | 0071 |
| appointment · nền, khung giờ, đặt lịch | appointment-v1 #1–2 | `AppointmentLocks`, `AvailableSlotService`, `AppointmentBookingService` | 0045–0047. `advisory(1001, thú) → branches → appointments`; mã `LH-yyMMdd-<seq>`. Nợ D027, D029, D032 |
| appointment · xem, đổi, hủy | #3–6 | `AppointmentQueryService`, `AppointmentChangeService`, `BookingRestrictionService` | 0048, 0049. Hủy muộn → `evaluateAfterViolation` (advisory 1002 cuối) |
| appointment · hạn chế đặt online | #7–9 | `BookingRestrictionStatusService` | 0055 |
| appointment · API, hủy do phòng khám | — | `AppointmentVisitService`, `AppointmentLookupService`, 3 listener | 0050, 0052, 0057 |
| appointment · ST05, ST03 | `0 */5`, `0 2/5` | `AppointmentNoShowService`, `AppointmentReminderService` | 0053, 0054, 0056. **2 IT đỏ đã biết** ở `AppointmentReminderIT` (thiếu cận dưới của `findDueForReminderIds`) |
| visit · tiếp nhận, hàng đợi, gán / gọi / hủy / gán lại | visit-v1 #1–9, #11 | `VisitCheckInService`, `VisitQueueService`, `VisitDispatchService`, `VisitProgressService`, `VisitReassignService`, `AccountLockedListener` | 0058–0063; mã `TN-yyMM-nnnnn`. Khung khóa chung ở `CLAUDE.md`; ngoại lệ từng luồng: tiếp nhận không khóa `branches`; gọi lượt khóa `accounts FOR SHARE` của chính người gọi trước `visits`; sắp lại khóa mọi lượt `WAITING` của chi nhánh trong một câu `ORDER BY id`; gán lại khóa người cũ và người mới `FOR SHARE` (id ↑); listener khóa tài khoản chạy `accounts → visits`; hủy lượt `visits → appointments → orders`. Nợ D029, D041, D042 |
| visit · bệnh án, cân nặng, hoàn tất, bản bổ sung | #10, #12–15 | `MedicalRecordService`, `VisitCompletionService` | 0066–0070. Ghi bệnh án và cân nặng #14 dừng ở `visits` (`PetApi.recordWeight` không nâng khóa thú). Hoàn tất `visits → appointments → orders`, đọc dịch vụ sau khi khóa Order (D045). Nợ D043 |
| visit · mũi tiêm: nền + `VaccinationQueryApi` | (bên trong #12/#13) | `Vaccination`, `VaccinationQueryService`, `VaccinationRecordReader` | 0072. Đọc không khóa, tham gia tx bên gọi. Lô null (D046). 0080 thêm `findVaccineTypeIds` (care ghép tên loại) |
| visit · gợi ý mũi tiêm | #18 | `VaccinationController`, `VaccinationOptionService`, `VaccinationOptionsMapper` | 0073. Chỉ đọc, mọi trạng thái lượt; chỉ sản phẩm đang kinh doanh còn tồn. **Chưa có:** #21 hồ sơ sức khỏe (T28b) |
| visit · ghi / xóa mũi tiêm | #19, #20 | `VaccinationRecordService` (`record`, `delete`), `VaccinationOptionService.requireUsableType`; care: `CareTaskService` | 0074, 0075. `pets FOR SHARE` → advisory 1004 (thú) → `visits` → (#19) `products FOR SHARE` → `orders` → lô / (#20) mũi `FOR UPDATE` → xóa mũi → `orders` → lô. #19: mũi cũ `superseded_at`, hủy Care Task `VACCINE_*`; #20: người phụ trách hiện tại, mũi cũ mới nhất hết `superseded`, task đã hủy không mở lại. Không audit. Nợ D046, D047 |
| visit · đơn thuốc: kê #16, xóa dòng #17, `PrescriptionApi` | #16, #17 (+ đọc trong #12/#13) | `PrescriptionController`, `PrescriptionService` (`add`, `remove`), `PrescriptionApiService`, `PrescriptionItem` (`prescribe` / `externalAtPrescribe`), `PrescriptionItemRepository`, `PrescriptionItemReader` | 0076–0078. #16: `pets FOR SHARE` → `visits` → `products FOR SHARE` → `orders` (`addDrugLine`), không advisory, không lô; tồn = lô chưa hết hạn, không trừ thuốc đã kê (A28); đủ tồn bỏ qua `shortageResolution`, giảm về 0 → 400; bệnh án rỗng tạo kèm; audit `PRESCRIPTION_ITEM_ADDED`. #17: `visits` → dòng đơn `FOR UPDATE` → DELETE dòng đơn **trước** → `orders` (`removeDrugLine`, bỏ qua dòng mua ngoài) → audit `PRESCRIPTION_ITEM_REMOVED`; VET phụ trách hiện tại, lượt `IN_PROGRESS`; bệnh án rỗng giữ nguyên. `markExternalPurchaseAtPayment` = một UPDATE, khóa `orders → prescription_items` (không khóa `visits`), chạy được trên bệnh án đã khóa (BR-BH-04); IT `PrescriptionIT` chứng minh chuỗi thu từ chối → mua ngoài → thu lại bằng stand-in cho sales #11 (D048). #12/#13: 2 câu cố định. Nợ D043, D048 |
| visit · ST04 nhắc tái chủng / tái khám | cron `30 10 7-20 * * *` (tái chủng), `30 40 7-20 * * *` (tái khám) | `VaccineReminderJob` / `FollowUpReminderJob`, `VaccineReminderService` / `FollowUpReminderService` (`remind` một bản ghi một tx), `DueDateWindow`, `ReminderChannels` | 0082. Chọn mũi chưa thay thế + chưa nhắc + lượt `COMPLETED` / bệnh án đã khóa chưa nhắc chưa hủy; kênh BR-TB-02 (tài khoản → IN_APP + EMAIL, email hồ sơ → EMAIL, không → Care Task `*_DUE` chi nhánh của mũi / lượt, chủ hiện tại); thú mất → cờ (tái khám: `follow_up_cancelled_at`). Link `_APP` `/booking?petId=&branchId=` (D051). Tại-nhiều-nhất-một-lần, ST20 thử lại. Nợ D029 (chưa hủy khi đã đặt lịch), ST19 chưa làm |
| sales · Order lõi, bán lẻ | sales-v1 #1–8, #10 | `VisitOrderService` (`VisitOrderApi`), `OrderLineService`, `RetailOrderService`, `OrderLinePolicy` | 0044, 0051. Khóa `orders` trước khi đọc; `SalesOrder` không có `@Version` (D045). Chưa có #9, #11 |
| sales · thu ngân, ST13 | #12–21 | `CashierShiftService`, `PaymentService`, `CashierShiftAutoCloseService`, `PendingOrderDigestService` | 0064, V19. Thu tiền: ca `FOR SHARE` → Order một câu → lô một câu → `document_counters` |
| inventory · kho | inventory-v1 #1–17 | `StockReceiptService`, `StockAdjustmentService`, `StockLedger`, `FefoAllocator`, `StockQueryService`, `StockIssueService` | 0042, 0043, V14. `StockLedger` là nơi duy nhất đổi `stock_lots.quantity`; hết hạn = `expiry_date < hôm nay`. Chưa có `StockReportApi`, ST08 (T44) |
| boarding · chuồng, đặt chỗ, gia hạn, hủy, ST05 | boarding-v1 #1–12 | `KennelService`, `BoardingBookingService`, `BoardingChangeService`, `BoardingNoShowService`, 3 listener | 0065, V20. Cảnh báo mũi tiêm qua `VaccinationQueryApi`. Chưa có #13–22 nhận / trả thú, nhật ký, ST15 (T42, T43); `activeOrderId` null |
| content · trang, bài viết, feedback | content-v1 #1–22 | `PageContentService`, `ArticleService`, `ArticleHtml` (jsoup), `FeedbackSubmitService`, `FeedbackReviewService` | 0071, V21. Feedback: advisory 1003 theo khách. Chưa có `FeedbackReportApi` (T44) |
| report | — | Chỉ interface `*ReportApi` | T44 |

## 4. Backend — chưa có (phải xây)

| Hạng mục | Nguồn | Ghi chú |
|---|---|---|
| identity #25–27 (UC22: sửa hộ email / liên kết hồ sơ tại quầy của khách) | BR-TK-16, 19 | Dùng mẫu OTP của ADR-0023 (BCrypt ngoài khóa) |
| visit #21 (T28b) | BR-KH-07 | D045 (khóa Order trước khi đọc) |
| care: ST18, ST19, listener thú mất / đổi chủ phía care, `FollowUpApi` | BR-TB-01…06 | T39 phần sau; trả D029; cài đặt nhận thông báo D025 |
| sales #9, #11, `BoardingOrderApi` | BR-LT-09, BR-BH-04 | T43; #11 làm được từ 26/10 (`PrescriptionApi` có từ ADR-0076); chuỗi thu → mua ngoài → thu lại đã có IT bằng stand-in (D048) |
| boarding #13–22, ST15 | BR-LT-05…12 | T42, T43; khóa sức chứa chuồng chưa có ADR (convention 07 §7.3) |
| report, ST08 | BR-BC-01…04, BR-KO-07 | T44 |
| Gọi `AuditRecorder` ở module còn lại | BR-QT-15 | Snapshot JSON object |

## 4b. Database — migration (`BE/src/main/resources/db/migration/`)

Không bao giờ sửa migration đã chạy (checksum). Số V dùng chung với BE-2.

| V | File | Nội dung (ADR) |
|---|---|---|
| 1 | `V1__init_schema.sql` | 55 bảng erd, sắp theo FK, nhóm `-- erd §n`. Lệch erd có chủ đích (§13 mục 6–10): trigger chỉ-INSERT `audit_logs`, enum `VARCHAR(30)`, `btree_gist` cho EXCLUDE lưu trú, `audit_logs.actor_account_id` không FK, `payments.amount >= 0` |
| 2 | `V2__seed_system_configs.sql` | 52 [CFG] khớp `ConfigKey` (0004) |
| 3, 4 | `V3__seed_notification_template_otp_register.sql`, `V4__st20_notification_outbox.sql` | `OTP_REGISTER`; index luồng HIGH (0012) |
| 5, 6 | `V5__account_pending_expires_at.sql`, `V6__in_app_notification_index.sql` | `pending_expires_at` + CHECK + index (0013); index IN_APP (0014) |
| 7 | `V7__account_fk_indexes.sql` | 38 index FK → `accounts` (0018) |
| 8, 9 | `V8__seed_notification_templates_login_password.sql`, `V9__otp_profile_link.sql` | Mẫu đăng nhập / mật khẩu (0019); `OTP_PROFILE_LINK` + `ix_otp_tokens_customer_id` (0025) |
| 10 | `V10__address_customer_index.sql` | `ix_addresses_customer_id` (0028) |
| 11, 12 | `V11__seed_staff_admin_templates.sql`, `V12__seed_account_lock_templates.sql` | Mẫu quản trị nhân viên (0029), khóa tài khoản `_APP` (0035) |
| 13–15 | `V13__pet_customer_fk_indexes.sql`, `V14__inventory_counters_indexes.sql`, `V15__order_lines_index.sql` | BE-2: index FK `pets` / `customers` (0041, trả D013); `document_counters` + index kho (0042, 0043); `ix_order_lines_order_id` (0044) |
| 16 | `V16__appointment_code_sequence.sql` | `appointment_code_seq` (0045). Checksum đổi ở `571e0fe` — cách sửa DB cũ ở `CLAUDE.md` *Persistence* |
| 17, 18 | `V17__seed_appointment_clinic_cancel_templates.sql`, `V18__seed_appointment_reminder_templates.sql` | Mẫu hủy do phòng khám (0052), nhắc lịch (0056) |
| 19–21 | `V19__cashier_shift_templates_indexes.sql`, `V20__boarding_no_show_index_templates.sql`, `V21__seed_page_contents.sql` | BE-2: thu ngân (0064), ST05 lưu trú (0065), `page_contents` (0071) |
| 22 | `V22__care_reminder_templates_care_task_guards.sql` | BE-1: mẫu `VACCINE_REMINDER`, `FOLLOW_UP_REMINDER` (+`_APP`); index `care_tasks (vaccination_id)`, `(medical_record_id)`; 3 CHECK trạng thái ↔ cột xử lý (0081, D049). Test đã sửa, xanh (phần 3) |

- **PK đặc biệt (9 bảng):** 1–1 (`staff_profiles`, `kennel_types`, `medical_records`, `boarding_check_ins`), ghép (`branch_services`, `branch_quota_defaults`), chuỗi (`system_configs`, `notification_templates`, `page_contents`). 8 bảng LOG chỉ `created_at`. Tên ràng buộc `ck_ / uq_ / ix_ / ex_`.
- **Thứ tự xóa cứng** (app xóa con trước, cùng tx): BR-TK-08 `otp_tokens` → hồ sơ online → `accounts`; BR-TK-19 `addresses` → hồ sơ online → gán `account_id` (flush trước, `uq_customers_account_id` không deferrable); BR-KH-06 `weight_records` → `pets`; BR-KB-04 hoàn kho + `stock_movements` → `vaccinations` → `order_lines`; BR-KB-03 `prescription_items` → `order_lines` (dòng `DRUG`, bệnh án giữ).
- **TBD còn mở:** Care Task `VACCINE_*` đã hủy lúc ghi mũi không mở lại khi xóa mũi — PO chưa xác nhận (ADR-0075, visit-v1 Q4); chuyển chủ: Care Task `OPEN` vẫn mang `customer_id` chủ cũ.
- **DB cũ:** `baseline-on-migrate` tắt; volume còn lịch sử Flyway lạ → không khởi động → `docker compose down -v` (hỏi trước, mất dữ liệu).

## 5. Luồng request

```
TraceIdFilter → SecurityFilterChain (JwtAuthenticationFilter: verify JWT → SessionAuthenticator tra phiên → principal)
  → MustChangePasswordInterceptor (BR-TK-17, /api/**)
  → Controller (@Valid, @PreAuthorize; ApiResponse<…>)
  → Service (@Transactional; BranchScope; guard → BusinessRuleViolationException) → [TransitionHandler] → Repository
Lỗi → GlobalExceptionHandler → ErrorResponse{success,errorCode,message,statusCode,timestamp,traceId}
Job: Scheduler (4 thread) → {Việc}Job.run() (không tx, MDC traceId) → Service (tx theo bản ghi / lô); lỗi log <JOB>_FAILED
```

## 6. Frontend (`FE/`)

- **Stack:** React 18, Vite, TypeScript, React Router 6, React Query 5, Zustand, react-hook-form + zod, Tailwind 4 + Radix, Vitest, Playwright.
- **Routes** (`src/app/App.tsx`): công khai; mọi role đã đăng nhập; khách A02 (`/booking`, `/pets`, `/review` sau `RequireLinkDecision`); bàn làm việc `/staff/*` và back office `/admin/*` trong `StaffShell`. Chặn route: `RequireRole` (chưa đăng nhập → login, `mustChangePassword` → đổi mật khẩu, sai role → 403). Chi tiết: `CLAUDE.md` *Frontend notes*.
- **Nhiều route thuộc phạm vi đã bỏ / tầng 3** (shop, membership, vouchers…) — đối chiếu `docs/INDEX.md` trước khi nối API.
<<<<<<< HEAD
- **API:** một client `shared/api/axios.ts` (`VITE_API_URL`, mặc định 8081), lỗi `ApiError` + `ruleId`. Mock theo hợp đồng `shared/api/mock/` bật ở `npm run dev` (`VITE_API_MOCK=false` để gọi BE thật). `clinic.api.ts` / `clinic-db.ts` (localStorage, rule v13) nay chỉ còn nuôi KPI tổng quan và mã chết (T37-fe.md B.6). Bàn Phòng khám (kê đơn #16–17, T46), hàng đợi, grooming chạy trên mock theo hợp đồng. Trang công khai (`/services`, `/branches`, `/doctors`, `/shop`, `/news`, `/news/:slug`, `/`, `/about`) đọc `/api/public/*` qua `usePublicSite` (T46). Trang quản trị nội dung (`/admin/page-contents`, `/admin/articles`), góp ý (`/admin/feedbacks`, khách `/review`) và Care Task (`/admin/care-tasks`) chạy mock (T47): content-v1 BE đã có nhưng chưa chạy thử với BE thật, Care Task BE chưa có (T39); trang công khai `/`, `/about`, `/terms`, `/privacy` đọc nội dung từ `/api/public/pages/{key}` (mặc định trong mã khi `{}`). `mock-api.test.ts` cố ý assert mock trả 404 cho một path chưa có mock (`/api/visits/1/not-yet-built`).
=======
- **API:** một client `shared/api/axios.ts` (`VITE_API_URL`, mặc định 8081), lỗi `ApiError` + `ruleId`. Mock theo hợp đồng `shared/api/mock/` bật ở `npm run dev` (`VITE_API_MOCK=false` để gọi BE thật). `clinic.api.ts` / `clinic-db.ts` (localStorage, rule v13) nay chỉ còn nuôi KPI tổng quan và mã chết (T37-fe.md B.6). Bàn Phòng khám (kê đơn #16–17, T46), hàng đợi, grooming chạy trên mock theo hợp đồng. Trang công khai (`/services`, `/branches`, `/doctors`, `/shop`, `/news`, `/news/:slug`, `/`, `/about`) đọc `/api/public/*` qua `usePublicSite` (T46). `mock-api.test.ts` cố ý assert mock trả 404 cho một path chưa có mock (`/api/visits/1/not-yet-built`).
>>>>>>> 17dfa50 (feat: triển khai Care Task và nhắc tái chủng, tái khám)
- **Khuyết tật:** 2 `QueryClient` (`main.tsx`, `App.tsx` thắng); Vite proxy 8080 ≠ axios 8081.

## 7. Nợ cấu hình & tài liệu (đừng tin, hãy kiểm tra)

| Chỗ | Vấn đề |
|---|---|
| `application*.yml` | `open-in-view: false` (ADR-0020); `accept-float-as-int: false` (ADR-0032); không có refresh token (ADR-0003); mail health bật → chạy BE ngoài Docker cần `mailpit`. Tham số job `app.jobs.*` là vận hành, không phải [CFG] |
| `.github/workflows/ci.yml` | Gọi plugin Flyway không có trong `pom.xml`; trigger `main, develop` (nhánh làm việc là `dev`); lint `\|\| true` |
| `.gitignore` `docs/dept` | Sổ nợ chỉ có trên máy BE-1; điều BE-2 cần ghi ở `06-module-contracts.md` §6 |
| `docs/convention/backend/` | TBD còn mở: tên command, cách trả cảnh báo không chặn, khóa sức chứa chuồng |
| `server.forward-headers-strategy: native` | Dải proxy tin cậy gồm gateway Docker `172.x`: ở dev client giả được `X-Forwarded-For`. Môi trường thật: không publish 8080 hoặc thu hẹp `internal-proxies` (ADR-0002) |
