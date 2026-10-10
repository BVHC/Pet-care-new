# ADR-0026: Nhân viên tự sửa hồ sơ (khóa `accounts`, giữ / xóa, định dạng) và đội ngũ bác sĩ công khai do identity lọc chi nhánh

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-10
- **Supersedes:** ADR-0024 mục 5 (`listPublicVets` để bên gọi — content — lọc chi nhánh `ACTIVE`).
- **Liên quan:**
  - BR-TK-01 (nhân viên bắt buộc có SĐT, SĐT không duy nhất), BR-TK-11, BR-TK-15 (phạm vi tự sửa, email không tự sửa), BR-TK-17, BR-TK-20 (hồ sơ VET công khai, mô tả ≤ 500 ký tự [CFG]), BR-CK-01 (trang công khai chỉ chi nhánh `ACTIVE`), BR-QT-13; UC06, UC15.
  - identity-v1 #10 `PATCH /me/staff-profile`, #37 `GET /public/vets`; content-v1 L12 ("bác sĩ công khai nằm ở identity").
  - `docs/05-erd.md` `accounts.phone` (L85: "Chuẩn hóa dạng `0xxxxxxxxx`"), `staff_profiles` (L105–116); `docs/api/00-method.md` L89 (cơ chế upload ảnh TBD).
  - ADR-0003, 0004, 0005, 0020, 0021 (thứ tự khóa `accounts → sessions`), 0022 mục 9 (kiểm lại BR-TK-11 dưới khóa), 0024. Nợ D014.
- **Triển khai (task 08 phần 2):**
  - `identity/controller/MeController.updateStaffProfile`, `identity/service/StaffProfileService`, `identity/dto/UpdateStaffProfileRequest`, `Account.changeStaffPhone`, `StaffProfile.updateSelfProfile`; response dùng lại `MeMapper.toStaffProfileResponse`.
  - `identity/controller/PublicVetController`, `identity/service/PublicVetService`, `identity/dto/PublicVetResponse`, `identity/mapper/PublicVetMapper`.
  - Generator hợp đồng: kiểu `mobile`, `https_url` (`docs/api/generator/lib.py`), dùng cho 4 schema nhân viên và `avatarUrl` hồ sơ nhân viên / `PublicVet`.
  - Test: `StaffProfileServiceTest`, `PublicVetServiceTest`, `AccountTest` (SĐT nhân viên), `StaffProfileTest`, `module/identity/StaffProfileIT`, `module/identity/PublicVetIT`.

## Bối cảnh

- **#10:** nhân viên tự sửa họ tên, ảnh, SĐT và (VET) chuyên môn, mô tả ngắn. SĐT nằm ở `accounts`, phần còn lại ở `staff_profiles`, nên một PATCH ghi hai bảng. Hibernate cập nhật **đủ mọi cột** của entity bẩn (không `@DynamicUpdate`): đọc hồ sơ không khóa rồi ghi lại sẽ ghi đè `staff_profiles.branch_id` (T14 điều chuyển) hoặc mất trường của một PATCH chạy song song. Hợp đồng chỉ nói "null = giữ" mà không nói cách xóa trường; không quy định định dạng `avatarUrl`; kiểu `phone` dùng chung `^0[0-9]{9,10}$` lệch erd `accounts.phone` (10 số) và luồng đăng ký (`^0\d{9}$`).
- **#37:** ADR-0024 mục 5 để identity trả VET không lọc chi nhánh, content lọc và thêm `branchName`. Nhưng hợp đồng đặt #37 ở identity (identity-v1, content-v1 L12), `BranchQueryApi` thật đã có (BE-2, 09/10), và người dùng muốn #37 làm ngay trong T8 thay vì chờ T32 (23/10).

## Quyết định

