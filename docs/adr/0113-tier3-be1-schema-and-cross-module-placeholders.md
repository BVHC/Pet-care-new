# ADR-0113: Tầng 3 phần BE-1 (T55b) — migration V36–V41, placeholder `default` trên interface có sẵn, 8 khóa [CFG], 40 mẫu thông báo

- **Trạng thái:** Proposed (làm thay BE-1 theo yêu cầu của Hiến; Mạnh chưa đọc `09` và phần Nhân sự. V41, V42 chạm bảng của BE-2 và **chưa được BE-2 xác nhận**. Tên mã, biến và nội dung 40 mẫu thông báo là **giả định của T55b**)
- **Ngày:** 2026-11-06
- **Liên quan:** T55 (`docs/time.md`); `docs/09` §A–E, §G; `docs/05` §14.1, §13 mục 24, 26; `docs/06` §1, §6b, §8 Q4; `docs/07` §11; `docs/08` §F.1; `docs/10` §1.7; ADR-0018 (index cho FK tới `accounts`), ADR-0037 (cú pháp biến mẫu), ADR-0081 (khuôn CHECK cột ↔ trạng thái của `care_tasks`), ADR-0110 (V34 làm khuôn: VARCHAR(30), tên khóa ba đoạn). **Đánh số:** dải BE-1 ghi 0111–0115 nhưng Mạnh đã lấy 0111, 0112 trên `origin/dev` (8d0036f, chưa gộp vào `dev` này) nên ADR này là 0113; số BE-1 còn lại: 0114, 0115. `adr/INDEX.md` sẽ xung đột với hai dòng của Mạnh lúc gộp (cùng vùng cuối bảng).
- **Triển khai:** `V36__workforce_tables.sql`, `V37__home_visit_surgery_material_usage.sql`, `V38__inpatient_tables.sql`, `V39__complaint_tables.sql`, `V40__seed_tier3_configs_and_templates.sql`, `V41__tier3_be1_catalog_enum_values.sql` (V42 là Order `ONLINE`, xem ADR-0116); `identity/api/{ConfigKey (+8), NotificationTemplateCode (+40), StaffLeftBranchEvent (mới), StaffDirectoryApi}`, `visit/api/{VisitQueryApi, VaccinationQueryApi}`, `appointment/api/AppointmentQueryApi`, `care/api/CareTaskApi`. Chưa có entity, repository, service, controller (T58…T71).
- **Test:** `Tier3Be1SchemaIT` (13), `SchemaMigrationIT` (cập nhật danh sách + test mới `everyNotificationTemplateCodeConstantHasASeededRow`), `ConfigKeyTest`, `SystemConfigIT`, `SystemConfigAdminIT`, bốn lớp `*Tier3PlaceholderTest`.

## 1. Dải V đã dùng

| V | Nội dung | Nguồn |
|---|---|---|
| V36 | `shift_definitions`, `shift_assignments`, `shift_requests` | `05` §14.1 |
| V37 | cột `is_home_visit`, `home_*` của `appointments` + `visits.is_home_visit`; `surgeries`; `visit_material_usages` | `09` §A, §B, §E |
| V38 | `admissions`, `admission_orders`, `admission_observations` | `09` §C |
| V39 | `complaints`, `complaint_messages` | `09` §D |
| V40 | 8 tham số [CFG] + 40 mẫu thông báo | `09` §G, `10` §1.7, `06` §6b hàng 17 |
| V41 | `services.medical_type` thêm `SURGERY`, `INPATIENT`; `order_lines.line_type` thêm `HOME_FEE` | `05` §13 mục 26 |
| V42 | 4 CHECK của `orders` cho nguồn `ONLINE` (làm thay BE-2, **ADR-0116**) | `08` §B #14b, `05` §13 mục 26 |

`origin/dev`, `origin/manh`, `origin/dung`, `origin/Hien` chưa có V36+ khi viết (kiểm 6/11). V42 do T55b làm thay BE-2 sau khi Hiến yêu cầu (Order `ONLINE`, ADR-0116); V43–V49 còn nguyên cho BE-2 (T55c). 10 bảng mới: `ERD_TABLES` 59 → 69, `LOG_TABLES` 9 → 12 (`admission_observations`, `complaint_messages`, `visit_material_usages`), FK → `accounts` 45 → 61.

