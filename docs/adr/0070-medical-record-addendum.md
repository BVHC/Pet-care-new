# ADR-0070: Bản bổ sung bệnh án (`POST /api/visits/{visitId}/medical-record/addenda`) — mọi VET của chi nhánh, chỉ sau `COMPLETED`, tạo bệnh án rỗng đã khóa khi thiếu

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Liên quan:**
  - BR-KB-01, BR-KB-04, BR-QT-15, BR-KH-03, BR-KH-05, BR-KH-07; UC53; visit-v1 #15, A7, A17.
  - ADR-0066 (nền bệnh án: §7 khóa, §8 bất biến ở service, §9 DTO), 0067 (khuôn #13), 0069 (A16: lượt chỉ tiêm hoàn tất khi có ≥ 1 mũi).
  - Nợ: D043 (trigger chặn sửa / xóa bản bổ sung vẫn chưa có).
- **Triển khai (T27 phần 4):** `MedicalRecordService.addAddendum`, `MedicalRecordController.addMedicalRecordAddendum`, `MedicalRecordMapper.toAddendum` (cắt `createdAt` về µs). Test: `MedicalRecordServiceTest`, `MedicalRecordMapperTest`, `MedicalRecordControllerSecurityTest`, `MedicalRecordIT` (+12 ca). Không migration, không method mới ở module khác.

## Quyết định

1. **Ai ghi:** mọi VET của chi nhánh nơi khám (A7), không cần là người phụ trách. Sau `COMPLETED` không còn khái niệm "đang phụ trách"; người khám có thể đã bị khóa hay điều chuyển. Quyền = `@PreAuthorize(hasRole('VET'))` + `BranchScope.check`. CARETAKER, lễ tân, quản lý, khách: 403.
2. **Khi nào:** chỉ lượt `MEDICAL` đã `COMPLETED`; `GROOMING` → 404 (không có bệnh án); trạng thái khác → 409 `INVALID_STATE_TRANSITION`. Kiểm sau khi khóa `visits` (§7 của ADR-0066), không khóa thú.
3. **Thiếu dòng bệnh án (A17):** tạo bệnh án rỗng với `locked_at = visits.completed_at`, trong cùng transaction, rồi mới thêm bản bổ sung. Xảy ra với lượt chỉ tiêm (A16: hoàn tất không cần chẩn đoán). Không cần khóa thú vì lượt chưa có dòng chắc chắn đã có mũi tiêm, nên loài đã bị khóa (BR-KH-03). Dòng tạo kèm không audit; bản bổ sung có audit.
4. **Thú đã mất / đã chuyển chủ:** vẫn thêm được. Giả định: "chỉ đọc" của BR-KH-05 nói về hồ sơ thú; bản bổ sung gắn với lượt và có audit.
5. **Audit:** `MEDICAL_RECORD_ADDENDUM_ADDED`, `entity = medical_record_addenda`, id = id bản bổ sung, `after = {content, isInternal}`, không `before`. `audit.record` (MANDATORY): lỗi audit rollback cả bản bổ sung và bệnh án tạo kèm.
6. **Transaction:** một `@Transactional` mặc định (REQUIRED, READ COMMITTED, không `noRollbackFor`). Thứ tự: role → lượt (404) → chi nhánh (403) → GROOMING (404) → khóa `visits` → `COMPLETED` (409) → tra tên người ghi → đọc / tạo bệnh án → bản bổ sung → audit → map. Tra tên đặt trước mọi lệnh ghi.
7. **Thứ tự lỗi:** 400 body → 401 → 400 BR-TK-17 → 403 vai trò → 404 → 403 chi nhánh → 404 GROOMING → 409.
8. **DTO:** `content` strip rồi `@NotBlank`, không giới hạn độ dài (hợp đồng và cột `TEXT`); `isInternal` vắng = `false`; người ghi, `medicalRecordId`, `createdAt` do server đặt, trường lạ trong body bị bỏ qua. `createdAt` trả về cắt µs để bằng giá trị đọc lại ở #12.

## Bảng đảm bảo (tiếp G1–G14 của ADR-0066)

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| G8 | Chỉ có sau `COMPLETED` | service, sau khóa lượt | `MedicalRecordServiceTest` (`InOrder`, không ghi); IT 409 cho WAITING / IN_PROGRESS / CANCELLED |
| G9 | Chỉ thêm mới | entity `updatable = false`, service không sửa / xóa | `MedicalRecordAddendumTest.everyColumnIsInsertOnly` |
| G15 | Lượt `COMPLETED` thiếu bệnh án → đúng 1 bệnh án rỗng, `locked_at = completed_at` | service dưới khóa lượt | IT `aCompletedVisitWithoutARecord…`; IT race `twoConcurrentAddenda…` (cả hai 201, 1 bệnh án, 2 bản bổ sung) |
| G16 | Bản bổ sung và bệnh án tạo kèm cùng rollback | một transaction | IT trigger tạm trên `audit_logs` và trên `medical_record_addenda` |
| G17 | Mọi VET chi nhánh ghi được, vai trò / chi nhánh đọc lại mỗi request | `@PreAuthorize` + `BranchScope` | IT: VET khác 201; 6 vai trò 403; chuyển chi nhánh, đổi chức vụ, hủy phiên |

DB không giữ G8, G9, G15 (chờ trigger ở D043).

## Hệ quả

- Hồ sơ cho khách (#21) phải lọc bản bổ sung `isInternal` (G13); response này chỉ dành cho nhân viên.
- Bản bổ sung đầu tiên của lượt chỉ tiêm làm loài của thú bị khóa vì `hasMedicalHistory` đã đúng từ lúc có mũi tiêm (không đổi hành vi).

## Đã cân nhắc

- **Visit#5 luôn tạo dòng bệnh án** — bất biến gọn hơn nhưng phải sửa `VisitCompletionService` và thay một phần ADR-0069.
- **409 khi thiếu dòng cho tới T28** — ít code, để lại nợ và chặn sửa sai sót mũi tiêm (BR-KB-04).
- **Chặn khi thú đã mất** — làm mất đường ghi sửa sai y tế; BR-KH-05 không nói tới bản bổ sung.
