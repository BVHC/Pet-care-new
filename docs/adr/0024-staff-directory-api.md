# ADR-0024: `StaffDirectoryApi` — "đang hoạt động", "online", đếm BRANCH_MANAGER

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-09
- **Liên quan:**
  - BR-QT-04 (≥ 1 BRANCH_MANAGER `ACTIVE`; ngoại lệ quản lý cuối cùng bị khóa), BR-CN-01, Chi nhánh#2; BR-TN-05, 06 (gán lượt, online); BR-TK-20, BR-CK-01 (VET công khai, UC15); BR-QT-11 (khóa là cờ `is_locked` riêng, 03 #1).
  - `docs/06-module-contracts.md` §4 (bên gọi: branch kích hoạt, visit gán lượt, content trang công khai, mọi module tìm người nhận thông báo), §6 (hạn giao 08/10).
  - identity-v1 #37 `GET /public/vets` ("Chỉ VET ACTIVE, không khóa"); ADR-0003 (BranchScope), ADR-0021 (`last_seen_at`). Nợ D014 (đếm không khóa).
- **Triển khai:**
  - `identity/service/StaffDirectoryService` (`@Transactional(readOnly = true) implements StaffDirectoryApi`), `identity/repository/{StaffProfileRepository, StaffRow, PublicVetRow}`, `AccountRepository.{findActiveIdsByRole, findEmailById}`. Javadoc `identity/api/StaffDirectoryApi` ghi lại các định nghĩa dưới đây.
  - Xóa `identity/service/StaffDirectoryApiPlaceholder` (BE-2 tạo 2026-10-09) cùng test của nó.
  - Test: `StaffDirectoryServiceTest`, `module/identity/StaffDirectoryIT` (gồm kích hoạt chi nhánh qua HTTP không mock).

## Bối cảnh

`StaffDirectoryApi` là interface đọc nhân viên của identity cho module khác. Tới 2026-10-09 chỉ có placeholder ném `UnsupportedOperationException`, nên `POST /api/branches/{id}/activate` trả 500 trên app thật (IT của branch mock interface nên không thấy). Javadoc interface nói "active = status ACTIVE và không bị khóa" nhưng không nói `countBranchManagers` tính ai, "online" tính thế nào, và VET công khai có lọc chi nhánh không.

## Quyết định

1. **"Đang hoạt động"** (`StaffSummary.active`, `findAssignableStaff`, `findActiveStaffIds`, `findActiveSuperManagerIds`, `listPublicVets`) = `status = ACTIVE` **và** `is_locked = false`. Khóa tạm do đăng nhập sai (`locked_until`, ST01) **không** tính: nó chỉ chặn đăng nhập, nhân viên vẫn là người của chi nhánh.
2. **`countBranchManagers(branchId)`** = số tài khoản role `BRANCH_MANAGER`, `status = ACTIVE`, `staff_profiles.branch_id = branchId`, **tính cả người đang bị khóa** (người dùng chốt 2026-10-09). BR-QT-04 đòi "BRANCH_MANAGER `ACTIVE`" và cho phép quản lý cuối cùng bị khóa mà chi nhánh vẫn hoạt động, tức khóa độc lập với "ACTIVE". `DISABLED` không tính. T14 dùng lại đúng định nghĩa này cho guard "quản lý cuối cùng" (vô hiệu hóa, điều chuyển, đổi chức vụ).
3. **"Online"** = `last_seen_at >= now − staff.online_window_minutes` [CFG] (mặc định 10 phút); đúng bằng mốc vẫn online; `NULL` = offline. `now` lấy từ bean `Clock`.
4. **Thứ tự `findAssignableStaff`**: online trước, trong mỗi nhóm theo họ tên rồi `accountId` (BR-TN-06: chỉ là gợi ý, không lọc người offline).
5. **`listPublicVets`** không lọc chi nhánh `ACTIVE`: identity không đọc trạng thái chi nhánh. Bên gọi (content, UC15) lọc bằng `BranchQueryApi.listActiveBranches` và thêm `branchName` cho `PublicVet` (identity-v1).
6. **`findActiveSuperManagerIds`** không yêu cầu `staff_profiles` (SUPER_MANAGER không gắn chi nhánh). **`findStaff`** trả rỗng với tài khoản không có `staff_profiles` (khách). **`findEmail`** trả email mọi trạng thái, mọi role.
7. Mọi câu đọc là **projection** (record), không nạp entity `Account` vào persistence context của transaction bên gọi (cùng lý do `AccountCredential`, ADR-0019). Service `readOnly`, tham gia transaction bên gọi (`REQUIRED`, convention 07).
8. **Không thêm index `staff_profiles(branch_id)`**: bảng nhân viên ở mức hàng trăm dòng, không có xóa cứng `branches`; thêm khi số đo cho thấy cần.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Đếm quản lý `ACTIVE` và không khóa | Chi nhánh có một quản lý đang bị ADMIN khóa thì không kích hoạt được, trong khi BR-QT-04 coi chi nhánh vẫn hoạt động bình thường khi quản lý cuối cùng bị khóa; T14 sẽ cần một định nghĩa thứ hai |
| Đếm mọi trạng thái trừ `DISABLED` | Với nhân viên tương đương quyết định 2 (nhân viên không có `PENDING`); viết theo `status = ACTIVE` khớp chữ của rule |
| Coi khóa tạm (`locked_until`) là không hoạt động | Khóa tạm tự hết sau 15 phút và do người khác gõ sai mật khẩu gây ra được; gán lượt / nhận thông báo không nên phụ thuộc |
| identity lọc VET theo chi nhánh `ACTIVE` | Phải gọi `BranchQueryApi` từ identity chỉ cho một trang công khai; content đã cần `listActiveBranches` để hiển thị tên chi nhánh |
| Giữ placeholder tới T14 (QT 12–13/10) | Kích hoạt chi nhánh 500 trên app thật, quá hạn 08/10; các method đều là câu đọc đơn giản |

## Hệ quả

- `POST /api/branches/{id}/activate` chạy thật: 400 `BR-QT-04` khi chưa có quản lý `ACTIVE`, 200 khi có (kể cả quản lý đang bị khóa).
- `countBranchManagers` đọc không khóa. `activateBranch` khóa dòng `branches`, nhưng luồng làm giảm số quản lý (T14) chưa có; khi có, nó phải khóa cùng dòng `branches` trước (thứ tự `branches → accounts`, cần method khóa trên `BranchApi` của BE-2), nếu không có thể kích hoạt được chi nhánh vừa mất quản lý cuối cùng — **nợ D014**.
- **Đã kiểm 2026-10-09:** `mvn clean verify` xanh (778 unit + 364 IT). Mutation (đã hoàn tác): thêm `a.locked = false` vào câu đếm → `StaffDirectoryIT.countsActiveBranchManagersIncludingLockedOnes` và `branchActivationUsesTheRealDirectory` đỏ. Smoke `docker compose` (DB dev, V9 áp thành công): kích hoạt chi nhánh chưa có quản lý → 400 `BR-QT-04`; thêm BRANCH_MANAGER `ACTIVE` đang bị khóa → 200 `ACTIVE` (dữ liệu smoke đã xóa, trừ dòng `audit_logs` vì insert-only).
- Visit (gán lượt), content (UC15) và các module gửi thông báo dùng được interface thật; IT của BE-2 (`BranchAdminIT`, `BranchServiceTest`) vẫn mock, không cần sửa.