## 2. V41 và `orders.source = ONLINE`

06 §6b hàng 7 ghi bên cung cấp ba thay đổi là BE-2; prompt T55b cho mặc định "BE-1 viết ở V41". V41 làm **hai trong ba** vì cả hai là nhu cầu của BE-1 và an toàn (cái thứ ba, `orders.source = ONLINE`, làm ở V42 sau khi Hiến yêu cầu):

- `ck_services_medical_type`: `EXAM, VACCINE, SURGERY, INPATIENT` (`ck_services_medical_type_by_group` giữ nguyên).
- `ck_order_lines_line_type` thêm `HOME_FEE`. `ck_order_lines_service_ref` / `_product_ref` **không đổi**: `HOME_FEE` không thuộc hai tập đó nên cả hai vế đều `false`, dòng hợp lệ khi `service_id` và `product_id` cùng NULL, và bị chặn nếu mang một trong hai — chứng minh bằng INSERT ở `Tier3Be1SchemaIT.homeFeeLineHasNeitherServiceNorProduct`.
- `DROP CONSTRAINT IF EXISTS` rồi `ADD`, nên nếu T55c viết cùng thay đổi ở V42+ thì bản sau chỉ khẳng định lại đúng tập này.

**`orders.source = 'ONLINE'` làm ở V42, không ở V41** — nó cần một quyết định thiết kế của BE-2: ngoài nhánh `ONLINE` cho `ck_orders_single_source`, Order `ONLINE` lên `PAID` "qua cổng, không qua ca thu ngân" trong khi `ck_orders_paid_payment` buộc `PAID ⇔ payment_id` và `payments.cashier_shift_id` là `NOT NULL`. Hiến yêu cầu BE-1 làm thay (6/11): tiền online ở `payment_transactions`, không vào `payments`; chi tiết, phương án đã bỏ và việc bắt buộc khi T63 ghi Order `ONLINE` đầu tiên ở **ADR-0116**.

## 3. Chỗ khác `09` / `05` khi viết migration

