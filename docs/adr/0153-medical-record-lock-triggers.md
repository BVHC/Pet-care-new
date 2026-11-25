# ADR-0153: Hai trigger khóa bệnh án (V65): chặn sửa bệnh án đã khóa, chặn sửa / xóa / TRUNCATE bản bổ sung (trả nốt D043)

- **Trạng thái:** Proposed (Hiến (FE-1) chốt làm ngày 2026-11-25 và làm thay BE-1; chủ module visit là Mạnh, cần Mạnh rà trước khi gộp). Chuyển Accepted khi V65 đã commit, IT các nhóm bên dưới xanh và Hiến cho áp lên DB demo.
- **Ngày:** 2026-11-25
- **Liên quan:** BR-KB-01, BR-QT-15, BR-TB-06; ADR-0066 §6 (bảng đảm bảo G6, G8, G9; mở D043), 0070, 0076 §5 (mua ngoài lúc thu), 0078 (xóa dòng đơn thuốc), 0088 (ST04 `SKIP LOCKED`), 0093 (**mục 3 bị ADR này thay**), 0098 (gỡ trigger `audit_logs`, đã làm ngược lại; khuôn hàm và trigger là V1). Nợ: D043 đóng nốt.
- **Triển khai:** `V65__medical_record_lock_triggers.sql`; `SchemaMigrationIT` (danh sách phiên bản có `65`); `MedicalRecordLockTriggerIT` (mới); `AppointmentItSupport.deleteAddenda` và bốn nơi dọn bản bổ sung trong test (`AppointmentItSupport.cleanUp`, `MedicalRecordIT`, `VisitCompleteIT`, `HealthRecordIT`). Không đổi Java ở `src/main`, không đổi contract, không [CFG], không đổi thứ tự khóa.

## Bối cảnh

ADR-0093 (2026-11-02) chấp nhận không làm hai trigger của D043 nhóm 2 vì đồ án không có người dùng thật và trigger đòi chi phí sửa test. Rủi ro còn lại nêu ở đó: một bug trong service visit hoặc SQL tay sửa được bệnh án đã khóa, hoặc xóa bản bổ sung, mà DB không chặn. Đề cương và báo cáo mô tả bệnh án là hồ sơ pháp lý ("khóa khi hoàn tất, chỉ được bổ sung có ghi vết"), nên Hiến chốt ngày 2026-11-25 làm cả hai trigger, giữ ứng dụng là lớp chính.

## Quyết định

1. **Trigger `trg_medical_records_locked_guard`:** `BEFORE UPDATE` từng dòng trên `medical_records`. Nếu `OLD.locked_at IS NOT NULL` và một trong `visit_id`, `examination`, `diagnosis`, `treatment_plan`, `internal_note`, `follow_up_date`, `locked_at` đổi (`IS DISTINCT FROM`) thì `RAISE EXCEPTION ... USING ERRCODE = 'check_violation'`. **Cho đi qua:** `follow_up_reminded_at`, `follow_up_cancelled_at` (ST04, hủy nhắc, BR-TB-06), `last_edited_by`, `updated_at`; lần khóa đầu (`OLD.locked_at IS NULL`); UPDATE ghi lại đúng giá trị cũ (Hibernate có thể UPDATE cả dòng). So với gợi ý của ADR-0093, thêm `visit_id` và `locked_at` vào danh sách chặn để không ai mở khóa, đổi mốc khóa hay chuyển bệnh án sang lượt khác.
2. **Trigger `trg_medical_record_addenda_no_update_delete`** (`BEFORE UPDATE OR DELETE`, từng dòng) và **`trg_medical_record_addenda_no_truncate`** (`BEFORE TRUNCATE`, theo câu lệnh) trên `medical_record_addenda`, cùng hàm mới `forbid_medical_record_addendum_change()` (hàm `forbid_audit_log_change()` của V1 đã gỡ ở V31). Bản bổ sung chỉ còn INSERT.
3. **Lỗi:** SQLSTATE 23514, Spring dịch thành `DataIntegrityViolationException`, `GlobalExceptionHandler.handleDataConflict` trả 409 `CONCURRENCY_CONFLICT`. Đường API bình thường không bao giờ chạm trigger vì service đã trả 409 `INVALID_STATE_TRANSITION` trước; trigger chỉ chạm khi có bug hoặc SQL ghi tay.
4. **Không chặn `DELETE medical_records`:** nhiều IT dọn dữ liệu bằng câu này, và FK `RESTRICT` từ `medical_record_addenda` và `prescription_items` đã chặn khi bệnh án còn con. Giới hạn: bệnh án đã khóa mà không có bản bổ sung hay đơn thuốc vẫn xóa được ở DB. Không chặn `TRUNCATE medical_records`.
5. **Dọn dữ liệu IT:** `AppointmentItSupport.deleteAddenda(jdbc, where, args…)` chạy câu `DELETE` trong một transaction riêng với `SET LOCAL session_replication_role = replica` (trigger thường không chạy ở chế độ này; ngoài transaction `SET LOCAL` không có tác dụng). Bốn nơi dọn bản bổ sung dùng hàm này (ADR-0093 liệt kê ba, thiếu `HealthRecordIT`).

## Vì sao

