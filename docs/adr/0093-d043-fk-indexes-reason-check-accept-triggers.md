# ADR-0093: Trả D043 một phần (4 index FK + CHECK lý do mua ngoài), chấp nhận không trigger chặn sửa bệnh án

- **Trạng thái:** Accepted
- **Ngày:** 2026-11-02
- **Liên quan:** BR-KB-01, BR-KB-03, BR-KB-04, BR-BH-04, BR-SP-07; ADR-0066 §6 (bảng đảm bảo G6, G8, G9; mở D043), 0070, 0072, 0076 (C3, §5), 0077, 0078, 0018 (index FK, không `CONCURRENTLY`), 0081 (khuôn V22). Nợ: D043 (nhóm 1 và 3 trả, nhóm 2 chấp nhận).
- **Triển khai:** `V28__medical_record_fk_indexes_external_reason_check.sql`; `SchemaMigrationIT` (danh sách phiên bản có `28`, `medicalRecordAndVaccinationForeignKeysHaveSingleColumnIndexes`, `prescriptionItemsTieTheExternalReasonToTheExternalFlag`); `PrescriptionItemConstraintIT.theReasonIsTiedToTheExternalFlag` (thay `knownGapD043…`); `FkIndexScanIT` (EXPLAIN trên 20 000 dòng). Không đổi contract, không [CFG], không đổi lock order.

Người dùng chốt ngày 2026-11-02: đồ án, không có người dùng thật; trả phần rẻ và có giá trị, không làm phần tốn công.

## Quyết định

1. **Bốn index một cột** (đều cột `NOT NULL`, nên không partial): `ix_medical_record_addenda_medical_record_id`, `ix_prescription_items_medical_record_id`, `ix_vaccinations_visit_id`, `ix_vaccinations_vaccine_type_id`. Phục vụ xem bệnh án (#12), hồ sơ sức khỏe (#21), `existsByVaccineTypeId` và kiểm FK khi xóa dòng cha. `hasVaccinationInVisit` vẫn lọc `pet_id` (đã đúng), không đổi code truy vấn. Không `CONCURRENTLY` (bảng nhỏ, như ADR-0018 mục 4).
2. **CHECK `ck_prescription_items_external_reason_tied`:** `is_external_purchase = (external_reason IS NOT NULL)`. V1 chỉ có `external_reason IN (...)`, NULL cho UNKNOWN nên lọt. Hợp lệ: `(true, NULL, 'OUT_OF_STOCK_AT_PRESCRIBE' | 'OUT_OF_STOCK_AT_PAYMENT')` và `(false, <dòng Order>, NULL)`; cùng `ck_prescription_items_external_xor_line` (V1) giữ "không tách dòng". Mọi đường ghi hiện có đã đặt cả ba cột cùng lúc (`PrescriptionItem.prescribe` / `externalAtPrescribe`, `PrescriptionItemRepository.markExternalAtPayment`) nên không dòng hợp lệ nào bị chặn. Migration chạy `ADD CONSTRAINT` kiểm cả dòng sẵn có: DB dev có dòng ghi tay sai thì V28 lỗi lúc khởi động, sửa dòng theo câu truy vấn ghi ở đầu file migration rồi chạy lại.
3. **Chấp nhận không làm hai trigger của D043 nhóm 2:** (a) chặn UPDATE năm cột nội dung của `medical_records` khi `locked_at IS NOT NULL`, (b) chặn UPDATE / DELETE / TRUNCATE `medical_record_addenda`. `locked_at` cũng không được bảo vệ ở DB.

## Vì sao chấp nhận nhóm 2

- BR-KB-01 vẫn được ứng dụng giữ và có test (ADR-0066 G6, G8, G9; `MedicalRecordIT`; `MedicalRecordAddendum` mọi cột `updatable = false`; `addAddendum` chỉ thêm). Mất lớp phòng thủ thứ hai ở DB, không mất tính đúng của hệ thống.
- Rủi ro còn lại: một bug trong service visit hoặc SQL tay sửa được bệnh án đã khóa / xóa bản bổ sung mà DB không chặn. Đồ án không có người dùng thật nên chấp nhận (cùng cách với D020, D027).
- Chi phí khi làm: ba file test đang `DELETE FROM medical_record_addenda` để dọn dữ liệu (`AppointmentItSupport.java`, `MedicalRecordIT.java`, `VisitCompleteIT.java`) sẽ vỡ và phải bọc `SET LOCAL session_replication_role = replica`; thêm IT cho từng trigger; nguy cơ chặn nhầm ST04 hoặc đường mua ngoài nếu viết sai.

## Cách trả sau này, nếu cần

Một migration mới (hỏi số V với BE-2 trước):
- Trigger `BEFORE UPDATE` trên `medical_records`: từ chối khi `OLD.locked_at IS NOT NULL` và một trong `examination`, `diagnosis`, `treatment_plan`, `internal_note`, `follow_up_date` đổi (`IS DISTINCT FROM`); **phải cho phép** `follow_up_reminded_at`, `follow_up_cancelled_at` (ST04, BR-TB-06), `last_edited_by`, `updated_at` và chính lần khóa (`OLD.locked_at IS NULL`). Dùng `ERRCODE = 'check_violation'` để `GlobalExceptionHandler` trả 409 (cần xác nhận bằng IT). Muốn chặn cả mở khóa thì thêm `locked_at` vào danh sách so sánh.
- Trigger `BEFORE UPDATE OR DELETE` và `BEFORE TRUNCATE` trên `medical_record_addenda` theo khuôn `forbid_audit_log_change` (V1).
- Không được chặn đơn thuốc: `PrescriptionApi.markExternalPurchaseAtPayment` (UPDATE `prescription_items`, ADR-0076 §5) và `DELETE prescription_items` khi lượt `IN_PROGRESS` (visit-v1 #17, ADR-0078) là hợp lệ; trigger chỉ gắn vào `medical_records` nên không chạm hai đường này, nhưng cần IT xác nhận.
- Ba file test dọn bản bổ sung bọc `SET LOCAL session_replication_role = replica`.

## Ghi chú

Dòng 3 của `V22__care_reminder_templates_care_task_guards.sql` còn ghi "V23 giữ cho CHECK `prescription_items` (D043)": đã lỗi thời (V23 dùng cho ST18 — ADR-0083). Không sửa được vì migration đã áp dụng (checksum); số của D043 là V28.
