# Test case FE — T22 (Admin: tài khoản nhân viên, khóa/mở khóa, cấu hình tham số, mẫu thông báo, audit log)

> Hợp đồng: `docs/api/identity-v1.md` #15–24 (Staff), #28–30 (Accounts), #31–35 (Config + Templates), #36 (Audit). UC08–UC11. BR-QT-01…16, BR-TK-11, 16, 17. Cập nhật 2026-10-13.
> Cột cuối mỗi bảng ghi test tự động tương ứng.

## 1. Chuẩn bị

**Chạy với mock (mặc định, không cần BE):**
- `cd FE && npm run dev`, mở `http://localhost:5173`. Đăng nhập nhân viên ở `/admin/login`.

| Tài khoản | Role | Quyền xem | Dùng cho |
|---|---|---|---|
| `admin@petcare.vn` | ADMIN | Mọi trang quản trị, bao gồm audit, system-configs, notification-templates | Đầy đủ |
| `chuoi@petcare.vn` | SUPER_MANAGER | `/admin/users` (mọi chi nhánh, A05–A08), `/admin/audit` chỉ xem, KHÔNG có `/admin/system-configs` và `/admin/notification-templates` (cả hai chỉ ADMIN) | Phân cấp 1 |
| `manager@store1.vn` | BRANCH_MANAGER | `/admin/users` chỉ chi nhánh 1, A06–A08; không có `/admin/audit`, `/admin/system-configs`, `/admin/notification-templates` | Phân cấp 2 |
| `manager2@store2.vn` | BRANCH_MANAGER | Chỉ thấy nhân viên chi nhánh 2 | Phân cấp 3 |

**Dữ liệu mẫu mock (sau khi sửa ở task này):**

