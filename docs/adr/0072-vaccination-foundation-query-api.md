# ADR-0072: Nền mũi tiêm (`Vaccination`) và `VaccinationQueryApi` thật — mũi tiêm trong bệnh án #12/#13, khoảng trống mũi bắt buộc khi lưu trú

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-25
- **Liên quan:**
  - BR-KB-04, BR-LT-05, BR-SP-03, BR-SP-07, BR-KB-01; visit-v1 #12, #13, schema `VaccinationRecord`.
  - ADR-0066 (nền bệnh án), 0067 (khuôn #13), 0070 (thứ tự đọc trước ghi); `06-module-contracts` §3.
  - Nợ: D046 (số lô), D043 (index `vaccinations`).
- **Triển khai (T28 phần 0):** `Vaccination`, `VaccinationRepository`, `VaccinationQueryService` (thay `VaccinationQueryApiPlaceholder`, trả 500 ở đặt chỗ lưu trú T31 và xóa loại vaccine T9), `VaccinationRecordReader`, `MedicalRecordMapper.toVaccinationRecord`. Không endpoint mới, không migration. Test: `VaccinationQueryServiceTest`, `VaccinationRecordReaderTest`, `VaccinationQueryApiIT` (12), `VaccinationBoardingIT` (2, đặt chỗ lưu trú với bean thật), `MedicalRecordIT` (+7).

## Quyết định

1. **Khoảng trống (BR-LT-05):** với loài của thú, mỗi loại vaccine có phác đồ đang dùng cờ bắt buộc khi lưu trú là một khoảng trống nếu thú **chưa từng tiêm** (ngày null) hoặc mũi **gần nhất** có `next_due_date < onDate`. Đúng ngày tái chủng vẫn đạt. Mũi gần nhất = `administered_on` lớn nhất, cùng ngày thì `id` lớn hơn; không xét `superseded_at`. Kết quả sắp theo `vaccineTypeId`. Chỉ mũi tiêm tại hệ thống (nguyên tắc 9).
2. **Đọc không khóa, tham gia transaction của bên gọi** (`readOnly`, `REQUIRED`, không `catch`): lỗi dữ liệu rollback cả đặt chỗ lẫn mã chứng từ. **Nghĩa vụ bên gọi:** tự phân quyền (role, chủ thú, chi nhánh) trước khi gọi; khi **chặn** (nhận thú, T42) khóa `pets FOR SHARE` trước. Lúc đặt chỗ chỉ cảnh báo nên đọc READ COMMITTED là đủ.
3. **#12/#13 trả mũi tiêm của chính lượt** (`pet_id + visit_id`, theo `id`; có cả khi lượt chưa có dòng bệnh án hoặc đã `COMPLETED`). Mọi giá trị là cột đã chụp lúc tiêm (`dose_number`, `next_due_date`, `branch_id`), không tính lại từ phác đồ hiện tại (BR-SP-03). Tên loại vaccine / sản phẩm tra theo lô qua `CatalogQueryApi` (tối đa 2 câu, không câu nào khi không có mũi), không lọc `active`: đã ngừng vẫn có tên. Tên sản phẩm là tên **hiện tại** (catalog không chụp tên). Thiếu tên là dữ liệu hỏng (500).
4. **#13 đọc mũi tiêm sau mọi guard và trước mọi lệnh ghi:** lỗi đọc thì chưa ghi gì (không bệnh án, không audit). Không đổi thứ tự khóa / lỗi của ADR-0067.
5. **`lotNumber`, `expiryDate` trả `null`** (optional trong contract): inventory chưa có API đọc lô theo id → D046.
6. **Ánh xạ:** `dose_number` là `SMALLINT` nên cần `@JdbcTypeCode(SqlTypes.SMALLINT)` (như `VaccinationProtocol`); thiếu thì `ddl-auto=validate` làm hỏng khởi động.

## Bảng đảm bảo

| # | Ràng buộc | Ai giữ | Test |
|---|---|---|---|
| G1 | Chưa tiêm / quá hạn → khoảng trống; đúng hạn → đạt | `VaccinationQueryService.gapOf` | `VaccinationQueryServiceTest`, `VaccinationQueryApiIT` (biên `==`, `-1`) |
| G2 | Mũi gần nhất quyết định (cả cùng ngày) | `findLatestDoses` `DISTINCT ON … id DESC` | IT `theLatestDose…`, `twoDosesOnTheSameDay…` |
| G3 | Chỉ loại bắt buộc của đúng loài, phác đồ đang dùng | `CatalogQueryApi.findBoardingRequiredVaccineTypeIds` | IT `theSpeciesOfThePet…`, `anInactiveProtocol…` |
| G4 | Xóa loại đã có mũi → 400 BR-SP-07, hết 500 | `existsByVaccineTypeId` + `VaccineTypeService` | IT `deletingATypeThatHasADose…`, `deletingAnUnusedType…` |
| G5 | Mũi trong response là giá trị đã chụp, không lẫn lượt khác | mapper + `findByPetIdAndVisitId…` | `MedicalRecordIT` `getReturnsOnlyTheVisitsDoses…` (đổi phác đồ, ngừng loại / sản phẩm) |
| G6 | #13 lỗi giữa chừng không để lại bệnh án / audit | đọc trước ghi | `MedicalRecordServiceTest` (`InOrder`, reader ném), IT trigger `audit_logs` |
| G7 | #13 không ghi dòng `vaccinations` | entity không setter | IT so `xmin` |
| G8 | Vai trò / chi nhánh ngoài hợp đồng không thấy mũi | `@PreAuthorize` + `BranchScope` sẵn có | IT `doseDataNeverReachesRoles…`; unit `verifyNoInteractions(doses)` |

DB không giữ G1–G3 (nghiệp vụ ở service).

## Hệ quả

- Hết 500 ở `POST /api/boarding-bookings` và `DELETE /api/vaccine-types/{id}`; đặt chỗ lưu trú nhận `vaccineWarnings` thật.
- `existsByVaccineTypeId` quét `vaccinations` (chưa index `vaccine_type_id`, D043); bảng nhỏ trong giai đoạn 1.
- Phần 2–3 (ghi / xóa mũi) thêm factory và setter cho `superseded_at`; phần 1 (#18 gợi ý) tái dùng `findLatestDoses` và `VaccinationRecordReader`.
- Race mũi chèn song song lúc xóa loại vaccine: FK `RESTRICT` → 409 (không thêm IT, chưa có luồng ghi).

## Đã cân nhắc

- **Gap khi `next_due_date <= onDate`** — chặt hơn nhưng BR-LT-05 nói "chưa quá ngày tái chủng".
- **Thêm `StockQueryApi.findLots` ngay** — sửa module BE-2; chọn để null và ghi nợ.
- **Tính `nextDueDate` / `doseNumber` từ phác đồ hiện tại** — sai lịch sử khi phác đồ đổi (BR-SP-03).