1. **Khóa dòng `accounts` trước khi đọc `staff_profiles`.** `StaffProfileService.updateStaffProfile` (một `@Transactional`): `findByIdForUpdate` (`FOR NO KEY UPDATE`) → `staffProfiles.findById` → guard → ghi → `flush()` → map. Thứ tự khóa **`accounts → staff_profiles`** — mọi luồng ghi `staff_profiles` (T14: điều chuyển, đổi chức vụ) phải khóa `accounts` trước. `touchLastSeen` dùng `SKIP LOCKED` nên không chờ và không bị ghi đè. `flush()` trước khi map để lỗi khóa / ràng buộc ném trong method (→ 409) thay vì lúc commit.
2. **Thứ tự kiểm:** hình thức (Bean Validation, 400 `VALIDATION_FAILED`) → có `email` → 400 BR-TK-15 (trước mọi đọc / ghi) → dưới khóa: tài khoản bị khóa / không `ACTIVE` → 400 BR-TK-11 (như ADR-0022 mục 9) → không phải VET mà gửi `specialty` / `bio` (kể cả chuỗi rỗng) → **403** `AccessDeniedScopeException` → `bio` gửi lên vượt `vet.bio_max_length` [CFG] (đếm code point) → 400 BR-TK-20. Mọi từ chối xảy ra trước lệnh ghi đầu tiên; không audit (convention 08 §8.3).
3. **`null` = giữ nguyên; chuỗi rỗng (sau `strip`) = xóa** với `avatarUrl`, `specialty`, `bio`. `fullName`, `phone` rỗng → 400 (nhân viên luôn có họ tên và SĐT). BR-TK-20 chỉ kiểm khi request **có gửi** `bio`: ADMIN hạ [CFG] không chặn VET sửa trường khác (BR-QT-13). Field lạ (`role`, `branchId`…) bị bỏ qua.
4. **SĐT nhân viên 10 số `^0\d{9}$`** theo erd `accounts.phone` (ưu tiên cao hơn `docs/api`), khớp luồng đăng ký; không kiểm trùng (BR-TK-01 v16). Generator thêm kiểu `mobile`, dùng cho `StaffProfile`, `UpdateStaffProfileRequest`, `StaffResponse`, `CreateStaffRequest`; kiểu `phone` của module khác giữ nguyên.
5. **`avatarUrl` của nhân viên chỉ nhận `https://`**: ảnh hiện ở trang công khai — chặn `javascript:` / `data:` và mixed content. Khi chốt cơ chế upload (00-method L89) sẽ siết theo domain kho ảnh.
6. **#37 do identity phục vụ, lọc chi nhánh qua `BranchQueryApi`.** `PublicVetService` (`readOnly`): `listActiveBranches()` → map id → tên; `StaffDirectoryApi.listPublicVets()` (định nghĩa "VET công khai" vẫn một chỗ: `ACTIVE`, không bị khóa, khóa tạm không tính) lọc theo chi nhánh `ACTIVE`; VET không gắn chi nhánh bị loại. `branchId` không phải chi nhánh `ACTIVE` (không tồn tại, `DRAFT`) → **200 mảng rỗng**, không đọc nhân viên — không lộ chi nhánh nháp có tồn tại. Lọc trong bộ nhớ (số VET nhỏ, không có index `staff_profiles(branch_id)` — ADR-0024).

## Lý do

| Phương án bị loại | Vì sao |
|---|---|
| Content làm #37 (giữ ADR-0024 mục 5) | Hợp đồng đặt #37 ở identity; chờ T32 (23/10) trong khi `BranchQueryApi` đã có; hai module cùng khai báo `/api/public/vets` sẽ trùng mapping (app không khởi động) |
| `@DynamicUpdate` thay cho khóa | Chỉ thu hẹp cột được ghi, không chống mất cập nhật khi hai PATCH sửa cùng trường, và không cho kiểm lại BR-TK-11 dưới khóa |
| Khóa `staff_profiles` thay vì `accounts` | `accounts` là dòng mọi luồng hủy phiên / khóa / vô hiệu hóa đã khóa trước (ADR-0021); khóa nó giữ một thứ tự duy nhất và thấy ngay trạng thái khóa mới |
| Non-VET gửi specialty/bio: 400 BR-TK-15 hoặc bỏ qua im lặng | Hợp đồng liệt kê 403 cho #10; convention 04 §4.4: vượt phạm vi → `AccessDeniedScopeException`; bỏ qua im lặng làm client tưởng đã lưu |
| SĐT theo hợp đồng 10–11 số | erd `accounts.phone` ghi rõ dạng chuẩn 10 số và có ưu tiên cao hơn; SĐT nhân viên là di động cá nhân |
| Không kiểm `avatarUrl` | Ảnh hiện trên trang công khai; `https` rẻ, chặn được scheme nguy hiểm và mixed content |
| `branchId` lạ / DRAFT → 404 | Hợp đồng không có 404 cho #37; trang công khai không nên tiết lộ chi nhánh nháp có tồn tại |

## Hệ quả

- identity phụ thuộc `branch.api.BranchQueryApi` (chỉ đọc). Không có vòng bean: `BranchQueryService` không inject gì của identity; `BranchService → StaffDirectoryApi` vẫn một chiều.
- T14 (UC08) phải khóa `accounts` trước khi ghi `staff_profiles` (ghi thêm ở nợ D014), dùng kiểu `mobile` cho `CreateStaffRequest.phone`, và quyết định giữ hay xóa `specialty` / `bio` khi VET đổi sang chức vụ khác — hiện chúng còn trong DB, chỉ trả cho chính người đó qua `GET /me` / PATCH; trang công khai lọc `role = VET` nên không lộ.
- Tên nhân viên không được chụp lại ở bảng nghiệp vụ nào: đổi họ tên hiện ngay ở mọi nơi đọc tên hiện tại (gán lượt, nhật ký…). Đúng BR-TK-15; "kèm tên người ghi" của BR-TN-08 gắn với người (id).
- Dữ liệu cũ không theo quy tắc mới (SĐT 11 số, `avatarUrl` không phải `https` do dev chèn bằng SQL) giữ nguyên; quy tắc chỉ áp cho lần sửa mới.
- `"email": null` không phân biệt được với không gửi (record + Jackson) nên được bỏ qua — không ghi gì, an toàn.
- BE-2 không cài `GET /api/public/vets` ở content (T32); hệ thống chưa có quy tắc SĐT / URL chung cho các module khác — ghi ở `docs/06-module-contracts.md` §7 để trao đổi.
