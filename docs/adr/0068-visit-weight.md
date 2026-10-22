# ADR-0068: Ghi cân nặng khi khám (`POST /api/visits/{visitId}/weight`) — `PetApi.recordWeight` khóa `FOR SHARE`, trả bản ghi

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-22
- **Đánh số lại:** ban đầu là ADR-0066; đổi +2 ngày 2026-10-22 vì trùng ADR-0064, 0065 của BE-2 (vào `dev` trước). Mọi tham chiếu ADR-0064…0067 trong code / tài liệu visit đã đổi theo (+2).
- **Liên quan:**
  - BR-KH-04 (02 L90), BR-KH-05, BR-TN-05, 08, BR-QT-15 (không liệt kê cân nặng); 03 §4 Visit (IN_PROGRESS: VET ghi bệnh án).
  - visit-v1 #14, schema `RecordWeightRequest`, `WeightRecordRef`, A8; customer-v1 #20, #21.
  - ADR-0041 §8 (`PetApi.recordWeight`), 0058 §5 (thứ tự khóa), 0066, 0067 (khuôn #13).
  - Nợ: không mở, không trả.
- **Triển khai (T27 phần 2):** `MedicalRecordService.recordWeight`, `MedicalRecordController` (gốc `/api/visits/{visitId}`), `RecordWeightRequest`, `WeightRecordRefResponse`, `MedicalRecordMapper.toWeightRef`; customer: `api/RecordedWeight`, `PetApi.recordWeight`, `PetWeightApiService`. Không migration.

## Bối cảnh

`weight_records` thuộc customer (BE-2) nên #14 ghi qua `PetApi.recordWeight`. Bản có sẵn (ADR-0041) có hai vấn đề với visit:

- Khóa thú `FOR NO KEY UPDATE`. Visit khóa `pets FOR SHARE` → `visits` (ADR-0058 §5, 0067 §1); nếu #14 giữ SHARE rồi gọi hàm này, khóa phải nâng lên NO KEY UPDATE, và #14 chạy song song với #13 trên cùng lượt sẽ deadlock (#13 giữ SHARE, chờ khóa lượt mà #14 đang giữ).
- Chỉ trả `Long`; response #14 cần kg đã chuẩn hóa và `measuredAt`.

## Quyết định

1. **`PetApi.recordWeight` khóa `FOR SHARE`** (`PetRepository.findByIdForShare`) và trả `RecordedWeight(weightRecordId, weightKg, measuredAt)`; `measuredAt` cắt về µs để bằng giá trị đọc lại. SHARE vẫn xung đột với `NO KEY UPDATE` của đánh dấu đã mất, chuyển chủ, đổi loài nên guard "không ghi cân cho thú vừa bị đánh dấu" còn nguyên. Luồng chủ tự khai (`PetWeightService`, customer-v1 #21) không đổi. BE-1 sửa trên `manh`; BE-2 xác nhận ở 06 §6.
2. **Thứ tự #14:** 400 body → 403 vai trò → 404 lượt → 403 chi nhánh → 404 `GROOMING` → `pets FOR SHARE` → `visits` → 403 không phải người phụ trách → 409 không `IN_PROGRESS` → `recordWeight` (400 BR-KH-04, BR-KH-05). Khuôn #13; lượt `GROOMING` không có bệnh án nên 404 (người dùng chốt 22/10).
3. **Thú, nguồn, người ghi do server lấy** (`summary.petId()`, `VET`, `visitId`, người gọi); body chỉ có `weightKg`, field lạ bị bỏ qua.
4. **Không audit** (BR-QT-15 không liệt kê; `PetWeightService` cũng không). Mỗi lần gọi là một bản ghi LOG (A8).

Bị loại: nâng khóa thú lên NO KEY UPDATE (deadlock với #13); ghi trước, kiểm người phụ trách sau (BR-KH-04 đứng trước 403, trái quy ước guard trước ghi); chờ BE-2 sửa.

## Bảng đảm bảo

| Ràng buộc | Ai giữ | Test |
|---|---|---|
| Chỉ VET phụ trách hiện tại, lượt `IN_PROGRESS` | service dưới khóa lượt | `MedicalRecordServiceTest` (`InOrder`, không gọi `recordWeight` ở nhánh lỗi); IT thứ tự lỗi, race khóa lượt |
| Không nâng khóa thú | `findByIdForShare` | `PetWeightApiServiceTest`; IT giữ `pets FOR SHARE` vẫn 201 |
| Chờ ghi thú, chưa khóa lượt | khóa thú trước lượt | IT giữ `pets FOR NO KEY UPDATE`, `visits … NOWAIT` thành công |
| Cân > 0, ≤ 9999.99 | `PetWeightApiService.normalize` | IT 0, -1, 0.004, 10000 |
| Thú, nguồn, người ghi không đến từ body | service | IT body có `petId`, `source`, `recordedBy`, `visitId` |
| Xác thực, vai trò, chi nhánh | JWT + session, `@PreAuthorize`, `BranchScope` | IT 401 (cả tài khoản bị khóa), BR-TK-17, 403 mọi vai trò, đổi chức vụ |

## Hệ quả

- `PetApi.recordWeight` đổi kiểu trả về; boarding (INTAKE, T42) dùng cùng kiểu và cùng khóa SHARE.
- Bên gọi `recordWeight` phải đã giữ hoặc chấp nhận khóa thú ở mức SHARE; không còn khóa `NO KEY UPDATE` từ hàm này.