| Tài khoản | Role | Chi nhánh | Status | isLocked | Ghi chú |
|---|---|---|---|---|---|
| `admin@petcare.vn` | ADMIN | — | ACTIVE | false | Tự tạo bằng SQL, không thuộc chi nhánh |
| `chuoi@petcare.vn` | SUPER_MANAGER | — | ACTIVE | false | Không thuộc chi nhánh |
| `manager@store1.vn` | BRANCH_MANAGER | 1 | ACTIVE | false | Quản lý cuối của CN1 |
| `manager2@store2.vn` | BRANCH_MANAGER | 2 | ACTIVE | false | Quản lý duy nhất của CN2 |
| `reception@store1.vn` | RECEPTIONIST | 1 | ACTIVE | false | |
| `doctor@store1.vn` | VET | 1 | ACTIVE | false | Có `specialty`, `bio` |
| `caretaker@store1.vn` | CARETAKER | 1 | ACTIVE | false | |
| `newstaff@store1.vn` | RECEPTIONIST | 1 | ACTIVE | true (`mustChangePassword`) | Sau khi tạo |

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-identity` (và `petcare-mock-system-config` sau khi thêm), rồi F5.

**Chạy với BE thật** (đặt `VITE_API_MOCK=false` trong `FE/.env`):
- BE đã có `/api/staff/**`, `/api/accounts/**`, `/api/system-configs`, `/api/notification-templates`, `/api/audit-logs` (xem T14, T15; commit `bd8962a`).
- Tài khoản ADMIN phải tạo bằng SQL vì T4 không seed.
- SUPER_MANAGER + BRANCH_MANAGER có thể tạo qua `/admin/users` UI.
- Mock không còn giả lập lỗi 500 khi vô hiệu hóa / điều chuyển / khóa A05–A08 (gỡ 23/10): nợ D016 đã trả ở BE (`VisitQueryApi` T26, `CashierShiftQueryApi` T30). UI chưa hiển thị `pendingWork` khi khóa (BR-QT-11); mock luôn trả `pendingWork: []`.

## 2. `/admin/users` — Danh sách nhân viên (UC08)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 2.1 | Đăng nhập `admin@petcare.vn`, mở `/admin/users` | Bảng 7 nhân viên (6 từ mock + 1 đã tạo ở bước 3). Cột "Chi nhánh" thay cột "Tổ chức". Cột "Trạng thái" hiện 1 trong `PENDING/Đang hoạt động (ACTIVE)/Vô hiệu hóa (DISABLED)`. ADMIN hiện 0 chi nhánh, SUPER_MANAGER hiện "—" | `AdminUsersPage.test` |
| 2.2 | Tìm `doctor@store1.vn` | Lọc đúng 1 dòng, badge role `Bác sĩ` xanh | `AdminUsersPage.test` |
| 2.3 | Lọc theo role = BRANCH_MANAGER | SUPER_MANAGER thấy 2; BRANCH_MANAGER (`manager@store1.vn`) chỉ thấy 1 (mình) | `AdminUsersPage.test` |
| 2.4 | Lọc theo chi nhánh khác (BRANCH_MANAGER gửi `branchId=2`) | Bảng rỗng + toast 403 (BR-QT-07) | `AdminUsersPage.test` |
| 2.5 | Bấm icon mắt một nhân viên | Modal chi tiết: accountId, email, SĐT `0900000xxx`, role, status, isLocked, branchId, specialty/bio (VET), `mustChangePassword` | `AdminUsersPage.test` |
| 2.6 | Bấm "Sửa" | Mở form: họ tên, SĐT (BR-TK-15 không cho sửa email). VET thêm ô specialty + bio ≤ 500 | (test sau) |
| 2.7 | Đăng nhập `manager@store1.vn` vào `/admin/users` | Bảng chỉ nhân viên CN1 (4 dòng: manager + reception + doctor + caretaker); "Tổ chức" → "Chi nhánh 1". SUPER_MANAGER/ADMIN không hiện ở đây | `AdminUsersPage.test` |

## 3. `/admin/users` — Tạo tài khoản nhân viên (UC08, BR-QT-01, 02, 03)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 3.1 | Bấm "Thêm người dùng" → nhập email mới, SĐT 10 số, họ tên, chọn role RECEPTIONIST, không chọn chi nhánh (BRANCH_MANAGER) | Submit → 201, dòng mới trong bảng, status ACTIVE, `mustChangePassword=true`, mật khẩu tạm hiện thông báo "Đã gửi email STAFF_TEMP_PASSWORD" | (test sau) |
| 3.2 | SUPER_MANAGER tạo SUPER_MANAGER khác | 403 BR-QT-01 (ghi audit), toast "Không có quyền tạo tài khoản cùng hoặc cao hơn" | (test sau) |
| 3.3 | BRANCH_MANAGER tạo SUPER_MANAGER | 403 BR-QT-01 | (test sau) |
| 3.4 | BRANCH_MANAGER không gửi `branchId` | Mặc định chi nhánh của BRANCH_MANAGER (1). Response 201, dòng mới thuộc CN1 | (test sau) |
| 3.5 | SUPER_MANAGER tạo BRANCH_MANAGER + không gửi `branchId` | 400 BR-QT-03 (SUPER_MANAGER bắt buộc gửi `branchId`) | (test sau) |
| 3.6 | Email trùng (`reception@store1.vn`) | 400 BR-TK-01, lỗi gắn ô email | (test sau) |
| 3.7 | SĐT không phải 10 số | 400 VALIDATION_FAILED, lỗi gắn ô SĐT | (test sau) |
| 3.8 | Sau lỗi mạng → bấm lại "Thêm" | 409 CONCURRENCY_CONFLICT (đã tạo từ lần trước). UI báo và cho bấm "Tìm theo email" → mở `/admin/users?q=…` | (test sau) |
| 3.9 | ADMIN bấm "Thêm người dùng" (BR-QT-01: ADMIN chỉ tạo SUPER_MANAGER) | Ô Vai trò chỉ có "Quản lý chuỗi", không có ô Chi nhánh; nhập họ tên, email, SĐT, "Tạo tài khoản" → 201, dòng mới trong bảng. Mock: ADMIN gửi role khác → 403 + audit `STAFF_CREATE_DENIED` | `AdminUsersPage.test`, `identity-admin.mock.test` |
| 3.10 | SUPER_MANAGER mở form tạo | Ô Vai trò gồm Quản lý chi nhánh, Lễ tân, Bác sĩ thú y, Nhân viên chăm sóc (không có Quản lý chuỗi); ô Chi nhánh bắt buộc (`required`). Mock: gửi `SUPER_MANAGER` → 403 | `AdminUsersPage.test`, `identity-admin.mock.test` |

## 4. `/admin/users` — Đổi chức vụ (UC08, BR-QT-04, 05, ADR-0031)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 4.1 | SUPER_MANAGER chọn `reception@store1.vn` → "Đổi chức vụ" → BRANCH_MANAGER | Confirm "Đổi chức vụ?" → 200, dòng cập nhật role, branchId giữ nguyên (1). `online=false` nếu đang đăng nhập | (test sau) |
| 4.2 | Đổi VET (`doctor@store1.vn`) sang RECEPTIONIST | 200, **specialty và bio bị xóa** (BR-QT-05). Nếu bác sĩ đang đăng nhập, request kế tiếp dùng role mới | (test sau) |
| 4.3 | BRANCH_MANAGER mở menu "Đổi chức vụ" | Menu ẩn (chỉ SUPER_MANAGER, BR-QT-01) | `AdminUsersPage.test` |
| 4.4 | Đổi chính mình | 403 BR-QT-01 (ghi audit) | (test sau) |
| 4.5 | BRANCH_MANAGER duy nhất của CN1 (`manager@store1.vn`) đổi sang RECEPTIONIST | 400 BR-QT-04 "Quản lý ACTIVE cuối cùng của chi nhánh" | (test sau) |
| 4.6 | Đổi sang cùng chức vụ hiện tại | 200, không đổi gì, toast "Không có thay đổi" | (test sau) |

## 5. `/admin/users` — Điều chuyển (UC08, BR-QT-03, 04, 06, 08, ADR-0031/0032)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 5.1 | SUPER_MANAGER chọn `reception@store1.vn` → "Điều chuyển" → CN2 | 200, branchId đổi từ 1 → 2. Nếu nhân viên đang đăng nhập, request kế tiếp dùng CN mới | (test sau) |
| 5.2 | BRANCH_MANAGER mở menu "Điều chuyển" | Ẩn (chỉ SUPER_MANAGER) | `AdminUsersPage.test` |
| 5.3 | Điều chuyển đến cùng chi nhánh | 200, không đổi gì | (test sau) |
| 5.4 | Điều chuyển BRANCH_MANAGER cuối của CN1 | 400 BR-QT-04 | (test sau) |
| 5.5 | Nhân viên CN1 còn Visit đang mở (sau T26) | 400 BR-QT-08 "Còn Visit được gán chưa xong / ca thu ngân OPEN: <danh sách>" | (test sau — D016) |
| 5.6 | Mở hộp "Điều chuyển" | Ô "Chi nhánh mới" bắt buộc (`required`), không gửi `branchId = 0` | `AdminUsersPage.test` |
| 5.6 | `branchId` chi nhánh không tồn tại | 400 BR-QT-03 | (test sau) |

## 6. `/admin/users` — Vô hiệu hóa / Kích hoạt lại (UC08, BR-QT-04, 07, 08, 09, 10, 12, BR-TK-11, ADR-0033)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 6.1 | BRANCH_MANAGER chọn `reception@store1.vn` → "Vô hiệu hóa" | Confirm "Vô hiệu hóa tài khoản? Hủy mọi phiên" → 200, status → `Vô hiệu hóa (DISABLED)`, badge đỏ. Nếu nhân viên đang dùng app, refresh → 401, về trang đăng nhập | (test sau) |
| 6.2 | Vô hiệu hóa chính mình | 403 BR-QT-07, nút ẩn (UI chặn trước) | `AdminUsersPage.test` |
| 6.3 | Vô hiệu hóa BRANCH_MANAGER cuối của CN1 (`manager@store1.vn`) | 400 BR-QT-04 | (test sau) |
| 6.4 | Bấm lại "Vô hiệu hóa" trên user đã DISABLED | 409 INVALID_STATE_TRANSITION; UI gợi ý đọc lại | (test sau) |
| 6.5 | BRANCH_MANAGER bấm "Vô hiệu hóa" Lan Chi → xác nhận trong hộp thoại | API được gọi (trước 23/10 hộp thoại gọi hook sai chỗ nên không gửi request): dòng đổi nút sang "Kích hoạt lại" | `AdminUsersPage.test` |
| 6.5 | Sau lỗi mạng, bấm lại | 409 → UI báo "Đã xử lý, tải lại" | (test sau) |
| 6.6 | Kích hoạt lại (`reception@store1.vn`) | 200, status ACTIVE, `mustChangePassword=true` (sinh MK tạm mới), "Đã gửi STAFF_TEMP_PASSWORD" | (test sau) |
| 6.7 | Kích hoạt lại + chọn chi nhánh khác | 200, branchId đổi, mật khẩu tạm mới gửi email | (test sau) |
| 6.8 | Kích hoạt lại tài khoản đang bị khóa (`isLocked=true`) | 400 BR-QT-10 "Đang bị khóa, cần mở khóa trước" | (test sau) |

## 7. `/admin/users` — Gửi lại mật khẩu ban đầu (UC08, BR-QT-01, 02, ADR-0030)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 7.1 | Chọn user `mustChangePassword=true` → "Gửi lại MK ban đầu" | 204, toast "Đã gửi mật khẩu tạm mới", phiên bị hủy | (test sau) |
| 7.1a | Bấm "Gửi lại MK cho Lễ tân mới" → "Gửi lại" trong hộp thoại | API được gọi, hộp thoại đóng (lỗi thì hộp thoại ở lại) | `AdminUsersPage.test` |
| 7.2 | Gửi lại cho user đã đổi MK lần đầu | 409 "Nhân viên đã đổi mật khẩu lần đầu" | (test sau) |
| 7.3 | Gửi lại cho tài khoản DISABLED | 409 "Tài khoản đang DISABLED" | (test sau) |
| 7.4 | BRANCH_MANAGER bấm "Gửi lại MK" cho SUPER_MANAGER | 403 BR-QT-01 (ghi audit) | (test sau) |

## 8. `/admin/users` — Sửa email nhân viên (UC08, BR-TK-01, 04, 05, 06, 07, 16, ADR-0034)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 8.1 | Chọn `reception@store1.vn` → "Sửa email" → nhập email mới hợp lệ | Modal bước 1: 200, hiện maskedEmail mới, "Đã gửi OTP tới email mới", đếm ngược gửi lại 60s (BR-TK-07) | (test sau) |
| 8.2 | Nhập OTP sai 5 lần | Mã bị hủy (BR-TK-06), báo "Nhập sai quá 5 lần", nút "Gửi lại mã" | (test sau) |
| 8.3 | Nhập OTP đúng (`123456` với mock) | 200, email đổi, `STAFF_EMAIL_CHANGED` audit, "Đã gửi EMAIL_CHANGED_NOTICE tới email cũ và mới". Trạng thái, khóa, MK giữ nguyên; phiên KHÔNG bị hủy | (test sau) |
| 8.4 | Email mới trùng email user khác | 400 BR-TK-01 (kể cả tài khoản PENDING) | (test sau) |
| 8.5 | Email mới trùng email hiện tại | 400 BR-TK-01 | (test sau) |
| 8.6 | Nhân viên đang `mustChangePassword=true` → sau khi đổi email thành công | UI nhắc "Gửi lại MK ban đầu để MK tạm đi tới email mới" | (test sau) |
| 8.7 | Sửa email chính mình | 403 BR-QT-01 (ghi audit) | (test sau) |

## 9. `/admin/users` — Tìm & Khóa/Mở khóa tài khoản (UC09, BR-QT-11, 12, BR-TK-11, ADR-0035)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 9.1 | ADMIN mở `/admin/accounts` (tab con hoặc sub-page) | Bảng `AccountSummary`: id, email, role, status, `isLocked`, `mustChangePassword`. Lọc theo `q` (tên/email), `role`, `isLocked` | (test sau) |
| 9.2 | Tìm `manager@store1.vn` | Hiện 1 dòng, role BRANCH_MANAGER, status ACTIVE, isLocked false | (test sau) |
| 9.3 | Click "Khóa" → modal bắt buộc nhập **lý do** (BR-QT-11) | Submit → 200, isLocked=true, status giữ nguyên (BR-QT-12). Nếu đang đăng nhập, refresh → 401, về login | (test sau) |
| 9.4 | Khóa chính mình | 400 BR-QT-11, nút ẩn | (test sau) |
| 9.5 | Khóa tài khoản đã khóa | 409 INVALID_STATE_TRANSITION | (test sau) |
| 9.6 | Khóa nhân viên A05–A08 còn Visit/ca OPEN (sau T26/T30) | 200 + `pendingWork` liệt kê. UI hiện "Cần reassign: <danh sách>" + link `/admin/queue` hoặc modal "Phân lại" | (test sau — D016) |
| 9.7 | Khóa BRANCH_MANAGER cuối của CN1 | UI cảnh báo: "Quản lý cuối cùng, sẽ thông báo SUPER_MANAGER qua app (BR-QT-11 LAST_BRANCH_MANAGER_LOCKED_APP)" | (test sau) |
| 9.8 | Mở khóa (click icon "Mở khóa") → nhập lý do | 200, isLocked=false, status vẫn giữ giá trị trước khi khóa, ghi audit `ACCOUNT_UNLOCKED` | (test sau) |
| 9.9 | Mở khóa tài khoản không bị khóa | 409 INVALID_STATE_TRANSITION | (test sau) |
| 9.10 | BRANCH_MANAGER mở `/admin/accounts` | 403 (chỉ ADMIN, BR-QT-11). SUPER_MANAGER cũng 403. UI ẩn route này cho non-ADMIN | `AdminUsersPage.test` |

## 10. `/admin/system-configs` — Tham số [CFG] (UC10, BR-QT-13, 15)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 10.1 | ADMIN mở `/admin/system-configs` | Bảng: `key`, `value`, `valueType` (INT/DECIMAL/BOOL/TIME), `minValue`/`maxValue`, `unit`, `description`. Cột "Sửa" | `AdminSystemConfigsPage.test` (mới) |
| 10.2 | Sửa `vet.bio_max_length` (INT, min=100, max=2000, mặc định 500) → nhập 600 | 200, dòng cập nhật. Lần sau mở `/admin/profile` (VET) vẫn 500, sau khi BE áp dụng cho giao dịch mới (BR-QT-13) | (test sau) |
| 10.3 | Sửa cùng key → nhập 50 (ngoài min) | 400 BR-QT-13, message kèm khoảng hợp lệ. Lỗi gắn ô input | (test sau) |
| 10.4 | Sửa key không tồn tại | 404 | (test sau) |
| 10.5 | SUPER_MANAGER mở `/admin/system-configs` | 403 (chỉ ADMIN). Route ẩn trong `staff-menu.ts` | `App.routes.test` |
| 10.6 | Ghi audit | Mở `/admin/audit`, lọc `action=UPDATE`, `entityType=SystemConfig` → thấy bản ghi vừa rồi | (test sau) |

## 11. `/admin/notification-templates` — Mẫu thông báo (UC10, BR-QT-14, 15)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 11.1 | ADMIN mở `/admin/notification-templates` | Bảng: `code`, `channel` (EMAIL/IN_APP), `subject`, `body`, `allowedVars`, `requiredVars`, `isDefault` | (test sau) |
| 11.2 | Sửa mẫu `STAFF_TEMP_PASSWORD` → thay `body` nhưng giữ biến `{fullName}`, `{tempPassword}` | 200, "Đã lưu mẫu", `isDefault=false` | (test sau) |
| 11.3 | Sửa → nhập body không có `{tempPassword}` (required) | 400 BR-QT-14 "Thiếu biến bắt buộc: tempPassword" | (test sau) |
| 11.4 | Sửa → nhập body có biến lạ `{fooBar}` | 400 BR-QT-14 "Biến không tồn tại: fooBar" | (test sau) |
| 11.5 | Click "Khôi phục mặc định" → confirm | 200, `isDefault=true`, `body` về `default_body` | (test sau) |
| 11.6 | SUPER_MANAGER mở | 403 (chỉ ADMIN) | `App.routes.test` |
| 11.7 | Sửa mẫu IN_APP (vd `BRANCH_MANAGER_LOCKED_APP`) | Có ô "Tiêu đề *" (bắt buộc, tối đa 255) cho mọi kênh; sửa tiêu đề + Lưu → 200 và bảng hiện tiêu đề mới (identity-v1 #34: `subject` bắt buộc mọi kênh) | `AdminNotificationTemplatesPage.test`, `identity-admin.mock.test` |
| 11.8 | Xóa tiêu đề hoặc chỉ nhập khoảng trắng → Lưu | FE báo "Tiêu đề không được để trống", không gọi API. Mock: thiếu / null / rỗng / > 255 → 400 `VALIDATION_FAILED` | `AdminNotificationTemplatesPage.test`, `identity-admin.mock.test` |

## 12. `/admin/audit` — Nhật ký kiểm toán (UC11, BR-QT-15, 16)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 12.1 | ADMIN mở `/admin/audit` | Bảng: `createdAt`, `actorEmail`/`actorAccountId`, `action`, `entityType`/`entityId`, `ipAddress`. 4 KPI: tổng / số actor / số CREATE-UPDATE / số DELETE | `AdminAuditPage.test` |
| 12.2 | Lọc `action=LOGIN_LOCKED` (ST01) hoặc `action=LOGIN`, `from=hôm nay` | Thấy bản ghi đăng nhập gần nhất (cả thành công lẫn thất bại theo ADR-0019) | (test sau) |
| 12.3 | Lọc `actorAccountId=4` (reception) | Thấy các bản ghi do lễ tân CN1 tạo | (test sau) |
| 12.4 | Lọc `entityType=Staff`, `entityId=4` | Thấy các bản ghi liên quan tới reception (change-role, transfer, disable…) | (test sau) |
| 12.5 | Bấm icon mắt một dòng | Modal: `beforeData`/`afterData` (object), `reason` (khóa/mở khóa, BR-QT-11), `ipAddress`, `createdAt` | (test sau) |
| 12.6 | Phân trang `size=20` | Có nút "Trang sau" khi > 20 dòng | (test sau) |
| 12.7 | SUPER_MANAGER mở | 403 (chỉ ADMIN). UI ẩn trong `staff-menu.ts` (hiện tại SUPER_MANAGER có thấy `/admin/audit`; cần đổi `roles: ['ADMIN']` cho đúng hợp đồng) | `App.routes.test` |
| 12.8 | BRANCH_MANAGER mở | 403 | `App.routes.test` |
| 12.9 | Chọn "Từ" / "Đến" (datetime-local) | FE gửi `from` / `to` kèm offset giờ nghiệp vụ `+07:00` (vd `2026-10-23T08:00:00+07:00`); mock trả 400 `MALFORMED_REQUEST` nếu thiếu offset | `AdminAuditPage.test`, `identity-admin.mock.test` |

## 13. Phân quyền truy cập (tổng hợp)

| Đường dẫn | ADMIN | SUPER_MANAGER | BRANCH_MANAGER | RECEPTIONIST / VET / CARETAKER |
|---|---|---|---|---|
| `/admin/users` | ✅ Mọi NV | ✅ A05–A08 | ✅ A06–A08 chi nhánh mình | ❌ |
| `/admin/accounts` | ✅ | ❌ | ❌ | ❌ |
| `/admin/system-configs` | ✅ | ❌ | ❌ | ❌ |
| `/admin/notification-templates` | ✅ | ❌ | ❌ | ❌ |
| `/admin/audit` | ✅ | ❌ (đổi `roles` trong menu) | ❌ | ❌ |

## 14. Lỗi thường gặp & trạng thái BE hiện tại

- Điều chuyển / vô hiệu hóa / khóa A05–A08 chạy bình thường từ 21/10 (BE có `VisitQueryApi` T26, `CashierShiftQueryApi` T30); mock đã gỡ lỗi 500 giả lập. Chưa mô phỏng BR-QT-08 (còn Visit / ca OPEN) vì mock không có dữ liệu Visit.
- Khóa tài khoản khách chạy từ T31 (`CustomerQueryApi`, `AppointmentQueryApi`, `BoardingQueryApi`, `OrderQueryApi` đều thật).
- `mustChangePassword` cho nhân viên mới: FE phải kiểm `BR-TK-17` chặn mọi API trừ `/me`, `/me/password`, `/auth/logout` (theo `MustChangePasswordInterceptor` của BE). Hiện `axios.ts` đã xử lý 400 BR-TK-17, cần bổ sung test cho case nhân viên mới đăng nhập lần đầu.
