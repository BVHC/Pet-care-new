# Test case FE — T34 (trung tâm thông báo & cài đặt nhận thông báo; bán lẻ tại quầy)

> FE viết 2026-10-23: phần A thông báo, phần B bán lẻ tại quầy.
> Cột cuối ghi test tự động tương ứng; "(test sau)" là bước chỉ kiểm tay.

## A. Thông báo (UC88)

> Hợp đồng: `docs/api/care-v1.md` #5–10 (+ `openapi/care-v1.yaml`), ADR-0039. 01 UC88: A02–A08 xem thông báo, chỉ A02 cài đặt.
> BE đã có #5–8 (`MyNotificationController`, `isAuthenticated()` — mọi role kể cả ADMIN); #9–10 chưa làm (nợ D025) nên mock là nguồn.

### A.1. Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`, mật khẩu chung **`Petcare123`**. Khách đăng nhập ở `/auth/login`, nhân viên ở `/admin/login`.

| Tài khoản | Role | Dữ liệu mẫu (thời gian tính từ lúc mock tạo dữ liệu) |
|---|---|---|
| `khach@petcare.vn` | CUSTOMER | 26 thông báo trong ~2 tháng, **6 chưa đọc** (5 mới nhất + 1 nhắc lịch hẹn của Mochi ~59 ngày trước, nằm ở trang 2). Mới nhất: "Nhắc lịch hẹn của Bim" (20 phút trước, → `/appointments`). Hai thông báo "Phòng khám đã hủy lịch hẹn…" trùng giờ (2 ngày trước). Có nhắc tái chủng / tái khám (→ `/booking`), hủy đặt chỗ lưu trú (không liên kết) |
| `khachmoi@petcare.vn` | CUSTOMER | Không có thông báo (trạng thái trống) |
| `manager@store1.vn` | BRANCH_MANAGER | 4: Order chờ thu tiền quá hạn, cảnh báo tồn kho (→ `/admin/warehouse/stock`) chưa đọc; ca thu ngân tự chốt, việc dở của lễ tân bị khóa đã đọc |
| `chuoi@petcare.vn` | SUPER_MANAGER | 1 chưa đọc: chi nhánh không còn quản lý đang hoạt động |
| `reception@store1.vn` | RECEPTIONIST | 3: nhật ký chăm sóc bất thường, chuồng lưu trú sắp kín (→ `/staff/reception`) chưa đọc; việc dở của khách bị khóa đã đọc |
| `doctor@store1.vn`, `caretaker@store1.vn` | VET, CARETAKER | Không có (06 §8 Q4 chưa có mẫu trong app nào gửi A07, A08) |
| `admin@petcare.vn` | ADMIN | Không có; có chuông và trang `/admin/notifications` (trống) — xem A.7 |

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-care`, rồi F5.

**Chạy với BE thật:** #5–8 chạy được (`VITE_API_MOCK=false`); trên app thật hiện chỉ có thông báo khóa tài khoản, hủy lịch / nhắc lịch, thu ngân, lưu trú khi các luồng đó chạy. Trang Cài đặt sẽ lỗi tải (BE chưa có #9–10) và hiện "Không tải được cài đặt".

### A.2. Trang

| Trang | Path | Ai mở được |
|---|---|---|
| Chuông trên header công khai | mọi trang có header | Đã đăng nhập: khách; nhân viên A04–A08 (đang xem trang công khai) |
| Thông báo của khách | `/notifications` | CUSTOMER |
| Cài đặt nhận thông báo | `/settings` | CUSTOMER |
| Chuông trên khung nhân viên | thanh bên (cạnh logo, cả khi thu gọn) + thanh trên ở điện thoại | SUPER_MANAGER, BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER |
| Thông báo của nhân viên | `/admin/notifications` (ẩn khỏi menu, mở từ chuông) | như trên |

### A.3. Chuông thông báo

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| A3.1 | Chưa đăng nhập, mở `/` | Không có chuông | `NotificationBell.test` |
| A3.2 | Khách đăng nhập | Chuông có huy hiệu **6**; số tự cập nhật mỗi 60 giây | `NotificationBell.test` |
| A3.3 | Bấm chuông | Khung "Thông báo" với 5 thông báo mới nhất, chấm xanh ở thông báo chưa đọc, giờ tương đối ("20 phút trước"; quá 7 ngày hiện ngày giờ) | `NotificationBell.test` |
| A3.4 | Bấm "Nhắc lịch hẹn của Bim" | Mở `/appointments`; huy hiệu còn 5 | `NotificationBell.test` |
| A3.5 | Bấm thông báo không có liên kết | Đánh dấu đã đọc, mở `/notifications` để đọc đủ nội dung | (test sau) |
| A3.6 | "Đánh dấu tất cả đã đọc" | Huy hiệu mất; "Không có thông báo chưa đọc" | `NotificationBell.test` |
| A3.7 | "Xem tất cả" | Khách → `/notifications` | `NotificationBell.test` |
| A3.8 | Lễ tân đăng nhập rồi mở trang chủ, bấm chuông → "Xem tất cả" | Mở `/admin/notifications` | `NotificationBell.test` |
| A3.9 | Nhấn Esc / bấm ra ngoài / cuộn trang | Khung đóng | (test sau) |

### A.4. Trang Thông báo của khách (`/notifications`)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| A4.1 | Mở trang | 20 thông báo mới nhất trước; hai thông báo hủy lịch trùng giờ: của Bim (id lớn hơn) đứng trước Tôm; nội dung nhiều dòng hiện đủ | `NotificationsPage.test`, `care.mock.test` |
| A4.2 | "Xem thêm" | Thêm 6 thông báo; hết trang thì nút biến mất | `NotificationsPage.test` |
| A4.3 | Tab "Chưa đọc (6)" | Chỉ 6 thông báo chưa đọc, gồm thông báo cũ ở trang 2 | `NotificationsPage.test`, `care.mock.test` |
| A4.4 | Nút ✓ trên một thông báo chưa đọc | Thông báo thành đã đọc, tab còn "Chưa đọc (5)", huy hiệu chuông giảm | `NotificationsPage.test` |
| A4.5 | Bấm vào thông báo có liên kết | Đánh dấu đã đọc và mở liên kết | `NotificationsPage.test` |
| A4.6 | "Đánh dấu tất cả đã đọc" | Nút bị khóa; tab "Chưa đọc" → "Bạn đã đọc hết thông báo" | `NotificationsPage.test` |
| A4.7 | `khachmoi@petcare.vn` | "Chưa có thông báo nào" | `NotificationsPage.test` |
| A4.8 | Tắt mạng / BE lỗi | "Không tải được thông báo" + "Thử lại" | (test sau) |

### A.5. Cài đặt nhận thông báo (`/settings`)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| A5.1 | Mở trang | Bảng 3 loại nhắc (lịch hẹn, tái chủng, tái khám) × 2 kênh (Email, Trong ứng dụng), mặc định bật hết; ghi chú "Email bảo mật luôn được gửi" (OTP, đổi mật khẩu, đổi email) | `SettingsPage.test`, `care.mock.test` |
| A5.2 | Tắt "Nhắc tái chủng qua email" | "Có thay đổi chưa lưu"; "Lưu thay đổi" mở khóa | `SettingsPage.test` |
| A5.3 | "Hủy thay đổi" | Trở lại như đã lưu | `SettingsPage.test` |
| A5.4 | Tắt cả hai kênh của một loại | Gợi ý "Đã tắt cả hai kênh — bạn sẽ không nhận nhắc này." | `SettingsPage.test` |
| A5.5 | "Lưu thay đổi", F5 | Toast "Đã lưu cài đặt nhận thông báo"; mở lại vẫn giữ | `SettingsPage.test` |
| A5.6 | Gửi thiếu loại nhắc / giá trị không phải boolean (gọi API) | 400 `VALIDATION_FAILED`, lỗi theo trường (`vaccineReminder.inApp: …`) | `care.mock.test` |
| A5.7 | Các mục cũ: thông báo đẩy / SMS, chế độ tối, ngôn ngữ, đơn vị đo, múi giờ, định dạng ngày, xóa dữ liệu, phiên bản | Không còn (không có use case ở v16) | `SettingsPage.test` |

### A.6. Khu nhân viên (`/admin/notifications`)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| A6.1 | Lễ tân đăng nhập | Chuông cạnh logo ở thanh bên có số **2**; thu gọn thanh bên vẫn còn chuông | `StaffSidebar.test`, `StaffNotificationsPage.test` |
| A6.2 | Bấm chuông | Trang "Thông báo": 3 thông báo, mới nhất trước; tab "Chưa đọc (2)" | `StaffNotificationsPage.test` |
| A6.3 | ✓ trên "Chuồng lưu trú sắp kín" rồi "Đánh dấu tất cả đã đọc" | "Chưa đọc (1)" rồi hết; tab Chưa đọc trống | `StaffNotificationsPage.test` |
| A6.4 | Bấm "Nhật ký chăm sóc bất thường: Lu" | Đánh dấu đã đọc, mở bàn Lễ tân | (test sau) |
| A6.5 | Điện thoại (< 1024 px) | Chuông ở góc phải thanh trên, tên trang "Thông báo" | (test sau) |
| A6.6 | Bác sĩ | Chuông không có số; trang trống "Chưa có thông báo nào" | `StaffNotificationsPage.test` |
| A6.7 | Quản lý chi nhánh: bấm "Cảnh báo tồn kho…" | Mở tab Tồn kho của kho hàng | (test sau) |

### A.7. Phân quyền (tổng hợp)

| Thao tác | CUSTOMER | A04–A08 | ADMIN |
|---|---|---|---|
| #5–8 thông báo của tôi | ✓ | ✓ | ✓ (UC88 A02–A08 gồm A03; BE `isAuthenticated()`); có chuông + trang `/admin/notifications` |
| #9–10 cài đặt | ✓ | 403 | 403 |
| Thông báo của người khác (#7) | 404 "Không tìm thấy thông báo #id" (cùng câu với id không tồn tại) | | |

Kiểm bằng `care.mock.test`, `StaffNotificationsPage.test` (ADMIN mở được trang), `NotificationBell.test` (ADMIN có chuông), `StaffSidebar.test`.

### A.8. Giả định (mock và FE)

| # | Giả định | Nguồn |
|---|---|---|
| N1 | Cài đặt mặc định bật cả 3 loại × 2 kênh (cột `accounts.notification_settings` DEFAULT `'{}'`, docs không nói loại nào tắt sẵn) | care-v1 A1, Q1 (chờ PO) |
| N2 | PUT cài đặt kiểm dữ liệu trước quyền (sai dữ liệu → 400 kể cả với nhân viên); khóa lạ bị bỏ qua | 00-method §3.5, Jackson mặc định của BE |
| N3 | `notifications.type` = mã mẫu bỏ `_APP`; tiêu đề / nội dung mock chép từ mẫu IN_APP đã seed (V12, V17–V20). Mẫu chưa seed (`VACCINE_REMINDER`, `FOLLOW_UP_REMINDER`, `CARE_LOG_ABNORMAL`, `BOARDING_CAPACITY_WARNING`, `STOCK_ALERT_DIGEST`) dùng câu tạm của FE | 05-erd mục 13, 06 §8 Q4 |
| N4 | `linkUrl` như BE gửi: hủy / nhắc lịch hẹn `/appointments`; thu ngân, Order quá hạn, khóa tài khoản: không liên kết. Nhắc tái chủng / tái khám tạm `/booking` (BR-TB-01, 06 muốn điền sẵn thú + dịch vụ — FE chưa có tham số). Mẫu chưa seed: liên kết tạm tới trang FE có thật | BE `ClinicCancellationNotices.LINK_URL`, ST13 |
| N5 | Hủy đặt chỗ lưu trú: BE gửi `/boarding-bookings` nhưng FE chưa có trang này → mock để `null` | `BoardingClinicCancellationNotices.LINK_URL` |
| N6 | FE chỉ đi theo `linkUrl` bắt đầu bằng `/`; thông báo không liên kết bấm từ chuông thì mở trang Thông báo | — |
| N7 | Chuông chỉ gọi API khi đã đăng nhập, không còn cờ đổi mật khẩu (BR-TK-17 chặn cả 4 endpoint — ADR-0039 §2) và role có trang Thông báo; hỏi số chưa đọc mỗi 60 giây, không hỏi khi tab ẩn. Với nhân viên mỗi lần hỏi làm mới `last_seen_at` (nợ D024) | ADR-0039 Hệ quả |
| N8 | Danh sách phân trang theo vị trí (BE): thông báo mới chen vào giữa hai lần "Xem thêm" có thể đẩy phần tử cũ sang trang sau → FE bỏ trùng theo `notificationId` | ADR-0039 §6 |

### A.9. Chưa làm, giới hạn

- **ADMIN:** 01 UC88 (A02–A08 gồm A03), ADR-0039 §1 và BE (`isAuthenticated()`) cho ADMIN xem thông báo, nhưng mục `/admin/notifications` trong `staff-menu.ts` ghi "A04–A08, không có ADMIN". Chuông / trang đi theo `rolesForPath`, nên chỉ cần thêm `'ADMIN'` vào `roles` của mục đó là ADMIN có chuông (sửa luôn 2 test ADMIN trong `NotificationBell.test`, `StaffNotificationsPage.test`, `StaffSidebar.test`).
- Cài đặt (#9–10) chưa có ở BE (D025); nội dung còn chờ PO (care-v1 Q1).
- Mock không tự sinh thông báo khi các mock khác hủy lịch / khóa tài khoản…; dữ liệu mẫu là cố định.
- `shared/stores/notifications.store.ts` (store local cũ của chuông) không còn ai dùng — xóa ở lượt dọn T57.
- Menu tài khoản trên header công khai vẫn có mục "Thông báo" → `/notifications`; nhân viên bấm vào gặp 403 (header ngoài phạm vi T34).

---

> Phần B: hợp đồng `docs/api/sales-v1.md` #1–8, #10 (UC66, UC67). BR-BH-01…06, BR-SP-01, BR-KB-04, BR-QT-15; 03 #5 Order#2, #5, #7, #10; docs/adr/0044, 0051.

## B. Bán lẻ tại quầy (UC66, UC67)

### B.1 Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`, đăng nhập ở `/admin/login`, mật khẩu chung **`Petcare123`**.