- **Service vẫn là lớp chính, trigger đứng sau.** Luật thật không phải "`locked_at` khác `NULL` thì cấm sửa": còn gồm vai trò, chi nhánh, người phụ trách và thứ tự lỗi theo hợp đồng (400 → 403 vai trò → 404 → 403 chi nhánh → 403 người phụ trách → 409). Trigger chỉ thấy dòng dữ liệu, không thấy người gọi, nên không thay được service; nó chặn đường mà service không đi qua.
- **Chi phí đã nêu ở ADR-0093 nay được chấp nhận:** sửa bốn nơi dọn dữ liệu test, thêm một IT; rủi ro chặn nhầm ST04 và đường mua ngoài được IT phủ (xem *Kiểm*).
- **Không chặn nhầm đường hợp lệ:** `PrescriptionApi.markExternalPurchaseAtPayment` (UPDATE `prescription_items`, ADR-0076 §5) và `DELETE prescription_items` khi lượt `IN_PROGRESS` (ADR-0078) không chạm hai bảng có trigger. ST04 chỉ ghi `follow_up_reminded_at` và `follow_up_cancelled_at`.

## Hệ quả

- **Migration một chiều:** BE khởi động trên DB nào thì áp V65 lên DB đó. DB demo 5433 đang ở Flyway 64 và 4 bệnh án đều đã khóa, chưa có bản bổ sung nào; trigger không kiểm dòng sẵn có. Không áp V65 lên 5433 khi Hiến chưa nói. Dump chung `BE/scripts/demo-db/petcare_demo.dump` (Flyway 62) không đổi.
- **Rủi ro còn lại:** ứng dụng kết nối bằng owner của DB (ADR-0098), nên owner vẫn gỡ được trigger; `DELETE medical_records` như mục 4. Trigger chặn bug và SQL nhầm, không chặn người cố tình. Không nên mô tả đây là bảo đảm tuyệt đối.
- **Thao tác tay trên DB:** muốn xóa bản bổ sung (dọn dữ liệu thử) phải bọc `SET LOCAL session_replication_role = replica` trong transaction, hoặc gỡ trigger tạm.
- ADR-0066 §6, 0070 và 0093 mục 3 là lịch sử, giữ nguyên chữ; ADR-0093 chỉ thêm ghi chú ở dòng trạng thái.

## Cách gỡ nếu cần

Một migration mới: `DROP TRIGGER trg_medical_records_locked_guard ON medical_records; DROP TRIGGER trg_medical_record_addenda_no_update_delete ON medical_record_addenda; DROP TRIGGER trg_medical_record_addenda_no_truncate ON medical_record_addenda; DROP FUNCTION forbid_locked_medical_record_change(); DROP FUNCTION forbid_medical_record_addendum_change();` (cùng khuôn V31). V65 không sửa sau khi đã áp (checksum).

## Kiểm

Ngày 2026-11-25, trong bản sao riêng của BE (`.c2-tmp/trg-scratch`, Postgres 17 qua Testcontainers, V65 phủ vào bản sao vì chưa nằm trong cây repo), `mvn verify` với `-Dtest=none`:

- **`MedicalRecordLockTriggerIT` (mới) 14/14:** mười ca tham số (mỗi cột bị chặn một ca: `examination`, `diagnosis`, `treatment_plan`, `internal_note`, `follow_up_date` đổi và về `NULL`, `locked_at` về `NULL` và dời mốc, `visit_id`), cột ST04 + `last_edited_by` + `updated_at` và UPDATE ghi lại giá trị cũ vẫn đi qua, bệnh án chưa khóa vẫn sửa được rồi khóa được, bản bổ sung chỉ INSERT (UPDATE, DELETE, TRUNCATE đều bị chặn), dọn dữ liệu ở chế độ replica. **`SchemaMigrationIT` 31/31** (danh sách phiên bản có `65`).
- **Đột biến V65, 7/7 bị bắt:** bỏ `follow_up_date` (2 ca đỏ), bỏ `locked_at` (2), bỏ `visit_id` (1), chặn nhầm `follow_up_reminded_at` của ST04 (1), bỏ điều kiện `OLD.locked_at IS NOT NULL` (14), trigger bản bổ sung chỉ chặn UPDATE không chặn DELETE (1), bỏ trigger TRUNCATE (1).
- **Hồi quy IT, 0 lỗi, 0 bỏ qua:** `visit` 41 lớp / 542 ca; `appointment` + `care` + `customer` 51 lớp / 717 ca; `inpatient` + `ai` + `OrderFsmIT`, `OrderQueryVisitLookupIT`, `CartIT`, `ShippingFeeConfigIT`, `SchemaMigrationIT`, `MedicalRecordLockTriggerIT` 39 lớp / 504 ca; `workforce` 51 lớp / 577 ca (có gọi `AppointmentItSupport` qua `WorkforceItSupport`). Tổng 182 lớp, 2340 ca.
- **Bản sao dữ liệu thật của DB demo** (`petcare_trg` dựng bằng `pg_dump` từ 5433 Flyway 64, 4 bệnh án đều đã khóa, 0 bản bổ sung; đã xóa sau khi thử): áp V65 bằng `psql` rồi chạy 10 câu SQL: sửa chẩn đoán và mở khóa bệnh án đã khóa bị chặn (BR-KB-01), ST04 và ghi lại giá trị cũ đi qua, INSERT bản bổ sung đi qua, UPDATE, DELETE và TRUNCATE bản bổ sung bị chặn, xóa ở chế độ replica đi qua. DB demo `petcare_seed` không bị đụng (Flyway 64, chưa có trigger).
- **Chưa làm:** áp V65 lên DB demo 5433; chạy cả bộ IT (không chạy `identity`, `boarding`, `catalog`, `content`, `inventory`, `payment`, `report`, `returns`, phần lớn `sales` và `shop`); unit test `*Test` (không đổi Java ở `src/main`); IT qua API xác nhận thân 409 (đường API không bao giờ chạm trigger, nên trigger chỉ được kiểm bằng JDBC).
