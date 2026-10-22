# ADR-0066: Nền bệnh án — tạo khi ghi lần đầu, PUT thay toàn bộ, audit mỗi lần ghi, mốc ngày khám, khóa theo lượt

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Đánh số lại:** ban đầu là ADR-0064; đổi +2 ngày 2026-10-22 vì trùng ADR-0064, 0065 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0064…0067 trong code / tài liệu visit đã đổi theo (+2).
- **Liên quan:**
  - BR-KB-01, 02, 06 (02 L157–162), BR-SP-06 (L109), BR-TN-08 (L148), BR-KH-03, 07 (L89, L93), BR-QT-13, 15 (L56, L58).
  - 03 §4 Visit#5 và Phụ lục; erd §6 `medical_records`, `medical_record_addenda`, `vaccinations`; V1 L515–541, L923–947.
  - visit-v1 #10, #12–15, #21, schema `MedicalRecord`, `UpdateMedicalRecordRequest`, `Addendum`, `AddendumRequest`, `PrescriptionItem`, `VaccinationRecord`; A7.
  - Convention 02 §DTO / §Entity, 08 §8.3; 06 §2 Visit#5.
  - ADR-0057 (cửa sổ [CFG] kiểm lúc thao tác), 0058 (nền visit, thứ tự khóa), 0062, 0063 §8 (nghĩa vụ cho T27).
  - Nợ: mở D043, D044.
- **Triển khai (T27 phần 0, người dùng chốt các quyết định ngày 22/10):**
  - `visit/entity/{MedicalRecord, MedicalRecordAddendum, ExternalPurchaseReason}`, `visit/repository/{MedicalRecordRepository, MedicalRecordAddendumRepository}`, `VisitRepository.hasVaccinationInVisit`.
  - `visit/dto/{UpdateMedicalRecordRequest, AddendumRequest, MedicalRecordResponse, AddendumResponse, PrescriptionItemResponse, VaccinationRecordResponse}`, `visit/mapper/MedicalRecordMapper`.
  - `VisitAuditActions.MEDICAL_RECORD_UPDATED`, `MEDICAL_RECORD_ADDENDUM_ADDED`.
  - Test:
    - Unit: `MedicalRecordTest`, `MedicalRecordAddendumTest`, `MedicalRecordMapperTest`, `MedicalRecordRequestValidationTest`.
    - IT: `MedicalRecordPersistenceIT`.
    - `AppointmentItSupport.cleanUp` xóa bản bổ sung và bệnh án trước `visits`.
  - Không endpoint, không migration.

## Bối cảnh

T27 dựng visit-v1 #10 (hoàn tất lượt), #12–15 (xem / ghi bệnh án, cân nặng, bản bổ sung). Các điểm docs để ngỏ:

- Bệnh án được tạo lúc nào. erd chỉ nói "1–1, chỉ Visit nhóm `MEDICAL`".
- "Sửa bệnh án" của BR-QT-15 có gồm mỗi lần ghi khi lượt còn mở hay không. Convention 08 §8.3 chỉ ghi bản bổ sung, nhưng erd §6 lấy "người ghi từng phần khi gán lại lượt" từ `audit_logs`, và ADR-0063 §8 để lại nghĩa vụ đó cho T27.
- "Ngày khám" của BR-KB-06 là mốc nào.
- PUT với trường vắng hoặc null.
- Visit#5 đọc loại dịch vụ của Order: `VisitOrderApi.findServiceIds` (`sales/service/VisitOrderService.java:105`) không khóa, trong khi sales-v1 #5 (đổi dòng tự sinh) chỉ khóa `orders`.
- Ba cột FK chưa có index: `medical_record_addenda.medical_record_id`, `prescription_items.medical_record_id`, `vaccinations.visit_id`.

## Quyết định