| Tài khoản | Role | Thấy gì |
|---|---|---|
| `reception@store1.vn` | RECEPTIONIST chi nhánh 1 | Quầy lễ tân `/staff/reception` → "Bán hàng"; menu **Order tại quầy** (`/admin/pos`): tạo / làm tiếp / hủy Order bán lẻ chưa chốt |
| `manager@store1.vn` | BRANCH_MANAGER chi nhánh 1 | `/admin/pos` chỉ xem + "Hủy – khách không thanh toán" trên Order chờ thu |
| `manager2@store2.vn` | BRANCH_MANAGER chi nhánh 2 | Chỉ thấy Order của chi nhánh 2 |
| `doctor@store1.vn` | VET | `/admin/pos` → 403 (menu chỉ cho A05, A06) |

**Dữ liệu mẫu của mock** (`sales.mock.ts`, thời điểm tính từ lúc nạp; chi nhánh 1):

| Order | Nguồn | Trạng thái | Khách | Nội dung |
|---|---|---|---|---|
| DH-…-00001 | Bán lẻ | Đang mở | Lê Hoàng Long (#3) | Royal Canin × 1 |
| DH-…-00002 | Lượt tiếp nhận | Chờ thu (hôm nay) | Nguyễn Thu Trang | Khám tổng quát (tự sinh), Amoxicillin × 10 (thuốc), Pate × 2 |
| DH-…-00003 | Lượt tiếp nhận | Chờ thu từ 2 ngày trước (**quá hạn**) | Lê Thị Hoa | Tiêm phòng (tự sinh), Vaccine Dại Rabisin |
| DH-…-00004 | Bán lẻ | Chờ thu (hôm nay) | Lê Hoàng Long (#2) | Royal Canin × 1 |
| DH-…-00005 | Bán lẻ | Đã thanh toán | Nguyễn Thu Trang | Pate × 6 |
| DH-…-00006 | Bán lẻ | Đã hủy (chưa chốt) | Lê Hoàng Long (#3) | Vitamin × 1 |
| DH-…-00007 | Lượt tiếp nhận | Đã hủy (khách không thanh toán) | Lê Thị Hoa | Khám tổng quát |

Chi nhánh 2: DH-…-00008 bán lẻ chờ thu. Tồn khả dụng chi nhánh 1 (`inventory.mock.ts`): Royal Canin 12, **Pate 3**, Vitamin 0.

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-sales` (và `petcare-mock-inventory`, `petcare-mock-customer` nếu đã sửa), rồi F5.

**Chạy với BE thật:** `VITE_API_MOCK=false` — BE đã có sales-v1 #1–8, #10 (T19, T29).

### B.2 Trang

| Trang | Path | Ai mở được |
|---|---|---|
| Quầy lễ tân — khu "Bán hàng" | `/staff/reception` | RECEPTIONIST |
| Order tại quầy | `/admin/pos` | RECEPTIONIST, BRANCH_MANAGER |

### B.3 Lễ tân bán lẻ (UC66, Order#2, #5, #7)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| B3.1 | Quầy lễ tân → Bán hàng | Ô "SĐT, họ tên hoặc email của khách", **không** có ô tìm sản phẩm khi chưa có Order (Order bán lẻ bắt buộc hồ sơ khách — A1); F1 / Ctrl+K focus ô tìm của bước hiện tại | `ReceptionWorkspacePage.test` |
| B3.2 | Gõ "Hoa" → chọn Lê Thị Hoa → "Tạo Order bán lẻ" | Order mới `DH-yyMM-nnnnn`, trạng thái Đang mở, tổng 0 đ, "Chốt Order" bị khóa | `RetailOrderPanel.test`, `sales.mock.test` |
| B3.3 | Gõ SĐT chưa đủ 10 số | "Nhập đủ 10 số điện thoại để tra cứu." | (test sau) |
| B3.4 | Khách chưa có hồ sơ → "Tạo hồ sơ mới" → nhập họ tên, SĐT → "Mở hồ sơ vừa tạo" | Hồ sơ mới được chọn cho Order | (test sau) |
| B3.5 | Danh sách sản phẩm | Không có Amoxicillin (thuốc kê đơn, BR-SP-01) và vaccine (BR-KB-04); Vitamin (thuốc không kê đơn) có | `RetailOrderPanel.test` |
| B3.6 | Số lượng 5 → bấm Pate Whiskas | Dòng 5 × 18.000 = 90.000 đ (giá chụp lúc thêm, BR-BH-03); cảnh báo **"Tồn khả dụng còn 3"** — vẫn thêm được (BR-BH-04) | `RetailOrderPanel.test`, `sales.mock.test` |
| B3.7 | Bấm "Bớt 1" dòng Pate | 4 × 18.000 = 72.000 đ; cảnh báo vẫn hiện (4 > 3). Không có nút tăng: muốn thêm thì thêm dòng mới để được kiểm tồn (ADR-0044 §4.3) | `RetailOrderPanel.test`, `sales.mock.test` |
| B3.8 | Thêm Royal Canin hai lần | Hai dòng riêng (BE không gộp); cảnh báo tính trên tổng số lượng sản phẩm trong Order | `sales.mock.test` |
| B3.9 | Xóa dòng (nút ×) | Dòng mất, tổng tính lại | `sales.mock.test` |
| B3.10 | "Chốt Order" | Toast "Đã chốt Order … chờ thu tiền tại quầy thu ngân"; trạng thái Chờ thu tiền; hiện **"Chưa thanh toán – chưa giao hàng"** (BR-BH-06); hết ô tìm sản phẩm; "Bán đơn mới" về bước chọn khách | `RetailOrderPanel.test` |
| B3.11 | Chốt Order không có dòng (gửi thẳng API) | 400 BR-BH-01, FE báo "Order chưa có dòng nào nên chưa chốt được…" (nút đã khóa) | `sales.mock.test` |
| B3.12 | Order đang mở → "Hủy Order" → "Xác nhận hủy" | Order Đã hủy (`RETAIL_UNCLOSED`), dòng giữ làm lịch sử, kho không đổi; quay về chọn khách | `RetailOrderPanel.test`, `sales.mock.test` |
| B3.13 | Hai tab: tab A hủy Order #1, tab B bấm "Chốt Order" | 409 → "Order vừa được chốt, hủy hoặc thu tiền ở nơi khác…", Order tải lại thành Đã hủy | `RetailOrderPanel.test` |
| B3.14 | Chọn lại khách #3 (Lê Hoàng Long, email long.lh…) | Hiện "Khách đang có Order bán lẻ chưa chốt" + "Tiếp tục DH-…-00001" | (test sau) |
| B3.15 | "Để sau" trên Order đang mở; mở "Khách hàng" rồi quay lại "Bán hàng" | Order vẫn Đang mở; đổi màn hình trong quầy không mất Order đang làm | (test sau) |

### B.4 Order tại quầy `/admin/pos`

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| B4.1 | Quản lý mở trang | Danh sách 7 Order chi nhánh 1, mới nhất trước; không có "Tạo Order bán lẻ" | `AdminPOSPage.test`, `sales.mock.test` |
| B4.2 | Lọc trạng thái / nguồn / ngày tạo | Đúng bộ lọc; ngày tạo theo giờ Việt Nam | `sales.mock.test` |
| B4.3 | Tích "Chờ thu quá hạn" | Chỉ còn DH-…-00003 (chốt trước 00:00 hôm nay, [CFG] `order.pending_alert_days` = 1), cũ nhất trước | `AdminPOSPage.test`, `sales.mock.test` |
| B4.4 | Quản lý → Xem DH-…-00003 → "Hủy – khách không thanh toán" | Giải thích: thất thu, ghi nhật ký (audit), kho / bệnh án / lịch hẹn / lưu trú giữ nguyên; "Xác nhận hủy Order" khóa khi chưa nhập lý do | `AdminPOSPage.test` |
| B4.5 | Nhập lý do → Xác nhận | "Đã hủy Order … — ghi nhận thất thu"; Order Đã hủy, loại "Khách không thanh toán (thất thu)", lý do hiện ở chi tiết | `AdminPOSPage.test`, `sales.mock.test` |
| B4.6 | Lý do chỉ toàn khoảng trắng / > 300 ký tự (gửi thẳng API) | 400 BR-BH-05 / 400 VALIDATION_FAILED | `sales.mock.test` |
| B4.7 | Quản lý xem Order Đang mở / Đã thanh toán | Chỉ đọc: không có nút bán, hủy | `AdminPOSPage.test` |
| B4.8 | Lễ tân xem Order chờ thu | Không có "Hủy – khách không thanh toán" (chỉ BRANCH_MANAGER, BR-BH-05) | `AdminPOSPage.test`, `sales.mock.test` |
| B4.9 | Lễ tân → DH-…-00001 → "Tiếp tục bán" | Bảng bán lẻ mở đúng Order, có ô tìm sản phẩm và dòng Royal Canin | `AdminPOSPage.test` |
| B4.10 | Lễ tân → "Tạo Order bán lẻ" | Bảng bán lẻ ở bước chọn khách | `AdminPOSPage.test` |
| B4.11 | `manager2@store2.vn` | Chỉ thấy DH-…-00008; mở Order chi nhánh khác qua API → 403 | `sales.mock.test` |
| B4.12 | `doctor@store1.vn` mở `/admin/pos` | 403 trong khung | `App.shell.test` |

### B.5 Quyền dòng và trạng thái (sales-v1 #4–6, BR-BH-02)

| # | Tình huống | Kỳ vọng | Test tự động |
|---|---|---|---|
| B5.1 | Lễ tân xem DH-…-00002 (Visit, chờ thu) | Dịch vụ, thuốc: không sửa / xóa; hàng Pate: sửa / xóa được (`canEdit` / `canDelete`) | `sales.mock.test` |
| B5.2 | Quản lý xem cùng Order | Mọi dòng không sửa / xóa | `sales.mock.test` |
| B5.3 | Xóa dòng thuốc / sửa dòng dịch vụ khi chờ thu (API) | 400 BR-BH-02 / 403 BR-BH-02 | `sales.mock.test` |
| B5.4 | Bác sĩ thêm hàng bán lẻ (API) | 403 BR-BH-02 | `sales.mock.test` |
| B5.5 | Thêm / sửa / xóa dòng Order đã thanh toán / đã hủy (API) | 409 | `sales.mock.test` |
| B5.6 | Chốt / hủy Order không phải bán lẻ đang mở (API) | 409 (trước kiểm BR-BH-01) | `sales.mock.test` |

### B.6 Giả định (ghi cả trong code)

| ID | Giả định | Căn cứ |
|---|---|---|
| P1 | Ô tìm khách tự nhận dạng: toàn số → SĐT (chỉ gửi khi đủ 10 số, tra chính xác), có `@` → email, còn lại → họ tên. | customer-v1 UC23; như `CustomerSearchPanel` |
| P2 | Danh sách bán lẻ dùng `retailOnly=true&isActive=true` và ẩn thêm vaccine phía FE; BE vẫn chặn nếu cố gửi. | catalog-v1 A4, BR-SP-01, BR-KB-04 ("ẩn thao tác ngoài quyền") |
| P3 | Chọn số lượng trước rồi bấm sản phẩm; thêm cùng sản phẩm lần nữa là dòng mới; dòng chỉ có nút giảm và xóa. | docs/adr/0044 §4.3, §4.5 |
| P4 | Cảnh báo thiếu tồn chỉ hiện sau khi thêm (từ `stockWarning`), tính lại khi giảm / xóa dòng từ số tồn đã biết; danh sách sản phẩm không gọi thêm API tồn. | BR-BH-04 (cảnh báo khi thêm dòng) |
| P5 | Sau khi chốt, bảng bán lẻ không cho thêm hàng nữa (BE vẫn cho lễ tân thêm GOODS khi PENDING); nút sửa / xóa dòng vẫn theo `canEdit` / `canDelete` (giảm / xóa dòng hàng khi thiếu tồn lúc thu — T35). | 03 #5, BR-BH-02, BR-BH-04 |
| P6 | "Để sau" rời Order đang mở mà không hủy; làm tiếp bằng danh sách Order bán lẻ đang mở của khách (`GET /orders?customerId&status=OPEN&source=RETAIL`) hoặc "Tiếp tục bán" ở `/admin/pos`. Order bỏ dở không tự hủy. | docs/adr/0051 Hệ quả (Order OPEN rỗng không có job dọn) |
| P7 | Không có idempotency khi tạo Order / thêm dòng: FE khóa nút khi đang gửi. | docs/adr/0044, 0051 §1.5 |
| P8 | "Chờ thu quá hạn" chỉ là bộ lọc `pendingOverdue`; FE không tô quá hạn trên danh sách thường vì ngưỡng N là [CFG] BE tính. Mock dùng N = 1. | docs/adr/0051 §4.4 |
| P9 | "Hủy – khách không thanh toán" hiện cho BRANCH_MANAGER trên mọi Order chờ thu (VISIT / RETAIL / BOARDING); Order lưu trú khi thú còn trong chuồng do BE trả 400 BR-BH-05, FE hiện message. | 03 #5 Order#10, docs/adr/0051 §3 |
| P10 | Mock không ghi audit (BE ghi `ORDER_CANCELLED_UNPAID`), không kiểm dịch vụ bật theo chi nhánh, chưa có Order lưu trú. | mock |

### B.7 Còn lại cho T35

- Phiếu thu ở quầy (`CheckoutPanel`, `pos.store`) vẫn chạy trên mock cũ `clinic-db`; Order bán lẻ đã chốt **chưa** hiện ở phiếu thu cho tới khi T35 nối `payable-orders`, `payments/preview`, `payments`, ca thu ngân. Khu "Bán hàng" không còn thả hàng thẳng vào phiếu thu.