| Chỗ | Đặc tả | Migration | Lý do |
|---|---|---|---|
| Cột enum ngắn: `discharge_outcome`, `order_type`, `topic`, `related_type`, `closed_reason` (20); `appetite`, `author_type`, `kind` (10) | `VARCHAR(10/20)` | `VARCHAR(30)` | `05` §13 mục 7 và `SchemaMigrationIT.enumCheckColumnsAreVarchar30` (cùng cách V34 làm với `species`) |
| `complaint_messages.author_type`, `kind` | không ghi `NN` | `NOT NULL` | tin nhắn nào cũng có bên viết và loại |
| `admissions` CHECK xuất viện | "CHECK xuất viện ↔ cột kết cục" | `ck_admissions_discharge_outcome_tied`: `DISCHARGED ⇔ discharge_outcome`; `ck_admissions_discharge_fields`: `DISCHARGED` buộc tóm tắt, người xuất, giờ xuất, dòng Order | FSM #24 dòng 2 |
| `admission_orders` CHECK | không nêu | mỗi cột `done_by`, `done_at`, `done_note` có giá trị ⇔ `DONE`; `cancel_reason ⇔ CANCELLED`; `MEDICATION` buộc `prescription_item_id` | FSM #25, BR-LT-15 |
| `surgeries` CHECK | "cột ↔ trạng thái (khuôn `care_tasks`)" | `CONSENTED`/`IN_PROGRESS`/`COMPLETED` có đủ 5 cột cam kết; `PLANNED` chưa có người/giờ cam kết; `IN_PROGRESS`/`COMPLETED` có `started_at`; `COMPLETED` có `completed_at`, `result_note`, `order_line_id`; `CANCELLED ⇔ cancel_reason` | FSM #23 |
| `complaints` CHECK | "trạng thái ↔ cột (khuôn `care_tasks`)" | `OPEN` chưa có người xử lý; `IN_PROGRESS`/`RESOLVED` có; `RESOLVED` có `resolution` + `resolved_at`; `CLOSED` có `closed_at` + `closed_reason`; `branch_id` bắt buộc trừ chủ đề `OTHER`; `related_type`/`related_id` cùng NULL hoặc cùng có | FSM #26, BR-DG-05 |
| Index thêm ngoài `09` | — | `admissions (visit_id)` (guard "không hoàn tất Visit khi còn `ADMITTED`", BR-LT-14); `admission_orders (prescription_item_id)` một phần (visit-v1 #17 xóa cứng dòng đơn thuốc, kiểm FK); `surgeries (planned_by/consented_by/cancelled_by)`, `visit_material_usages (resolved_by)`, `complaints (created_by_staff_id)`, `admission_* (…_by)`, `complaint_messages (author_id)` | kiểm FK khi xóa `accounts` (ADR-0018, `everyForeignKeyToAccountsHasUsableIndex`) |
| `EXCLUDE USING gist` chống chồng giờ ca (`05` §13 mục 24) | "chờ BE-1 quyết" | **chưa làm**: service kiểm dưới khóa dòng `accounts` | cần sao chép giờ vào `shift_assignments`; quyết khi làm T58, bằng migration mới nếu muốn |

**Câu hỏi mở (đặc tả không nói, V37/V39 để trống ràng buộc):** (a) BR-KB-11 "dừng phẫu thuật kèm ghi chú" — ghi chú nằm ở cột nào (`result_note` hay chỉ `cancel_reason`); V37 không buộc `result_note ⇔ COMPLETED`. (b) Khiếu nại mở lại (#6, #7) có xóa `resolved_at`, `closed_at`, `closed_reason` không; V39 chỉ buộc chiều `CLOSED ⇒ có closed_*`, `RESOLVED ⇒ có resolution`.

## 4. Placeholder trên interface đã có bản thật: method `default` ném lỗi

Cả năm interface (`VisitQueryApi`, `VaccinationQueryApi`, `AppointmentQueryApi`, `StaffDirectoryApi`, `CareTaskApi`) đã có **một bean thật** mỗi interface, và `BoardingBookingIT`, `BoardingCheckInIT` của BE-2 giữ lớp ẩn danh `new VaccinationQueryApi() {…}` (CLAUDE.md Gotchas). Method trừu tượng mới sẽ làm gãy các lớp đó. Nên placeholder của T55b có dạng **`default` method ném `UnsupportedOperationException`** (thông báo nêu `Interface.method` và task cài thật), **không** override trong service. Khi làm bản thật: ghi đè trong service (`@Transactional(MANDATORY)` nếu ghi) **và xóa `default`** — method thành trừu tượng, bean ẩn danh nào còn sót sẽ báo lỗi biên dịch ngay. Mỗi method có một test `CALLS_REAL_METHODS` khẳng định ném lỗi. Không dùng lớp `*Placeholder` riêng vì phải có đúng một bean mỗi interface (06 §1). `grep` `new <Api>() {` / `implements <Api>` trước khi thêm: chỉ hai chỗ trên ở test của BE-2, không gãy.

| Method | Trả về | Kiểu / quy ước do T55b chọn (đặc tả không nêu) | Task cài thật |
|---|---|---|---|
| `VaccinationQueryApi.findDoseHistory(petId)` | `List<DoseRecord(vaccinationId, vaccineTypeId, doseNumber, administeredOn, nextDueDate, superseded)>` | mới nhất trước, gồm cả mũi đã bị thay thế | T77 |
| `VaccinationQueryApi.findByStockLot(lotId)` | `List<LotVaccination(vaccinationId, petId, visitId, administeredOn)>` | theo ngày tiêm | T74 |
| `VisitQueryApi.findLastServiceDates(petId)` | `Map<serviceId, LocalDate>` | chỉ Visit `COMPLETED` | T78 |
| `VisitQueryApi.countCompletedByService(species, ageGroup, since)` | `Map<serviceId, Long>` | `Species` của `customer.api`; **`VisitQueryApi.AgeGroup {JUNIOR, ADULT, SENIOR, UNKNOWN}`** theo `07` L315; `since` tính cả ngày đó, theo ngày hoàn tất | T78 |
| `VisitQueryApi.countOpenByBranch(branchId)` | `long` | Visit `WAITING` + `IN_PROGRESS` | T75 |
| `AppointmentQueryApi.findUpcomingBookedOfCustomer(customerId, now, limit)` | `List<UpcomingBooked(appointmentId, code, petId, serviceId, branchId, slotDate, slotStart)>` | **`now` là `LocalDateTime` giờ Việt Nam** (`LocalDateTime.now(clock)`), cùng múi với `slot_date`/`slot_start`; sắp tăng dần; **không đụng `BookedSlot`** (07 L537) | T77 |
| `AppointmentQueryApi.countBookedByBranch(branchId)` | `long` | lịch `BOOKED` mọi ngày | T75 |
| `StaffDirectoryApi.countActiveByBranch(branchId)` | `int` | `ACTIVE` mọi chức vụ, tính cả người bị khóa (cùng `countBranchManagers`) | T75 |
| `CareTaskApi.reassignBranch(from, to)` | `int` (số task đã chuyển) | một UPDATE, chỉ task `OPEN` | T75 |
| `StaffLeftBranchEvent(staffId, branchId)` | record sự kiện, `branchId` = chi nhánh **cũ** | **chưa phát** | T58 |

**Báo BE-2 (Dũng) — `AgeGroup`:** I-7 (`BoardingQueryApi.countStaysByKennelType(species, ageGroup, since)`) cần cùng một kiểu. `VisitQueryApi.AgeGroup` do visit giữ để visit không phải import module `ai`; boarding nên import nó từ `visit.api` hoặc khai báo enum tương đương, thống nhất trước T78. **Thiếu trong hợp đồng `06` §6b hàng 13 / `08` §F.1:** hủy Care Task `OPEN` kèm lý do khi SUPER_MANAGER không chọn chi nhánh đích (BR-CN-09), và đếm Care Task `OPEN` cho danh sách tồn đọng (BR-CN-07) — T75 phải thêm hai method vào `CareTaskApi` (BE-1) hoặc đổi hợp đồng.

I-9 (bộ khớp giới hạn tần suất chatbot) T55a đã làm thay BE-1 (ADR-0110), không thuộc T55b. Ngoài phạm vi theo `time.md` T55: phí khám tại nhà, vật tư tiêu hao, nhập viện từ lưu trú (`AdmissionQueryApi`, `VisitApi.startTreatmentVisit`, `VisitOrderApi.addHomeFeeLine`…): task tiêu thụ tự thêm.

## 5. Tham số [CFG] (V40): 8 khóa, `ConfigKey` 70 → 78

Mặc định theo đặc tả; **khoảng min–max là giả định của T55b**:

| Khóa | Mặc định | min – max | Rule |
|---|---|---|---|
| `complaint.max_open` | 10 | 1 – 50 | BR-DG-05 |
| `complaint.reopen_days` | 7 | 1 – 30 | BR-DG-07 |
| `complaint.max_reopen` | 2 | 0 – 5 | BR-DG-07 |
| `complaint.sla_open_hours` | 24 | 1 – 168 | BR-DG-08 |
| `complaint.sla_resolve_hours` | 72 | 1 – 720 | BR-DG-08 |
| `complaint.auto_close_days` | 7 | 1 – 30 | BR-DG-09 |
| `workforce.request_lead_hours` | 24 | 0 – 168 | BR-NS-04 |
| `workforce.absence_report_max_days` | 7 | 1 – 30 | BR-NS-07 |

Không thêm ràng buộc chéo (`ConfigConstraints` không đổi). Các khóa `shop.*`, `payment.*`, `returns.*`, `content.comment_per_hour` của `10` §1.7 thuộc BE-2 (T55c).

## 6. Mẫu thông báo (V40): 40 mẫu — quyết định của Hiến 6/11/2026

`06` §8 Q4 nói seed dần, cùng PR với lần `enqueue` đầu tiên, biến do module gửi đề xuất; `06` §6b hàng 17 và prompt T55b nói seed ở T55; **không tài liệu nào định nghĩa tên mã, kênh, biến** của `SHIFT_*`, `COMPLAINT_*`, `ONLINE_ORDER_*`, `PAYMENT_*`, `RETURN_*`, `REFUND_*`, `ARTICLE_REVIEW_*`. Hiến chọn "seed đủ cả BE-1 và BE-2" (6/11). Toàn bộ tên mã, kênh, người nhận, biến và nội dung dưới đây là **giả định của T55b** suy từ rule và FSM; module gửi chỉnh biến bằng migration `UPDATE` mới (không sửa V40), ADMIN sửa chữ ở UC10.

| Họ | Mã (mỗi mã email có thêm `_APP`) | Người nhận | Suy từ |
|---|---|---|---|
| NS (9) | `SHIFT_REQUEST_SUBMITTED_APP` (BRANCH_MANAGER); `SHIFT_REQUEST_APPROVED`, `_REJECTED`, `SHIFT_ASSIGNMENT_CANCELLED` (cả hủy tự động BR-NS-09), `SHIFT_ABSENCE_RECORDED` (nhân viên) | BM / nhân viên | `02` BR-NS-08 |
| Khiếu nại (7) | `COMPLAINT_CREATED_APP`, `COMPLAINT_REOPENED_APP` (nhân viên xử lý), `COMPLAINT_ESCALATED_APP` (SUPER_MANAGER), `COMPLAINT_TAKEN`, `COMPLAINT_RESOLVED` (khách) | theo FSM #26 | `09` §D #1, 2, 3, 6, 7, 9. Tin nhắn trao đổi và tự đóng ST17 không có thông báo trong đặc tả nên không có mẫu |
| Vật tư (1) | `MATERIAL_SHORTAGE_APP` | BRANCH_MANAGER | `09` §E BR-KB-13. 09 viết `MATERIAL_SHORTAGE`; kênh IN_APP buộc đuôi `_APP` (test hiện có) |
| Đơn online (10) | `ONLINE_ORDER_PLACED`, `_PAID`, `_SHIPPED`, `_DELIVERY_FAILED`, `_CANCELLED` | khách | `08` L119 (năm sự kiện), FSM #14 |
| Thanh toán (4) | `PAYMENT_FAILED`, `PAYMENT_EXPIRED` | khách | `08` L178; FSM #15 (thành công đã có `ONLINE_ORDER_PAID`) |
| Trả hàng + hoàn tiền (6) | `RETURN_REQUESTED_APP` (BM), `RETURN_APPROVED_APP`, `RETURN_REJECTED_APP` (**lễ tân tạo yêu cầu** — 08 chỉ ghi "thông báo"), `REFUND_REQUESTED` (khách), `REFUND_FAILED_APP` (BM) | như ghi | `08` FSM #16, #17. Không có mẫu "hoàn tiền thành công" vì FSM #17 dòng 2 không ghi thông báo |
| Duyệt bài (3) | `ARTICLE_REVIEW_SUBMITTED_APP` (SUPER_MANAGER), `_APPROVED_APP`, `_REJECTED_APP` (bác sĩ tác giả) | như ghi | `08` §F.3 |

Quy ước: `body = default_body`, `subject = default_subject`, `required_vars ⊆ allowed_vars`, mọi biến bắt buộc có trong `body`, ngoặc nhọn chỉ cho biến (ADR-0037); `{ten_khach}` / `{ten_nhan_vien}` chỉ là lời chào của email, không bắt buộc; số tiền, ngày, giờ do bên gửi định dạng sẵn thành chữ. File sinh bằng script có kiểm các bất biến này trước khi ghi; `SchemaMigrationIT` kiểm lại và có thêm test mọi hằng số `NotificationTemplateCode` đều có dòng seed.

## 7. Kiểm

- RED trước: `Tier3Be1SchemaIT` chạy khi chưa có bảng → 12 lỗi (bảng hoặc cột chưa có) + 1 lỗi giá trị enum; sau V36–V41 xanh 13/13 (14/14 sau khi V42 thêm ca Order `ONLINE`, ADR-0116). Mỗi ràng buộc được kiểm bằng INSERT bị Postgres từ chối **đúng tên ràng buộc** (trong SAVEPOINT, hoàn tác cuối test, không để dữ liệu).
- Chạy trên **bản sao cô lập** của `BE/` (6/11/2026; BE host cổng 8082 đang chạy từ `BE/target/classes` nên không `mvn` ở cây thật). `diff -rq` xác nhận `BE/src` khớp bản sao sau lần chạy. Unit (Surefire) **5601** ca; IT theo nhóm: identity **856**, care **136**, appointment **389**, visit **516**, `OpenApiContractParityIT` + `SchemaMigrationIT` + `Tier3Be1SchemaIT` + `AiSchemaIT` **78** — **0 đỏ**. Năm ca đỏ cũ trong CLAUDE.md (`VisitCheckInServiceTest`, `OpenApiContractParityIT` ×2, `AuditLogViewIT.queriesUseExpectedIndex`, `ReminderScanIndexIT`) đều xanh trong lần chạy này; chỉ ghi nhận như đo được, không kết luận là đã được sửa. Lưu ý: `exit=1` của nhóm unit trong log tóm tắt là do Failsafe ("No tests matching pattern none"), không phải test đỏ. Chưa chạy: nhóm IT sales, inventory, boarding, branch, customer, content, report (không đụng).
- **Đột biến** (ba ràng buộc bị bỏ trong bản sao, mỗi cái làm đúng một test đỏ; bản sao khôi phục bằng đồng bộ lại):

| Đột biến | Test đỏ |
|---|---|
| bỏ `ck_surgeries_started_at` (V37) | `surgeryStatusColumnsFollowTheStateMachine` |
| `ux_shift_assignments_scheduled` bỏ `WHERE status = 'SCHEDULED'` (V36) | `shiftAssignmentStatusColumnsAndScheduledUniquenessFollowTheSpec` |
| `ux_admissions_pet_admitted` thành index thường, không `UNIQUE` (V38) | `admissionAllowsOneAdmittedPerPetAndDischargeColumnsFollowTheStateMachine` |

- **Sau V42 (ADR-0116):** chạy lại `Tier3Be1SchemaIT` 14/14, `SchemaMigrationIT` 28/28, `SystemConfigIT` 3/3, `OpenApiContractParityIT`, `AiSchemaIT`, nhóm IT sales, report, boarding, visit (516 ca) — 0 đỏ. Lần chạy đầu có 3 ca đỏ trong `Tier3Be1SchemaIT`: đó là ba đột biến cũ còn sót trong `target/classes` của bản sao (Maven không chép đè `.sql` gốc vì mtime cũ hơn bản đột biến), không phải lỗi mã; xóa `target/classes/db` rồi chạy lại thì xanh. Nhóm visit (516 ca) chạy lại sau V42 vì luồng thu tiền của Visit đi qua `ck_orders_paid_payment`: 0 đỏ. Nhóm unit, identity, care, appointment không chạy lại (không đụng `orders`).
- Không có khóa, transaction hay `noRollbackFor` mới nên không có đột biến lock.

## 8. Việc cho người khác

- **Dũng (BE-2):** xác nhận V41, V42 và ADR-0116 (Order `ONLINE`); `AgeGroup` cho I-7 (mục 4); mẫu thông báo của BE-2 (mục 6) có đúng người nhận / biến không; V42–V49 vẫn của T55c.
- **Mạnh (BE-1):** đọc `09` và phần Nhân sự; hai câu hỏi mở ở mục 3; `StaffLeftBranchEvent` chưa phát (T58); hai method thiếu của `CareTaskApi` (mục 4).
- **Hiến:** `CLAUDE.md` mục Persistence "Next free" → V42 (V36–V41 đã dùng), "ADR map" → BE-1 còn 0114, 0115; số `ConfigKey` 70 → 78; `ERD_TABLES` 69.