1. **Tạo bệnh án ở lần ghi đầu tiên (#13, upsert).**
   - Lượt `MEDICAL` chưa ghi thì không có dòng. #12 trả khung rỗng; Visit `GROOMING` trả 404 (không có bệnh án).
   - Hoàn tất lượt có dịch vụ `EXAM` mà chưa có dòng = thiếu chẩn đoán → 400 BR-KB-02.
   - `locked` của response = lượt `COMPLETED` (hợp đồng: "Visit COMPLETED"), không đọc `locked_at`. `locked_at` = `completed_at`, chỉ ghi ở Visit#5 khi dòng đã có.
   - Entity: PK gán tay (khuôn `StaffProfile`), nên `save` đi đường `merge` (SELECT rồi INSERT).
2. **Audit mỗi lần ghi khi lượt còn `IN_PROGRESS`.**
   - `MEDICAL_RECORD_UPDATED`, `entity = medical_records`, id = `visitId`, before/after = `{examination, diagnosis, treatmentPlan, internalNote, followUpDate}`; before rỗng ở lần ghi đầu.
   - Đây là nguồn "ai ghi phần nào" sau khi gán lại lượt (BR-TN-08, erd §6, ADR-0063 §8). Response chỉ có `lastEditedBy`.
   - Bản bổ sung ghi `MEDICAL_RECORD_ADDENDUM_ADDED`, after = `{content, isInternal}`.
   - Convention 08 §8.3 được bổ sung cho khớp.
3. **Ngày khám (BR-KB-06) = ngày theo giờ Việt Nam của `visits.checked_in_at`.**
   - Điều kiện: `ngày check-in < followUpDate ≤ ngày check-in + MEDICAL_RECORD_FOLLOW_UP_MAX_DAYS`.
   - [CFG] đọc lúc ghi (ADR-0057). Lượt kéo qua nửa đêm không làm đổi biên.
4. **PUT thay toàn bộ 5 trường.**
   - Trường vắng hoặc `null` là xóa giá trị; `last_edited_by` = người gọi.
   - `UpdateMedicalRecordRequest` bỏ khoảng trắng hai đầu và đổi chuỗi rỗng thành `null`, nên "có chẩn đoán" chỉ cần khác `null`.
   - `AddendumRequest.content` bắt buộc; không có `@Size` vì hợp đồng và cột `TEXT` không có giới hạn.
5. **Visit#5 đọc loại dịch vụ qua `findServiceIds` không khóa — nợ D044.**
   - Chỉ chính VET của lượt mới đổi được dòng dịch vụ (BR-TN-01, BR-BH-02), nên race chỉ xảy ra khi cùng một người bấm "đổi dịch vụ" và "hoàn tất" cùng lúc.
   - Cách trả: BE-2 thêm method khóa Order rồi trả serviceIds (06 §6).
6. **Không migration ở T27 — nợ D043.**
   - Kiểm mũi tiêm của lượt: `hasVaccinationInVisit(petId, visitId)` lọc cả `pet_id` để đi tiền tố của `ix_vaccinations_pet_type_administered`.
   - Đọc bản bổ sung (và đơn thuốc ở T38) quét bảng cho tới khi có index.
   - D043 gồm cả hai trigger DB (xem *Bảng đảm bảo*).
7. **Khóa.**
   - Mọi thao tác ghi bệnh án, bản bổ sung, cân nặng, hoàn tất đều khóa `visits` `FOR NO KEY UPDATE` trước (`VisitRepository.lockById`, `MANDATORY`), rồi mới đọc `medical_records`.
   - Dòng bệnh án không có khóa riêng; thứ tự khóa của ADR-0058 §5 giữ nguyên.
   - Chỉ người phụ trách **hiện tại** (`visits.assignee_id`) được ghi và hoàn tất, kiểm dưới khóa lượt (ADR-0063 §8).
8. **Bất biến nằm ở service, không ở entity** (khuôn ADR-0058 §1).
   - Entity Phần 0 chỉ có factory `of(...)`.
   - Method nghiệp vụ (`rewrite`, `lock`) thêm cùng phần dựng thao tác.
   - Ngoại lệ là mapping: mọi cột của `MedicalRecordAddendum` có `updatable = false`.
9. **DTO.**
   - Tên theo khuôn `VisitResponse` ↔ schema `Visit` (`OpenApiContractParityIT` so cây tên property).
   - `PrescriptionItemResponse` và `VaccinationRecordResponse` có từ Phần 0 vì nằm trong `MedicalRecord`; danh sách rỗng cho tới T38 / T28.
   - `MedicalRecordResponse` chỉ dành cho nhân viên (có `internalNote`). Hồ sơ cho khách (#21) phải dùng DTO riêng, không chứa `internalNote` và bản bổ sung `isInternal` (convention 02 §DTO, BR-KH-07).

## Bảng đảm bảo

| # | Ràng buộc | Nguồn | Ai giữ | Phần | Test |
|---|---|---|---|---|---|
| G1 | Mỗi lượt tối đa 1 bệnh án | erd §6 | DB (PK = FK) | 0 | `MedicalRecordPersistenceIT.aVisitHasAtMostOneMedicalRecord` |
| G2 | Chỉ lượt `MEDICAL` có bệnh án | erd §6 | service #12, #13 | 1 | IT `GROOMING` → 404, không có dòng |
| G3 | Tối đa 1 ngày tái khám | BR-KB-06 | DB (một cột) | 0 | cấu trúc |
| G4 | Biên ngày tái khám | BR-KB-06, §3 | service #13 | 1 | biên = ngày khám, = +max, = +max+1; IT đổi [CFG] |
| G5 | Chỉ VET phụ trách hiện tại được ghi | BR-KB-01, ADR-0063 §8 | phân quyền + service dưới khóa | 1 | `InOrder`; IT VET khác, CARETAKER, người cũ sau gán lại |
| G6 | Đã khóa thì không sửa | BR-KB-01 | service #13 (409) | 1 | IT sau hoàn tất → 409, `xmin` không đổi |
| G7 | `locked_at` = `completed_at` | erd §6 | service Visit#5 | 3 | IT so tới µs |
| G8 | Bản bổ sung chỉ sau `COMPLETED` | BR-KB-01 | service #15 (409) | 4 | IT `IN_PROGRESS` → 409, không có dòng |
| G9 | Bản bổ sung chỉ thêm | BR-KB-01, erd LOG | entity `updatable = false`, service không xóa | 0 | `MedicalRecordAddendumTest.everyColumnIsInsertOnly` |
| G10 | Điều kiện hoàn tất EXAM / VACCINE / GROOMING | BR-KB-02, BR-SP-06 | service Visit#5 | 3 | bảng tổ hợp; nhánh lỗi không ghi |
| G11 | Lịch hẹn + Order cùng transaction | 03 Phụ lục | service + `AppointmentApi`, `VisitOrderApi` | 3 | IT trigger tạm → rollback |
| G12 | Biết ai ghi phần nào | BR-TN-08, erd §6 | audit mỗi lần ghi (§2) | 1 | IT hai VET → hai dòng audit |
| G13 | Khách không thấy nội dung nội bộ | BR-KH-07, convention 02 | DTO riêng | #21 | nghĩa vụ |
| G14 | Loài bị khóa khi đã có bệnh án | BR-KH-03 | `hasMedicalHistory` (đã có) | — | IT Phần 1 |

**DB không giữ G2, G6, G8, G9** (không có CHECK hay trigger). Trigger chờ ở D043:
- Chặn UPDATE 5 cột nội dung của `medical_records` khi `locked_at IS NOT NULL`, nhưng vẫn cho ST04 ghi `follow_up_reminded_at` / `follow_up_cancelled_at`.
- Chặn UPDATE / DELETE `medical_record_addenda` (khuôn `forbid_audit_log_change`, V1 L1096–1110).

## Hệ quả

- Ghi bệnh án lần đầu, kể cả khi mọi trường rỗng, tạo dòng bệnh án. Từ đó loài của thú bị khóa (BR-KH-03), vì `hasMedicalHistory` chỉ kiểm dòng tồn tại.
- Lượt chỉ có dịch vụ `VACCINE` không hoàn tất được cho tới khi có T28 (ghi mũi tiêm).
- `audit_logs` lớn thêm một dòng mỗi lần VET bấm lưu bệnh án.
- Không đụng bảng, migration hay endpoint nào đang có.

## Mutation (mỗi thay đổi phải làm một test đỏ)

| Mutation | Test đỏ |
|---|---|
| Bỏ `updatable = false` ở một cột của bản bổ sung | `MedicalRecordAddendumTest.everyColumnIsInsertOnly` |
| Thêm `@GeneratedValue` vào PK bệnh án | `MedicalRecordPersistenceIT` (INSERT lỗi) |
| Đổi lớp cha bản bổ sung sang `TimestampedEntity` | khởi động lỗi `ddl-auto=validate` (không có `updated_at`) |
| Map nhầm nguồn một trường / `locked` đọc từ `locked_at` | `MedicalRecordMapperTest` |
| Bỏ đổi chuỗi rỗng thành `null` | `MedicalRecordRequestValidationTest.medicalRecordFieldsAreStrippedAndBlankBecomesNull` |

## Đã cân nhắc

- **Tạo bệnh án lúc gọi lượt (Visit#3)** — mọi lượt `MEDICAL` đang mở đều có dòng, nhưng phải sửa luồng gọi lượt đã xong (ADR-0062) và khóa loài ngay khi gọi lượt.
- **Chỉ audit bản bổ sung** — đúng chữ của convention 08, nhưng mất "ai ghi phần nào" sau khi gán lại lượt (lệch erd §6).
- **Mốc "hôm nay"** — cùng một bệnh án sẽ cho kết quả hợp lệ khác nhau tùy lúc ghi.
- **PATCH (null = giữ nguyên)** — không xóa được giá trị (ví dụ bỏ ngày tái khám), lệch method PUT của hợp đồng.
- **V19 ngay (index + trigger)** — phải thống nhất số V với BE-2 trước, có thể chặn T27.
- **Method khóa Order ngay** — cần BE-2 đổi `VisitOrderApi`; để thành nợ D044.
- **Bất biến trong entity (`openFor(Visit)`, `rewrite` ném khi đã khóa)** — chặn thêm một lớp, nhưng lệch khuôn ADR-0058 §1; người dùng chọn giữ khuôn.
