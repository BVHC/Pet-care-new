# T48 (FE): Lưu trú: chuồng, đặt chỗ, nhận / trả thú, nhật ký chăm sóc; khách xem lưu trú & nhật ký (UC54, UC57–60)

> Phạm vi: **A. Mock lưu trú** (boarding-v1 #1–22 + sales-v1 #9) · **B. Chuồng** (UC54) · **C. Bảng lưu trú của chi nhánh + đặt tại quầy** (UC58 lễ tân) · **D. Nhận / trả thú** (UC59) · **E. Nhật ký chăm sóc** (UC57) · **F. Khách: đặt chỗ, danh sách, chi tiết, nhật ký, `/hotel`** (UC58, UC60).
> Hợp đồng: `docs/api/boarding-v1.md` (22 endpoint), `docs/api/sales-v1.md` #9. Rule: BR-LT-01…12, BR-LH-09, BR-KH-04/05, BR-BH-06, BR-TK-19. SM #6 Đặt chỗ, #7 Chuồng. ADR-0065.
> **BE:** boarding-v1 **#1–12 đã có** (T31, ADR-0065); **#13–22 và `abort-checkout` chưa có** (T42, T43): mock theo hợp đồng + BR, mọi chỗ hợp đồng im lặng là ASSUMPTION (mục G). Cả sáu phần **chưa chạy thử với BE thật**; chạy BE thật cần `VITE_API_MOCK=false` + `VITE_API_MOCK_ONLY` cho #13–22 và `POST /api/orders/*/abort-checkout` (`FE/.env.example`).
> Kế hoạch: `docs/superpowers/plans/2026-10-27-t48-fe-boarding.md`, brief UI `…-t48-ui-brief.md` (thư mục gitignore, chỉ ở máy dev).
> **Quyết định phạm vi 27/10 (người dùng):** *chưa cần responsive*. Trình duyệt thật chỉ được xem ở một số trang và **không đồng đều** (xem mục I, bảng "Trình duyệt"); phần lớn kiểm chứng là test jsdom trên mock.

## 0. Khung chung

| Route | Role | `<h1>` | Menu |
|---|---|---|---|
| `/boarding-bookings` | CUSTOMER (ngoài `RequireLinkDecision`, như `/appointments`) | Lưu trú của tôi | menu người dùng + `AccountSidebar`: "Lưu trú của tôi" |
| `/boarding-bookings/new` | CUSTOMER + `RequireLinkDecision` (BR-TK-19) | Đặt chỗ lưu trú | — |
| `/boarding-bookings/:bookingId` | CUSTOMER (ngoài `RequireLinkDecision`) | Chi tiết lưu trú | — |
| `/admin/boarding` | RECEPTIONIST, BRANCH_MANAGER, CARETAKER, VET | Lưu trú | Nghiệp vụ › Lưu trú |
| `/admin/kennels` | BRANCH_MANAGER | Chuồng lưu trú | Nghiệp vụ › Chuồng lưu trú |
| `/hotel` (công khai) | mọi người | (đọc loại chuồng + giá thật) | "Khách sạn thú cưng" |

`/boarding-bookings` là đường dẫn BE dự kiến đặt trong link thông báo (ADR-0065, mục Nợ). SUPER_MANAGER và ADMIN **không** vào được trang nào: BE trả 403 ở cả 12 endpoint (không có RoleHierarchy). Test quyền: `App.routes.test.tsx` (8 ca mở được + 7 ca 403 tại chỗ + 2 ca `RequireLinkDecision`), `staff-menu.test.ts`, `StaffSidebar.test.tsx`.

Tên truy cập của `role="dialog"` là hợp đồng giữa các WS: "Nhận thú", "Hủy vì hết chuồng", "Trả thú", "Thú mất", "Nhật ký chăm sóc". `Modal` dùng chung (`components/ui/modal-templates.tsx`) trước đây **không có tên truy cập** nên `getByRole('dialog', {name})` rỗng; đã thêm `aria-labelledby` ↔ `ModalTitle`. Các dialog mới của lưu trú dùng Radix `Dialog` (`components/ui/dialog.tsx`).

**Thứ tự kiểm của mock** (BE lưu trú, ADR-0065 §7): 401 → 400 body / query → 403 vai trò → 404 / 403 phạm vi → **409 trạng thái → 400 rule** (ngược `00-method.md`: hủy đặt chỗ đã `CANCELLED` hay quá ngày nhận là 409, không phải 400).

**State localStorage:** khóa `petcare-mock-boarding`; `load()` gộp mặc định (mẫu B) và chạy tác vụ hệ thống lười (ST05 `NO_SHOW`, ST15 `OVERDUE`) trước mỗi request. Test "state hình dạng cũ không làm route đầu tiên throw" ở `boarding.mock.test.ts`. Khung giờ test: thứ Sáu 23/10/2026 09:00 giờ VN (`src/test/boarding-harness.ts`, `pinClock()`).

## A. Mock lưu trú (`shared/api/mock/boarding.mock.ts`, `boarding.seed.ts`)

### A.1 Dữ liệu mẫu (chi nhánh 1; loại #5 "Chuồng chó nhỏ" 200.000 đ/đêm, chó ≤ 10 kg; loại #6 chưa bật tại CN1; T = hôm nay)

Chuồng: C-01, C-02 `OCCUPIED`, C-03 `AVAILABLE`, C-04 `MAINTENANCE` (sức chứa 3). Mã đặt chỗ `LT-yyMM-nnnnn`.

| # | Thú / khách | Trạng thái | Ghi chú |
|---|---|---|---|
| 1 | Bơ (khách 9001) | BOOKED thứ Sáu kế → Chủ nhật | mã cũ `LT-yymmdd-0001`, thay mảng "Bơ" của `branch.mock` |
| 2 | Cốm (khách 9002) | CHECKED_IN T−2 → T+8, C-01 | chưa nhật ký hôm nay; nhật ký bất thường + 1 bản bổ sung của VET |
| 3 | Mực (khách 9003) | CHECKED_IN T−1 → T+9, C-02 | có nhật ký hôm nay (còn sửa được) |
| 4 | Bim #2 (`khach@petcare.vn`) | BOOKED thứ Hai kế → +2 | |
| 5 | Bim | CHECKED_OUT T−20 → T−17 | 3 nhật ký |
| 6 | Bim | CANCELLED T+9 → T+11 | khách hủy |
| 7–11 | Nâu / Tép / Khoai / Đậu / Gạo | thú mất / phòng khám hủy / NO_SHOW / hủy muộn / CHECKED_OUT | khách 9004–9008 |

Khách 9001–9008 chỉ có tên trong đặt chỗ. `registerCounterGuests` (customer.mock) cho **bàn thu tiền và chi tiết khách** tra được họ theo id (để trả thú Cốm / Mực thu được tiền); không vào danh sách / tìm kiếm, và `findCustomer` giữ nguyên (đặt Order bán lẻ cho họ vẫn 404). Test: `boarding-guest.mock.test.ts`. Với ngày ghim 23/10: đêm 26, 27, 30/10 kín. Không có đặt chỗ `CHECKED_IN` cho Bim (test chuyển chủ của `customer-pet.mock` dùng Bim); đưa thú thật vào lưu trú: `recordDoseForMock(petId, 1|2, date)` + đặt + nhận.

### A.2 Endpoint và vai trò (test: `boarding.mock.test.ts` 42, `-booking` 62, `-stay` 40, `-care` 21, `-cross` 17, `-guest` 4)

| Nhóm | Vai trò |
|---|---|
| Chuồng GET (#1) | BRANCH_MANAGER, RECEPTIONIST, CARETAKER (VET 403) |
| Chuồng POST / PATCH / DELETE / start / end-maintenance (#2–6) | BRANCH_MANAGER |
| Còn chỗ (#7), đặt (#8), gia hạn (#11), hủy (#12) | CUSTOMER, RECEPTIONIST |
| Danh sách / chi tiết (#9, #10) | CUSTOMER, BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER |
| check-in-readiness, check-in (#13, #14) | RECEPTIONIST, CARETAKER |
| cancel-no-kennel, start-checkout, hand-over, end-deceased (#15–18), `POST /orders/:id/abort-checkout` | RECEPTIONIST |
| Nhật ký GET (#19) | như danh sách; POST / PATCH / addenda (#20–22): VET, CARETAKER (PATCH chỉ người ghi) |

### A.3 Chỗ BE thật lệch hợp đồng (mock chép BE)

#2 loại chuồng không phải BOARDING → **404** (hợp đồng: 400 BR-LT-01) · #6 `end-maintenance` trên chuồng `OCCUPIED` **không 409**, về `AVAILABLE` (FSM cho phép; suy từ code, chưa có test BE) · #9 `size` kẹp [1,100] im lặng thay vì 400 · #8 / #11 có thêm vài 400 ngoài hợp đồng ("Chi nhánh chưa nhận đặt chỗ lưu trú", "Ngày trả mới trùng ngày chi nhánh không làm việc", BR-LT-02 trùng ngày khi gia hạn) và 409 `CONCURRENCY_CONFLICT` khi đua · #10 nhân viên chi nhánh khác nhận 404 **trước** 403 · `vaccineWarnings` đã là dữ liệu thật (ADR-0065 L48 "500 tới T28" đã lỗi thời) · trường null trả `null`.

### A.4 Đổi hành vi mock khác (có chủ đích)

`appointment.load()` chạy sweep lưu trú trước và BR-LH-09 đếm hủy muộn / `NO_SHOW` lưu trú · `branch.mock` đọc `BOOKED` qua nguồn đăng ký (ngày nghỉ / thu hẹp giờ hủy hàng loạt đặt chỗ lưu trú), trường `boardings` cũ giữ nhưng không dùng · `sales.mock`: `cancel-unpaid` thêm 400 BR-LT-09 cho Order BOARDING khi thú còn trong chuồng (hợp đồng sales #10) · `customer-pet.mock`: thú đang lưu trú không đánh dấu mất / chuyển chủ / xóa được · `care.mock`: thông báo `CARE_LOG_ABNORMAL`, `BOARDING_CANCELLED_BY_CLINIC` (link: khách `/boarding-bookings/:id`, nhân viên `/admin/boarding`).

### A.5 Đột biến mock: 127 đột biến, 127 bị bắt (2 sống lần đầu: M07 guard thừa → bỏ, M88 → thêm test chuồng chi nhánh khác)

Nhóm: chuồng 15 · còn chỗ 10 · đặt chỗ 19 · xem / gia hạn / hủy 20 · ST05 / ST15 / hủy phòng khám 10 · nhận / trả / thú mất 25 · nhật ký 13 · mock khác 16.

## B. Chuồng (UC54) — `pages/admin/AdminKennelsPage.tsx`, `pages/admin/kennels/*`

Bảng chuồng (mã, loại, trạng thái, ghi chú, "Đang giữ" tra bằng một lệnh `GET /boarding-bookings?inStay=true&size=100`), lọc loại / trạng thái, dải sức chứa theo loại, tạo / sửa / xóa, bảo trì (cảnh báo `shortageBookings`), hết bảo trì. Test: 47 (43 trang + 4 `capacity`).

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | manager@store1.vn | Mở `/admin/kennels` | C-01…C-04, sức chứa 3 (2 có thú, 1 trống, 1 bảo trì) |
| 2 | manager@store1.vn | Tạo chuồng mã trống / 21 ký tự / `C-01` | 3 câu BE hiện đúng ô hoặc nguyên câu BR-LT-01 |
| 3 | manager@store1.vn | Bảo trì C-03 | Khung "Cảnh báo thiếu chỗ": Bim + Bơ; không đặt chỗ nào bị hủy |
| 4 | manager@store1.vn | Hết bảo trì C-03 | Cảnh báo biến mất, chuồng "Trống" |
| 5 | manager@store1.vn | Xóa C-03 | Chữ "giảm sức chứa từ 3 xuống 2" + khuyên bảo trì; 409 "đã có lịch sử…" nguyên câu |
| 6 | manager@store1.vn | Xem C-01 (có thú) | Nút Bảo trì / Xóa khóa kèm lý do |
| 7 | manager2@store2.vn | Mở trang | Không lỗi; "Tạo chuồng" khóa kèm giải thích (CN2 DRAFT: `/public/services` rỗng) |

**Đột biến: 57 / 57 bị bắt.** ASSUMPTION UI: loại tạo được = dịch vụ công khai nhóm BOARDING bật tại chi nhánh; cảnh báo thiếu chỗ là state trang (mất khi tải lại).

## C. Bảng lưu trú + đặt tại quầy (UC58 lễ tân) — `pages/admin/AdminBoardingPage.tsx`, `pages/admin/boarding/*`

Ba tab (Sắp nhận / Đang lưu trú / Lịch sử với bộ lọc trạng thái), lọc `from`/`to`, "Chưa có nhật ký hôm nay", phân trang 20; nút theo vai trò × trạng thái (hợp đồng §B); đặt tại quầy (khách → thú → loại chuồng phù hợp → ngày → còn chỗ từng đêm → đặt, cảnh báo mũi); gia hạn; hủy (cảnh báo hủy muộn); chi tiết. Test: 94 (34 `rules` + 53 trang + 7 `mount` với dialog thật). Đã kiểm: mỗi vai trò tải trang không phát sinh 403 (VET không gọi `/kennels`).

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | reception | Tab Sắp nhận | Bơ / Bim có Nhận thú, Hủy vì hết chuồng, Gia hạn, Hủy, Chi tiết |
| 2 | manager | Duyệt 3 tab | Không nút ghi, không "Đặt tại quầy" |
| 3 | doctor | Đang lưu trú | Chỉ Nhật ký; không 4xx |
| 4 | caretaker | Sắp nhận | Chỉ Nhận thú (+ Nhật ký ở tab in-stay) |
| 5 | reception | Bật "Chưa có nhật ký hôm nay" | Chỉ còn Cốm |
| 6 | reception | Đặt tại quầy: Trang → Bim → Chuồng chó nhỏ, 24→26/10 | Còn 1/3 mỗi đêm; đặt xong cảnh báo "5 bệnh · Chưa tiêm" |
| 7 | reception | Chọn Mochi hoặc Lu 18,3 kg | Không có loại chuồng, hiện lý do |
| 8 | reception | 29→31/10 | Đêm 30/10 tô đỏ, nút Đặt chỗ khóa |
| 9 | reception | Gia hạn Bơ đến 02/11 | "3 đêm (thêm 1 đêm)", 600.000 ₫ |
| 10 | reception ×2 | A hủy Bơ, B bấm Gia hạn / Hủy | Câu BE hiện, hàng biến, nút không kẹt |

**Đột biến: 50 / 50 bị bắt.** ASSUMPTION UI: "Đặt chỗ" chỉ bật khi còn chỗ đã tải (`available === true`); loại chuồng lấy từ `/api/public/services?group=BOARDING&branchId` cho mọi vai trò, ẩn sai loài / quá cân, có dòng đếm số loại đã ẩn.

## D. Nhận / trả thú (UC59) — `pages/admin/boarding/stay/*`

`CheckInDialog` (điều kiện BR-LT-05 / 04 / 08, form cân + tình trạng + SĐT khẩn), `NoKennelDialog`, `CheckoutDialog` (Bắt đầu → thu tiền ở bàn Lễ tân → Giao thú; hủy phiên có lý do), `DeceasedDialog`. Test: 72 (5 file, gồm `StayCollect` đi trọn vòng thu tiền thật).

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | reception | Nhận thú Bim #4 khi chưa tiêm 5 bệnh | Bảng "5 bệnh – Chưa tiêm", link walk-in, không có form |
| 2 | reception | Ghi mũi 5 bệnh rồi Nhận thú Bim, cân "6,25" | Ghi chú nhận sớm, chuồng C-03 chọn sẵn, thành CHECKED_IN |
| 3 | reception | Nhận thú lúc 12:30 | Chặn BR-LT-04 (ngoài giờ) |
| 4 | manager rồi reception | Đưa C-03 vào bảo trì, rồi Hủy vì hết chuồng | CANCELLED do phòng khám |
| 5 | caretaker | Nhận Bơ #1 ngày 30/10 | Nhận được; không có nút hủy |
| 6 | reception | Trả thú Cốm: Bắt đầu | Mã Order, số đêm, tiền; Giao thú khóa (chưa PAID) |
| 7 | reception | Thu tiền tại bàn Lễ tân → thu | Order Lưu trú trong phiếu, PAID; mở lại Trả thú thì Giao thú bật |
| 8 | reception | Hủy phiên (trống lý do, rồi có lý do) | Báo thiếu lý do; bắt đầu lại sinh Order mới |
| 9 | reception | Thu ở cửa sổ khác, quay lại | Tự thấy PAID (nghe `focus` + nút "Kiểm tra lại") |
| 10 | reception | Thú mất (Cốm) | Order tính đến hôm nay, nhắc BR-KH-05 |
| 11 | 2 lễ tân cùng bấm | Người sau nhận / giao | Câu 409 của BE, chỉ còn Đóng |

**Đột biến: 45 / 45 bị bắt.** ASSUMPTION UI: bước trả thú suy từ `GET booking` + `GET order`, không lưu cục bộ; số đêm = tổng số lượng dòng BOARDING; hook riêng `useStayBooking` / `useStayOrder` vì `staleTime` 60 s của App làm Order PENDING cũ còn hiện sau khi thu ở màn khác.

## E. Nhật ký chăm sóc (UC57) — `pages/admin/boarding/care-logs/*`

`CareLogDialog`: danh sách nhóm theo ngày, ghi mới (≤ 5 ảnh URL `https`, cờ bất thường có xác nhận), sửa trong `editableUntil` (có "còn N phút"), bản bổ sung. Test: 78 (29 `rules` + 3 hook + 46 dialog).

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | caretaker | Mở Nhật ký Cốm | Banner "Hôm nay chưa có nhật ký"; mục 22/10 có "Bất thường" + bổ sung |
| 2 | caretaker | Ghi mới: ăn + 1 ảnh https | Mục mới đứng đầu, banner xanh, "còn 60 phút để sửa" |
| 3 | caretaker | Ngày mai / ô 301 ký tự / URL http / trống rồi Lưu | Lỗi tại ô, không gửi |
| 4 | caretaker | Bật "Đánh dấu bất thường", Lưu, Xác nhận | Bước xác nhận nêu hệ quả; mục có chip "Bất thường"; khách + lễ tân nhận thông báo |
| 5 | caretaker | Sửa mục vừa ghi; nhìn mục cũ | Có Sửa + còn N phút; mục cũ: "Đã quá thời hạn sửa — thêm bản bổ sung" |
| 6 | doctor | Nhật ký Mực: thêm bổ sung | Mục của người khác không có Sửa; bổ sung hiện "Bạn · giờ" |
| 7 | reception / manager | Mở Nhật ký | Chỉ xem, không nút ghi |
| 8 | caretaker + reception tab khác | Reception bấm Thú mất, caretaker Lưu | "Thú cưng không đang lưu trú", dialog chuyển chỉ xem |

**Đột biến: 42 / 42 bị bắt.** ASSUMPTION UI: mục hoàn toàn trống bị chặn ở form (mock không chặn); đặt chỗ `CHECKED_OUT` vẫn thêm được bản bổ sung (hợp đồng A4) nhưng trang chỉ mở dialog cho CHECKED_IN / OVERDUE; ghi chú không giới hạn độ dài ở UI.

## F. Khách (UC58, UC60)

### F.1 Danh sách + đặt chỗ + `/hotel` (`pages/customer/BoardingBookingsPage.tsx`, `BoardingNewPage.tsx`, `pages/customer/boarding/*`, `pages/hotel/*`) — 91 test

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | khach@petcare.vn | `/boarding-bookings` | 1 thẻ Bim "Đã đặt" (tạm tính); tab "Đã qua" có 2 thẻ |
| 2 | khach@ bị hạn chế | Mở danh sách | Thấy thông báo, không nút đặt, vẫn xem được |
| 3 | khachmoi@petcare.vn | `/boarding-bookings` rồi `/new` | Danh sách trống không lỗi; `/new` chuyển sang "Hoàn tất liên kết" |
| 4 | khach@ | Chọn Bim / Mochi / chó 18,3 kg | Chỉ "Chuồng chó nhỏ"; mèo và chó quá cân có giải thích |
| 5 | khach@ | Ngày nhận hôm nay, ngày 61, 31 đêm | Ô ngày bị khóa / lỗi, nút khóa |
| 6 | khach@ | Rex, 26/10→28/10 | "Hết chỗ" từng đêm; nút khóa kèm "Hết chỗ các đêm: 26/10, 27/10" |
| 7 | khach@ | Đặt Bim 24→25/10 | Mã `LT-2610-…`; cảnh báo "5 bệnh" + link `/booking`; nút sang chi tiết |
| 8 | khach@ | Đặt lại Bim 01→03/11 | Thành công (đặt chỗ #6 đã hủy không chặn) |
| 9 | khach@ + lễ tân | Lễ tân đặt nốt chỗ, khách bấm đặt | Câu BR-LT-03; dải đêm tải lại thành "Hết chỗ" |
| 10 | ẩn danh | `/hotel` | Giá thật từ catalog; chỉ gọi `/api/public/*`, không gọi availability |

**Đột biến: 58 / 58 bị bắt.** `/hotel`: bỏ "Tìm phòng", nhãn cứng (Camera 24/7, Wifi, Spa miễn phí, 150k–490k) và `StayPriceCalculator` (đã xóa file); thay bằng máy tính tiền, khối "Khi gửi thú" (BR-LT-09 / 11 / 12), chi nhánh từ dữ liệu công khai.

### F.2 Chi tiết + gia hạn / hủy + nhật ký (`pages/customer/BoardingDetailPage.tsx`, `pages/customer/boarding-detail/*`) — 72 test

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | khach@ | Mở `/boarding-bookings/4` | Đã đặt, 2 đêm, 400.000 ₫, Gia hạn + Hủy, chưa có nhật ký |
| 2 | khach@ | Mở #5 | Nhật ký 3 ngày mới→cũ, giờ VN, không nút |
| 3 | khach@ | Mở #6 | Khách hủy + lý do, không tạm tính |
| 4 | khach@ | Gia hạn #4 đến 29/10 | Xem trước 3 đêm, toast, trang cập nhật |
| 5 | khach@ | Gia hạn đến 31/10 | "Hết chỗ các đêm: 30/10", nút không kẹt |
| 6 | khach@ | Hủy #4 ngày 23/10 / 25/10 | Không cảnh báo / cảnh báo hủy muộn kèm hệ quả BR-LH-09 |
| 7 | khach@ | `/boarding-bookings/2` (của khách khác), `/abc` | "Không tìm thấy lưu trú"; `/abc` không gọi API |
| 8 | khachmoi@ | Gia hạn / hủy | Không bị chặn (BE chỉ chặn đặt mới); đặt chỗ người khác → 404 |

**Đột biến: 59 / 59 bị bắt.** Nhật ký tự làm mới 60 giây khi đang lưu trú (dọn timer khi rời trang), không làm mới khi đã trả thú / trang ẩn. Các trạng thái `CHECKED_IN` / `OVERDUE` / thú mất của khách chỉ xem được bằng cách vá `localStorage` của mock (`khach@` không có seed các trạng thái đó).

## G. ASSUMPTION của T48 (BE #13–22 chưa có)

Chi tiết ở đầu từng handler trong `boarding.mock.ts`; tóm tắt: **A1** check-in (thứ tự lỗi, nhận sớm cập nhật `checkInDate`, SĐT `^0[0-9]{9,10}$`, cân > 0 tối đa 2 số lẻ) · **A2** cancel-no-kennel (400 BR-LT-08 nếu còn chuồng) · **A3** start-checkout (số đêm `max(1, hôm nay − ngày nhận thực tế)` + 1 nếu quá 12:00; dùng lại Order PENDING; Order PAID trả lại, không sinh mới) · **A4** hand-over (400 BR-LT-09 khi chưa PAID) · **A5** end-deceased · **A6** abort-checkout · **A7** nhật ký (≤ 5 ảnh https, `editableUntil` = ghi + 60 phút, bất thường báo khách + lễ tân) · **A8 / Q1** ảnh là URL https, không upload · **A9** gia hạn chỉ BOOKED / CHECKED_IN · **A10** `activeOrderId` = Order BOARDING mới nhất chưa CANCELLED của đặt chỗ đang trong chuồng, `null` khi kết thúc (BE hiện luôn `null`) · **A11** thu tiền trả thú ở bàn Lễ tân `/staff/reception`, không phải `/admin/payments` · **A12** ST05 / ST15 mô phỏng lười (ST15 BE chưa có).

## H. Còn lại, không làm ở T48

- **Responsive 360 / 390 px: bỏ theo quyết định 27/10** (làm ở T57). Một số trang đã được xem màn hẹp trước khi có quyết định (bảng ở mục I); không trang nào được rà đủ ba cỡ. Header công khai chưa responsive (lỗi có sẵn).
- **Chưa xem trong trình duyệt thật:** bốn dialog `stay/` (Nhận thú, Hủy vì hết chuồng, Trả thú, Thú mất) và vòng trả thú qua hai màn (Cốm → Bắt đầu → "Thu tiền tại bàn Lễ tân" → `/staff/reception` mở ca, thu → quay lại → Giao thú). Vòng này chỉ được chạy trong jsdom (`StayCollect.test.tsx`), và seed khách 9002 / 9003 (`registerCounterGuests`) được thêm sau khi STAY làm xong. Làm tay trước khi demo.
- **Cần báo BE-2 (T42, T43):** (1) FE phụ thuộc `Booking.activeOrderId` sau `start-checkout` (A10): nếu BE không điền, bước Order của "Trả thú" sẽ như không làm gì; (2) `CareLogAddendum` chỉ có `createdBy`, đề nghị thêm `createdByName`; (3) `CheckoutStarted` không có mã Order (FE hiện "Order #id"); (4) `Booking` thiếu `cancelledAt` / `createdAt` / số tiền chốt / `endNote`; (5) Q1 ảnh nhật ký (hiện dán URL https); (6) link thông báo lưu trú: khách `/boarding-bookings/:id`, nhân viên `/admin/boarding`; (7) `end-maintenance` chuồng `OCCUPIED` không 409 (BE lệch hợp đồng).
- **Giao diện:** "Thu tiền tại bàn Lễ tân" dùng `attachOrder`, âm thầm thay phiếu thu đang dở của khách khác; dưới 1024 px phiếu nằm sau tab "Phiếu thu" (chưa tự chuyển tab). `DatePicker` (khách): nút "Hôm nay" bỏ qua `minDate` (form tự chặn). Ngày nghỉ chi nhánh không phải dữ liệu công khai nên FE không khóa trước (BE trả BR-LT-04). Chi nhánh DRAFT (CN2) không tạo được chuồng bằng UI vì `/public/services` rỗng.
- **Mock:** `POST care-logs` chấp nhận mục trống (UI chặn); `PATCH care-logs` không kiểm đặt chỗ còn trong chuồng; `khach@` không có seed `CHECKED_IN` / `OVERDUE` / thú mất.
- Care Task `PICKUP_OVERDUE` (T39) và ST15 thật (T43) chưa có; mock không sinh Care Task.
- Cảnh báo console có sẵn: `DialogOverlay` "Function components cannot be given refs" (`ui/dialog.tsx`).

## I. Kiểm chứng

Đo 27/10/2026 (sau khi cả 7 workstream xong):

| Kiểm | Kết quả |
|---|---|
| `npx tsc --noEmit` | 0 lỗi |
| `npm run lint` (`--max-warnings 0`) | 0 lỗi |
| `npx vitest run --maxWorkers=4` (cả bộ) | **147 file, 2868 ca xanh, thoát mã 0, không "Unhandled Errors"**; chạy **hai lần liên tiếp**, cả hai sạch (trước T48: 120 file / 2196 ca ở T47) |
| `npm run build` | thành công (cảnh báo chunk > 500 kB có từ trước) |
| Dấu conflict (`git grep '^<<<<<<<'`) | không có |
| Đột biến | 438 (mock 127, chuồng 57, bảng 50, nhận / trả 45, nhật ký 42, khách 58 + 59); mọi mutant bị bắt (các ca sống lần đầu đã sửa test hoặc bỏ mã thừa) |
| Trình duyệt (Playwright, mock) | `/admin/kennels`: 1280 / 390 / 360 px, manager1 và manager2 · `/admin/boarding` (bảng): **chỉ 360 px** · dialog Nhật ký trong `/admin/boarding`: 1280 / 390 / 360 px · `/boarding-bookings`, `/new`, `/hotel`: 1280 / 390 / 360 px (bản build + preview) · `/boarding-bookings/:id`: 1280 px (#4, #5, #6, đang lưu trú, ảnh lớn), 360 px, 390 px (#4) · **`stay/`: không mở lần nào** · MOCK: không (chỉ test). Đo `scrollWidth`: không tràn ở các cỡ đã xem |
| `npx playwright test` (e2e có sẵn, 27/10) | 13 xanh, 8 bỏ qua (`screens.spec`), **3 đỏ ở `workspaces.spec.ts`** (desk → phòng khám → thu ngân; groomer kéo thẻ; bán lẻ bằng bàn phím: chờ nút "Tiếp nhận" Lucky / "Bắt đầu" Rocky / "Mở ca thu ngân"). Không do T48: spec là kịch bản bàn cũ chưa sửa từ T5 và `T36-fe.md:329` đã ghi "để nguyên"; chưa chạy lại trên bản trước T48 để đối chứng. `branch.spec` (kỳ vọng "0 đặt chỗ lưu trú" khi thêm ngày nghỉ thứ Ba) và `catalog.spec` (loại chuồng) xanh |

Hai lần chạy cả bộ là bằng chứng hỗ trợ (bài học T46–T47: một lần sạch không chứng minh hết chập chờn); cơ chế dọn `setInterval` của GSAP ở `src/test/setup.ts` vẫn là thứ làm kết quả đáng tin. Các lỗi tìm ra bằng trình duyệt hoặc đo, không phải bằng test: nhãn "còn 61 phút" do đồng hồ dialog cũ tới 10 giây (CARELOG, đã sửa); padding hero `/hotel` bị luật CSS không-layer nuốt (CUST-BOOK, đã sửa); `Modal` không có tên truy cập (STAY phát hiện, đã thêm `aria-labelledby`).
