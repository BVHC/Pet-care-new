# ADR-0108: Hồ sơ sức khỏe thú cưng — visit-v1 #21 `GET /api/pets/{petId}/health-record` (UC25, UC53; trả T28b)

- **Trạng thái:** Proposed (BE-1 chưa xem; cài ngày 2026-11-05 theo ủy quyền của Hiến "chốt giai đoạn 1, làm luôn phần của BE-1 / BE-2 nếu cần sửa trực tiếp mã". BE-1 xác nhận hoặc đổi thì supersede bằng ADR mới)
- **Ngày:** 2026-11-05
- **Liên quan:**
  - BR-KH-07, BR-KB-01, BR-KB-04, BR-KB-06, BR-TK-19; visit-v1 #21, schema `HealthRecord`, `HealthVisitEntry`, `UpcomingVaccination` (tái dùng `VaccinationRecord`, `PrescriptionItem`, `Addendum`).
  - ADR-0006 (OWNER), 0041 (hồ sơ sức khỏe cho khách thuộc module visit, không làm ở customer), 0066 (bệnh án), 0072 (khuôn reader), 0076 (đơn thuốc), 0092 (race chuyển chủ).
  - Nợ: T28b (`docs/time.md`), L-xx "Hồ sơ sức khỏe 404" của `test-tay-ghep-api-theo-cum.md`.
- **Triển khai:** `PetHealthRecordController`, `HealthRecordService`, `HealthRecordResponse`, `HealthVisitEntryResponse`, `UpcomingVaccinationResponse`; thêm `VisitRepository.findCompletedByPetId`, `VaccinationRepository.findByPetIdOrderByAdministeredOnDescIdDesc`, `MedicalRecordAddendumRepository.findByMedicalRecordIdInOrderByIdAsc`, `PrescriptionItemRepository.findByMedicalRecordIdInOrderByIdAsc`, `VaccinationRecordReader.forPet`, `PrescriptionItemReader.forRecords`. Test: `HealthRecordIT` (10 ca), `OpenApiContractParityIT` `hasSize(187)`. Không migration, không đổi hợp đồng (generator `m_visit.py` đã khai báo #21).

## Quyết định

1. **Một endpoint chỉ đọc, một transaction `REPEATABLE_READ`**, không khóa, không audit (BR-QT-15 không liệt kê). Lượt, bệnh án, đơn thuốc, bản bổ sung và mũi tiêm cùng một ảnh chụp.
2. **Ai đọc:** khách (A02), lễ tân (A06), VET (A07), CARETAKER (A08). Quản lý, quản trị 403 theo hợp đồng. Nhân viên đọc mọi thú ở mọi chi nhánh vì hồ sơ theo thú, không theo lượt; không gọi `BranchScope.check`.
3. **Khách:** phải là chủ hiện tại; chủ cũ sau chuyển chủ và khách khác đều 403 `ACCESS_DENIED_SCOPE_MISMATCH` ("Không phải chủ hiện tại", đúng hợp đồng, không 404 vì thú tồn tại). Hồ sơ khách `link_decision_pending` → 400 `BR-TK-19`. **Thứ tự lỗi:** 403 vai trò → 400 BR-TK-19 → 404 thú → 403 không phải chủ (BR-TK-19 xét trước tra thú, như mock FE: hồ sơ chờ liên kết không đọc được thú nào, kể cả thú không tồn tại).
4. **Không nhận ghi chú nội bộ (BR-KH-07, BR-KB-01):** khách không nhận `internalNote` và bản bổ sung `isInternal`. `internalNote` bỏ **hẳn khỏi JSON** (`@JsonInclude(NON_NULL)` trên đúng trường đó) thay vì trả `null`, để "không trả trường" là sự thật của API. Nhân viên có giá trị thật; bệnh án không có ghi chú nội bộ cũng bỏ trường (trường tùy chọn trong hợp đồng).
5. **`visits`:** chỉ lượt `COMPLETED` (bệnh án đã khóa, BR-KB-01), mọi nhóm dịch vụ và mọi chi nhánh, mới nhất trước (`checked_in_at DESC, id DESC`, index `ix_visits_pet_checked_in_at`). `visitDate` là ngày check-in theo giờ Việt Nam (ADR-0066 §3). Lượt `GROOMING`, hoặc `MEDICAL` hoàn tất mà chưa ghi bệnh án, trả các trường khám `null`, đơn thuốc và bản bổ sung rỗng. Lượt đang chờ / đang khám / hủy không xuất hiện (bệnh án chưa khóa hoặc không có).
6. **`vaccinations`:** mọi mũi tại hệ thống của thú (BR-KB-04: không có tiền sử tiêm ngoài hệ thống), ngày tiêm mới nhất trước, cùng ngày mũi ghi sau; `superseded` theo cột `superseded_at` (BR-TB-03). **`upcomingVaccinations`:** mũi chưa bị thay thế, sắp theo `nextDueDate` tăng dần rồi loại vaccine. Gồm cả mũi đã quá hạn tái chủng (FE tự hiển thị trạng thái), không lọc theo hôm nay.
7. **`upcomingFollowUpDate`:** ngày tái khám nhỏ nhất không trước hôm nay (giờ Việt Nam, `Clock`) trong các bệnh án đã khóa; không có thì `null` (trường vẫn có trong JSON). Ngày tái khám của lượt chưa khóa không tính (bệnh án còn sửa được).
8. **Không N+1 theo số lượt:** lượt (1 câu), bệnh án (1), đơn thuốc + tên thuốc (2), bản bổ sung + tên người ghi (2), mũi tiêm + tên loại vaccine + tên sản phẩm (3), tên chi nhánh (1 mỗi chi nhánh khác nhau). Không phân trang (như `MedicalRecord`: mảng nằm trong hợp đồng; tài liệu không có [CFG] giới hạn).
9. **Thú đã mất vẫn đọc được** (lịch sử không mất theo BR-KH-05).

## Hệ quả và nợ

- Hồ sơ rất dài (hàng trăm lượt) trả cả một lần: chấp nhận ở quy mô một thú vài chục lượt; đo lại nếu một thú vượt vài trăm lượt (khi đó cần phân trang, đổi hợp đồng).
- Thú chuyển chủ: chủ mới thấy toàn bộ lịch sử của thú (kể cả lượt thời chủ cũ), như BR-KH-06 (hồ sơ theo thú). Chủ cũ mất quyền đọc ngay (403).
- FE (`PetDetailDrawer`, tab "Hồ sơ sức khỏe") nay gọi được BE thật; mock `customer-pet.mock.ts` đã khớp hình dạng này. Chưa đo trên trình duyệt thật.
