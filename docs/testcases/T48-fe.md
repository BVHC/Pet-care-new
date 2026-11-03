# T48 (FE): Lưu trú: chuồng, đặt chỗ, nhận / trả thú, nhật ký chăm sóc; khách xem lưu trú & nhật ký (UC54, UC57–60)

> Phạm vi: **A. Mock lưu trú** (boarding-v1 #1–22 + sales-v1 #9) · **B. Chuồng** (UC54) · **C. Bảng lưu trú của chi nhánh + đặt tại quầy** (UC58 lễ tân) · **D. Nhận / trả thú** (UC59) · **E. Nhật ký chăm sóc** (UC57) · **F. Khách: đặt chỗ, danh sách, chi tiết, nhật ký, `/hotel`** (UC58, UC60).
> Hợp đồng: `docs/api/boarding-v1.md` (22 endpoint), `docs/api/sales-v1.md` #9. Rule: BR-LT-01…12, BR-LH-09, BR-KH-04/05, BR-BH-06, BR-TK-19. SM #6 Đặt chỗ, #7 Chuồng. ADR-0065.
> **BE:** boarding-v1 **#1–12 đã có** (T31, ADR-0065); **#13–22 BE-2 đã giao** (T42 29/10, T43 30/10, kèm ST15) và `abort-checkout` (sales #9) cũng đã có. Mock theo hợp đồng + BR, mọi chỗ hợp đồng im lặng là ASSUMPTION (mục G); **1/11 mock đã được đối chiếu với code BE thật và sửa các chỗ lệch** (xem mục G, `docs/testcases/test-tay-ghep-api-theo-cum.md` cụm 6 và `T49-fe.md` mục C.14, cụm 6, 1/11). Chạy BE thật chỉ cần `VITE_API_MOCK=false`, không còn cần `VITE_API_MOCK_ONLY` cho #13–22 và `abort-checkout`. Phần B, C đã chạy thật ở C6-01…C6-04 (27–30/10); phần D, E (`stay/`, nhật ký) đã mở trên BE thật 1/11 ở chế độ **chỉ đọc** (C6-06…C6-12); **2/11 (đợt 2) đã chạy thật các nút GHI thành công**: nhận thú (nhận sớm), Bắt đầu trả thú, thu tiền ở bàn Lễ tân, Giao thú, hủy phiên trả thú, ghi / sửa / bổ sung nhật ký thường, và phần F ở mức khách đặt chỗ ONLINE rồi xem chi tiết + nhật ký (UC60); xem C6-05, C6-13, C6-14, C6-15, C6-17 (tự động, chưa tick tay) và `docs/testcases/T49-fe.md` mục C.14 (đợt 2). **Chiều 2/11 đã chạy thêm** nhật ký BẤT THƯỜNG gửi thông báo thật (C6-15), trả thú cùng ngày với số đêm thật, thu tiền mặt và Giao thú lần hai trên đặt chỗ #6 (C6-13): xem mục H. Vẫn chưa chạy thật: thú mất (`end-deceased`, C6-16, HẸN 3/11 12:08 sau khi quan sát `PICKUP_OVERDUE` của cụm 7).
> Kế hoạch: `docs/superpowers/plans/2026-10-27-t48-fe-boarding.md`, brief UI `…-t48-ui-brief.md` (thư mục gitignore, chỉ ở máy dev).
> **Quyết định phạm vi 27/10 (người dùng):** *chưa cần responsive*. Trình duyệt thật chỉ được xem ở một số trang và **không đồng đều** (xem mục I, bảng "Trình duyệt"; từ 1/11 `/admin/boarding` cùng các hộp `stay/` và Nhật ký đã mở trên Chrome thật 1280 px với BE thật, 1/11 chỉ đọc và 2/11 có ghi); phần lớn kiểm chứng là test jsdom trên mock.

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

### A.2 Endpoint và vai trò (test: `boarding.mock.test.ts` 42, `-booking` 62, `-stay` 40, `-care` 27 (21 lúc 27/10, thêm 6 ca khi sửa mock 1/11), `-cross` 17, `-guest` 4; các số còn lại là lúc 27/10, chưa đếm lại sau 1/11)

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

#2 loại chuồng không phải BOARDING → **404** (hợp đồng: 400 BR-LT-01) · #6 `end-maintenance` trên chuồng `OCCUPIED` → **409** (BE `KennelService.endMaintenance` chỉ nhận `MAINTENANCE`, xem `T45-be.md` §3 B1; mock cũ cho về `AVAILABLE`, đã sửa 1/11) · #9 `size` kẹp [1,100] im lặng thay vì 400 · #8 / #11 có thêm vài 400 ngoài hợp đồng ("Chi nhánh chưa nhận đặt chỗ lưu trú", "Ngày trả mới trùng ngày chi nhánh không làm việc", BR-LT-02 trùng ngày khi gia hạn) và 409 `CONCURRENCY_CONFLICT` khi đua · #10 nhân viên chi nhánh khác nhận 404 **trước** 403 · `vaccineWarnings` đã là dữ liệu thật (ADR-0065 L48 "500 tới T28" đã lỗi thời) · trường null trả `null`.

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
| 1 | reception | Tab Sắp nhận | Bơ / Bim có Nhận thú, Gia hạn, Hủy, Chi tiết. "Hủy vì hết chuồng" chỉ hiện khi hôm nay ≥ ngày nhận (sửa 1/11 theo BE: chưa tới ngày nhận thì BE 400 BR-LT-08); ngày ghim 23/10 thì Bơ / Bim đều chưa tới ngày nhận nên chưa có nút |
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
| 4 | manager rồi reception | Đưa C-03 vào bảo trì, rồi Hủy vì hết chuồng | CANCELLED do phòng khám, **chỉ với đặt chỗ `BOOKED` đã tới ngày nhận** (từ 1/11 nút ẩn trước ngày nhận, BE 400 BR-LT-08 "Chưa tới ngày nhận…"); Bim #4 ngày ghim 23/10 chưa tới ngày nhận nên ca này cần đặt chỗ khác |
| 5 | caretaker | Nhận Bơ #1 ngày 30/10 | Nhận được; không có nút hủy |
| 6 | reception | Trả thú Cốm: Bắt đầu | Mã Order, số đêm, tiền; Giao thú khóa (chưa PAID) |
| 7 | reception | Thu tiền tại bàn Lễ tân → thu | Order Lưu trú trong phiếu, PAID; mở lại Trả thú thì Giao thú bật |
| 8 | reception | Hủy phiên (trống lý do, rồi có lý do) | Báo thiếu lý do; bắt đầu lại sinh Order mới |
| 9 | reception | Thu ở cửa sổ khác, quay lại | Tự thấy PAID (nghe `focus` + nút "Kiểm tra lại") |
| 10 | reception | Thú mất (Cốm) | Order tính đến hôm nay, nhắc BR-KH-05 |
| 11 | 2 lễ tân cùng bấm | Người sau nhận / giao | Câu 409 của BE, chỉ còn Đóng |

**Đột biến: 45 / 45 bị bắt.** ASSUMPTION UI: bước trả thú suy từ `GET booking` + `GET order`, không lưu cục bộ (từ 1/11 Order lấy từ `activeOrderId`, còn null sau khi thu thì tra `GET /api/orders?boardingBookingId=` bằng `useStayLatestOrderId`, xem A10); số đêm = tổng số lượng dòng BOARDING; hook riêng `useStayBooking` / `useStayOrder` vì `staleTime` 60 s của App làm Order PENDING cũ còn hiện sau khi thu ở màn khác.

## E. Nhật ký chăm sóc (UC57) — `pages/admin/boarding/care-logs/*`

`CareLogDialog`: danh sách nhóm theo ngày, ghi mới (ảnh là URL `http` hoặc `https` dài tối đa 500 ký tự, tới 10 ô, số ảnh tối đa do BE quyết bằng BR-LT-11 từ 2/11, trước đó FE chỉ nhận ≤ 5 ảnh `https`; cờ bất thường có xác nhận), sửa trong `editableUntil` (có "còn N phút"), bản bổ sung. Test: 78 (29 `rules` + 3 hook + 46 dialog), số lúc 27/10, chưa đếm lại sau 1/11 và 2/11 (1/11 thêm `CareLogFixes.test.tsx` 12 ca; 2/11 nới ảnh sửa `rules.ts` và các ca ảnh).

| # | Tài khoản | Làm | Kỳ vọng |
|---|---|---|---|
| 1 | caretaker | Mở Nhật ký Cốm | Banner "Hôm nay chưa có nhật ký"; mục 22/10 có "Bất thường" + bổ sung |
| 2 | caretaker | Ghi mới: ăn + 1 ảnh https | Mục mới đứng đầu, banner xanh, "còn 60 phút để sửa" |
| 3 | caretaker | Ngày mai / ngày trước ngày nhận thú / ô 301 ký tự / URL `ftp://` / trống rồi Lưu | Lỗi tại ô, không gửi. Từ 2/11 URL `http://` được nhận (hết lỗi; BE nhận http + https ≤ 500 ký tự, L-40) nên ca này dùng `ftp://`. Từ 1/11 ô ngày có `min` = ngày nhận thực tế và `max` = hôm nay; ngày trước ngày nhận → "Ngày nhật ký phải từ ngày nhận thú tới hôm nay" (BE cũng chặn, đã thấy trên Chrome thật với Mochi, không gửi request) |
| 4 | caretaker | Bật "Đánh dấu bất thường", Lưu, Xác nhận | Bước xác nhận nêu hệ quả; mục có chip "Bất thường"; khách + lễ tân nhận thông báo |
| 5 | caretaker | Sửa mục vừa ghi; nhìn mục cũ | Có Sửa + còn N phút; mục cũ: "Đã quá thời hạn sửa — thêm bản bổ sung" |
| 6 | doctor | Nhật ký Mực: thêm bổ sung | Mục của người khác không có Sửa; bổ sung hiện "Bạn · giờ" |
| 7 | reception / manager | Mở Nhật ký | Chỉ xem, không nút ghi |
| 8 | caretaker + reception tab khác | Reception bấm Thú mất, caretaker Lưu | "Thú cưng không đang lưu trú", dialog chuyển chỉ xem |

**Đột biến: 42 / 42 bị bắt.** ASSUMPTION UI: mục hoàn toàn trống bị chặn ở form (BE chặn; mock cũ không chặn, đã sửa 1/11); đặt chỗ `CHECKED_OUT` vẫn thêm được bản bổ sung (hợp đồng A4) nhưng trang chỉ mở dialog cho CHECKED_IN / OVERDUE; ghi chú không giới hạn độ dài ở UI.

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

## G. ASSUMPTION của T48 (viết lúc BE #13–22 chưa có; BE đã giao, mock đối chiếu lại 1/11)

Chi tiết ở đầu từng handler trong `boarding.mock.ts`; tóm tắt (A1, A2, A3, A10, A12 đã sửa cho khớp BE thật ngày 1/11, xem `test-tay-ghep-api-theo-cum.md` L-30…L-38): **A1** check-in (thứ tự lỗi theo BE: `@Valid` → vai → phạm vi → 409 → BR-LT-06 → BR-LT-04 → nhận sớm BR-LT-02/03 → BR-LT-05 → thiếu cân / chuồng BR-LT-08 → chuồng → cân BR-KH-04; BE không `@NotNull` `kennelId` / `weightKg`; nhận sớm cập nhật `checkInDate` (đã thấy trên BE thật 2/11, C6-13), SĐT `^0[0-9]{9,10}$`, cân làm tròn HALF_UP 2 số và nằm trong [0,01; 9999,99]) · **A2** cancel-no-kennel (409 FSM → 400 BR-LT-08 "Chưa tới ngày nhận…" khi hôm nay < ngày nhận → 400 BR-LT-08 nếu còn chuồng trống đúng loại) · **A3** start-checkout (thứ tự BE: thú còn trong chuồng → giờ mở cửa 400 BR-LT-04 → đã có Order PAID thì 409 "Đã thu tiền lưu trú, chỉ còn giao thú", không sinh Order mới; dùng lại Order PENDING; số đêm `max(1, ngày ở + (quá 12:00 ? 1 : 0))` với đêm trễ cộng TRƯỚC khi lấy tối thiểu 1, đúng 12:00:00 chưa trễ; thú mất `end-deceased` không cộng đêm trễ; trên BE thật 2/11, C6-13 và C6-14: "Tính lại tiền" dùng lại cùng Order PENDING, ngày ở 0 → 1 đêm và Mochi 3 đêm khớp BE, nhưng cả hai ca SÁNG chạy trước 12:00 nên không phân biệt công thức đêm trễ cũ với mới; chiều 2/11, C6-13 lần hai: đặt chỗ #6 nhận và trả CÙNG ngày, trả 14:09 sau 12:00 ⇒ Order #16 1 đêm (mock cũ `max(1, ngày) + trễ` sẽ cho 2), là bằng chứng thật cho nhánh ngày ở 0 sau 12:00; số hạng "+1 sau 12:00" khi ngày ở > 0 chưa tách riêng được, L-31) · **A4** hand-over (400 BR-LT-09 khi chưa PAID; chạy thật 2/11, C6-17: BE trả "Order lưu trú chưa thanh toán, chưa giao thú được (BR-LT-09)") · **A5** end-deceased (chưa chạy thật, C6-16) · **A6** abort-checkout (chạy thật 2/11, C6-14: Order → `CANCELLED`, `cancel_type` `CHECKOUT_ABORTED`, đặt chỗ vẫn `CHECKED_IN`) · **A7** nhật ký (tối đa 5 ảnh theo [CFG] `care_log.max_photos`, `editableUntil` = ghi + 60 phút, bất thường báo khách + lễ tân) · **A8 / Q1** ảnh là URL, không upload (ban đầu FE chỉ nhận https; BE nhận http + https ≤ 500 ký tự nên 2/11 FE nới: nhận cả http, tới 10 ô, BE quyết BR-LT-11; L-40) · **A9** gia hạn chỉ BOOKED / CHECKED_IN · **A10** `activeOrderId` chỉ là Order lưu trú OPEN/PENDING của đặt chỗ đang trong chuồng (BE `findUnfinishedByBookingIds`, hợp đồng A10, ADR-0086 F13), `null` sau khi PAID và khi kết thúc; mock cũ giả định có cả PAID nên che lỗi, đã sửa 1/11. FE tra Order mới nhất chưa CANCELLED qua `GET /api/orders?boardingBookingId=` (`useStayLatestOrderId`, chỉ trang đầu) khi `activeOrderId` null và thú còn trong chuồng. **Bằng chứng E2E trên BE thật 2/11** (C6-13, đặt chỗ #5; giao diện và mạng là lời agent, DB là tự đo SELECT): sau khi Order #15 `PAID`, `GET /api/boarding-bookings/5` trả `activeOrderId: null` (khóa có mặt, giá trị null); FE tra Order qua `GET /api/orders?boardingBookingId=5` ra Order #15 `PAID` (`GET /api/orders/15` cũng `PAID`); nút "Giao thú" BẬT, không hiện "Bắt đầu trả thú"; giao thú 200. Lần hai chiều 2/11 (đặt chỗ #6, Order #16 `PAID`): cùng kết quả (`activeOrderId: null` → tra `GET /api/orders?boardingBookingId=6` → "Giao thú" BẬT → hand-over 200) · **A11** thu tiền trả thú ở bàn Lễ tân `/staff/reception`, không phải `/admin/payments` · **A12** ST05 / ST15 mô phỏng lười trong mock (ST15 `BoardingOverdueJob` BE đã có từ T43, chưa so hành vi với mock).

## H. Còn lại, không làm ở T48

- **Responsive 360 / 390 px: bỏ theo quyết định 27/10** (làm ở T57). Một số trang đã được xem màn hẹp trước khi có quyết định (bảng ở mục I); không trang nào được rà đủ ba cỡ. Header công khai chưa responsive (lỗi có sẵn).
- **Đã xem trong trình duyệt thật 1/11, chỉ đọc** (cụm 6 đợt 1: Chrome 1280 px, BE thật 8082, mock tắt hoàn toàn, mọi request ghi bị chặn trừ login và allow-list; số liệu do agent chạy Playwright, lời agent): hộp Nhận thú (#2 Kem: readiness, thiếu mũi BR-LT-05, ngoài giờ BR-LT-04, nhận sớm), Hủy vì hết chuồng (#2: BE 400 BR-LT-08 "Chưa tới ngày nhận…", nút nay ẩn), Trả thú (#3 Mochi: mở ra không bấm, `GET /api/orders?boardingBookingId=3` rỗng), Thú mất (#3: mở rồi "Quay lại") và Nhật ký (xem, form, bất thường dừng ở khối xác nhận). Bằng chứng và ca: `test-tay-ghep-api-theo-cum.md` C6-06…C6-12; lỗi tìm thấy: L-30…L-38 ở file đó; kỹ thuật: `T49-fe.md` mục C.14.
- **Đã chạy thật sáng 2/11 (cụm 6 đợt 2 sáng, CÓ GHI, 09:45–10:37 sáng T2 trong giờ mở cửa; tự động, chưa tick tay):** Playwright Chrome 1280 px, BE 8082, mock tắt hoàn toàn, guard chặn request ghi ngoài allow-list từng bước, `abortedWrites` rỗng (lời agent). Nhận thú (nhận sớm, callout BR-LT-08), Bắt đầu trả thú và "Tính lại tiền" (dùng lại cùng Order), thu tiền mặt ở bàn Lễ tân, Giao thú, hủy phiên trả thú (Mochi, Order #14 `CANCELLED` / `CHECKOUT_ABORTED`), ghi / sửa / bổ sung nhật ký thường, khách `minh.anh` đặt chỗ ONLINE và xem chi tiết + nhật ký (UC60). BE thật trả 400 BR-LT-09 khi giao trước thu và 400 BR-LT-11 khi gửi 6 ảnh (câu nguyên văn ở C6-17, C6-15). Vòng trả thú qua hai màn (Bắt đầu → thu ở `/staff/reception` → quay lại → Giao thú), trước đó chỉ chạy trong jsdom (`StayCollect.test.tsx`), nay chạy trọn trên BE thật với đặt chỗ #5. **Bằng chứng E2E của A10:** sau khi Order #15 `PAID`, `GET /api/boarding-bookings/5` trả `activeOrderId: null` (khóa có mặt, giá trị null); FE tra Order qua `GET /api/orders?boardingBookingId=5` (ra Order #15 `PAID`), nút "Giao thú" BẬT, không hiện "Bắt đầu trả thú". Ca và số liệu: `test-tay-ghep-api-theo-cum.md` C6-05, C6-13…C6-15, C6-17; lỗi tìm thấy: L-39, L-40, L-44…L-48 ở file đó; kỹ thuật: `T49-fe.md` mục C.14 (đợt 2).
- **Đã chạy thật chiều 2/11 (cụm 6 đợt chiều, 13:46–14:13, trong giờ mở cửa; CÓ GHI; tự động, chưa tick tay; Hiến duyệt "làm hết đi" ~12:20):** Playwright Chrome 1280 px hoặc `fetch` có guard theo thân request, BE 8082, mock tắt, `abortedWrites` rỗng ở mọi script (lời agent); DB là tự đo SELECT. Khách `minh.anh` tự đặt `LT-2611-00002` (đặt chỗ **#6**, ONLINE, 03 → 04/11), lễ tân nhận SỚM 13:58:17 vào CM-02. **Nhật ký BẤT THƯỜNG thật** (C6-15): `tai.bui` ghi care_logs #6 `isAbnormal`; khối "Xác nhận đánh dấu bất thường" hiện nguyên văn "Nhật ký được gửi ngay; chủ thú (trong ứng dụng và qua email, nếu có) và lễ tân chi nhánh (trong ứng dụng) nhận thông báo tức thì và không thu hồi được…", bấm "Lưu nhật ký" lúc đó không gửi request, bấm "Xác nhận và gửi thông báo" ⇒ 201; hệ quả: `notifications` #4 (khách, link `/boarding-bookings/6`), #5 (`mai.le`), #6 (`anh.pham`), `notification_outbox` #35–#38 (IN_APP khách, EMAIL tới email tài khoản vì `customers.email` NULL, 2 IN_APP lễ tân; cả 4 `SENT` ~0,7 giây sau POST), Mailpit +1 thư; khách đọc thấy chuông "1 chưa đọc" và huy hiệu "Bất thường" (khớp ADR-0085 §4–5; dòng "Bật Đánh dấu bất thường" ở bảng E.4 nay có phản hồi BE thật). **Trả thú cùng ngày, số đêm thật, thu tiền mặt, Giao thú lần hai** (C6-13): trả 14:09 SAU 12:00 ⇒ Order #16 `DH-2611-00003` 1 đêm × 180.000 ₫ (mock cũ sẽ cho 2); `POST /api/payments/preview` 200 rồi `POST /api/payments` 201 ⇒ payment #7 `PT-2611-00002` CASH 180.000 (khách đưa 200.000, thối 20.000); `activeOrderId: null` ⇒ FE tra `GET /orders?boardingBookingId=6` ra #16 `PAID` ⇒ "Giao thú" BẬT ⇒ hand-over 200 ⇒ #6 `CHECKED_OUT` / `RETURNED`, CM-02 `AVAILABLE`; số hạng "+1 sau 12:00" chưa tách riêng được (L-31, L-50). Trang khách sau trả vẫn "Số đêm 2" và "Tạm tính theo lịch đặt 360.000 ₫" (L-47). **Nút "Giao thú" có lúc không hiện toast và không đóng hộp** (quan sát 14:09, giả thuyết đọc từ mã, không tái hiện được trên BE thật) ⇒ **đã sửa 2/11** ở `CheckoutDialog.tsx`: `handOver.mutate(…, { onSuccess })` → `mutateAsync(undefined).then(…)`, cùng nguyên nhân ở hủy phiên (`abort.mutate` → `mutateAsync`); thêm 2 ca vào `CheckoutDialog.test.tsx` (số 72 test ở mục D chưa đếm lại); mẫu `mutate(…, { onSuccess })` còn ở 7 hộp khác (`CancelBookingDialog`, `ExtendBookingDialog`, `CounterBookingDialog`, `NoKennelDialog`, `DeceasedDialog`, `CareLogForm`, `AddendumForm`), chưa kiểm trang cha có gỡ chúng không (L-49). **`components/ui/sheet.tsx`** (dùng bởi menu trái `StaffShell` và ngăn phải `PetDetailDrawer`) đã sửa: nút X "Close" → "Đóng bảng bên", `SheetOverlay` `forwardRef` + `displayName`, test mới `sheet.test.tsx` 2 ca (L-45). **Hộp "Chi tiết" "Order chờ thu"** (L-44): chiều 2/11 không chạy lại riêng hộp này và chưa có số đo tự chạy mới cho bản sửa đó, giữ nguyên ghi chú ở dưới. **BE-note:** hai tin soạn sẵn cho Hiến tự gửi, chưa gửi: `D:/Do-an/.c2-tmp/final2/msg-be2-dung.md` (Dũng, 15 mục) và `D:/Do-an/.c2-tmp/final2/msg-be1-manh.md` (Mạnh, 9 mục). Cũng chiều nay (ngoài T48): cụm 7 Đ2-0, Đ2-B, Đ2-E chạy thật, Đ2-A bị bộ phân loại quyền chặn (L-51). Ca và số liệu: `test-tay-ghep-api-theo-cum.md` C6-13, C6-15 và khối "Đợt chiều 2/11" ở đầu cụm 6; lỗi: L-45, L-49…L-52 ở file đó.
- **Chưa chạy trên BE thật (sau chiều 2/11):** thú mất / `end-deceased` (`DeceasedDialog`; C6-16, (e)): HẸN 3/11 12:08, sau khi quan sát `PICKUP_OVERDUE` của cụm 7 (chạy sớm làm mất nguồn đó, không hoàn tác), không trước 12:06, phụ thuộc phiên điều phối còn sống (nếu phiên đóng thì phiên sau làm theo memory cụm 6); "Hủy vì hết chuồng" thành công 200 (C6-08); số hạng "+1 đêm sau 12:00" khi ngày ở > 0 (ca chiều 2/11 chỉ chứng minh nhánh ngày ở 0 sau 12:00, L-31). Nhật ký BẤT THƯỜNG thật đã chạy chiều 2/11 (bullet trên), không còn trong danh sách này.
- **Cần báo BE-2 (T42, T43), cập nhật 1/11 và 2/11:** (1) `Booking.activeOrderId` chỉ OPEN/PENDING (đúng hợp đồng A10), null sau khi PAID: FE tự tra Order qua `GET /api/orders?boardingBookingId=` (đã sửa FE); tùy chọn gọn hơn cho BE: thêm trường (vd `latestOrderId` / `paidOrderId`); (2) `CareLogAddendumResponse` chỉ có `createdBy`, đề nghị thêm `createdByName` (tác giả khác hiện "Nhân viên #id"); (3) `CheckoutStarted` không có mã Order (FE hiện "Order #id"); (4) `Booking` thiếu `cancelledAt` / `createdAt` / số tiền chốt / `endNote`; (5) Q1 ảnh nhật ký (hiện dán URL http / https); (6) link thông báo lưu trú: khách `/boarding-bookings/:id`, nhân viên `/admin/boarding`; (7) `check-in-readiness` (#13) không có trường lý do (`canCheckIn = false` do ngày không phân biệt BR-LT-02/03/06): FE tự soạn câu, nếu muốn thì thêm `blockers[]` / `reasons`; (8) mô tả dòng Order lưu trú BE ghi "Lưu trú {loại} · {mã đặt chỗ}", không có số đêm (số đêm nằm ở `quantity` của dòng BOARDING); (9) [2/11] sau trả sớm BE giữ `check_out_date` = ngày trả dự kiến nên hộp "Chi tiết" nhân viên ghi "Số đêm 2" và trang khách ghi "Tạm tính theo lịch đặt 360.000 ₫", trong khi Order thực thu 1 đêm = 180.000 ₫ (cùng gốc với (4); L-47); (10) [2/11] BE không ghi `audit_logs` cho start-checkout và hand-over (có cho `ORDER_PAID` và `ORDER_CHECKOUT_ABORTED`; lời agent; **hạ xuống chỉ ghi nhận chiều 2/11**: có thể đúng chủ ý vì BR-QT-15 không gồm lưu trú, `KennelService:42` ghi "Không audit (BR-QT-15 không gồm lưu trú)" (tự đo: đó là chỗ duy nhất trong module `boarding` nhắc audit; `BoardingCheckoutService` không có comment riêng); L-48). Chiều 2/11 trang khách sau trả sớm cùng ngày vẫn ghi "Số đêm 2" và "Tạm tính 360.000 ₫" (mục (9), thấy lần hai trên đặt chỗ #6). Hai tin BE-note tổng hợp (BE-2: `D:/Do-an/.c2-tmp/final2/msg-be2-dung.md`; BE-1: `D:/Do-an/.c2-tmp/final2/msg-be1-manh.md`) đã soạn, chờ Hiến gửi. Mục `end-maintenance` cũ đã bỏ: BE trả 409 đúng và mock đã sửa. Bảng tóm tắt L-43, L-47, L-48 ở `test-tay-ghep-api-theo-cum.md`.
- **Giao diện:** "Thu tiền tại bàn Lễ tân" dùng `attachOrder`, âm thầm thay phiếu thu đang dở của khách khác; dưới 1024 px phiếu nằm sau tab "Phiếu thu" (chưa tự chuyển tab). `DatePicker` (khách): nút "Hôm nay" bỏ qua `minDate` (form tự chặn). Ngày nghỉ chi nhánh không phải dữ liệu công khai nên FE không khóa trước (BE trả BR-LT-04). Chi nhánh DRAFT (CN2) không tạo được chuồng bằng UI vì `/public/services` rỗng.
- **Mock:** `POST care-logs` đã chặn mục trống như BE (sửa 1/11); `PATCH care-logs` không kiểm đặt chỗ còn trong chuồng; mock PATCH còn trả `MALFORMED_REQUEST` cho `isAbnormal` / `logDate` sai kiểu dù DTO PATCH của BE không có hai trường này; `khach@` không có seed `CHECKED_IN` / `OVERDUE` / thú mất.
- ST15 thật (`BoardingOverdueJob`, T43) và Care Task `PICKUP_OVERDUE` đã có ở BE; mock vẫn mô phỏng ST15 lười, không sinh Care Task và chưa được so hành vi với job thật (comment "BE chưa có (T43)" ở `sweep` / A12 trong mock lỗi thời).
- Cảnh báo console cũ `DialogOverlay` "Function components cannot be given refs" và nút X "Close" của mọi hộp thoại, cùng ở `ui/dialog.tsx` (component dùng chung, ngoài vùng T48): **đã sửa 2/11 (Hiến duyệt; L-39)**: nút X nay tên "Đóng hộp thoại", `DialogOverlay` là `React.forwardRef` + `displayName`, test mới `ui/dialog.test.tsx` 3 ca. `ui/sheet.tsx` (~dòng 78) cùng mẫu: **đã sửa chiều 2/11 (L-45)**: nút X "Close" → "Đóng bảng bên", `SheetOverlay` `forwardRef` + `displayName`, test mới `ui/sheet.test.tsx` 2 ca (lời agent). Còn ghi nhận, chưa sửa: sau nhận thú FE gọi lại `GET …/check-in-readiness` và nhận 409 "Đặt chỗ không còn ở trạng thái BOOKED" ở console, UI không báo (L-46).
- Hộp "Chi tiết" của nhân viên (đo 2/11): hàng "Order lưu trú —" gây hiểu lầm sau khi Order `PAID` (BE chỉ trả `activeOrderId` cho Order OPEN/PENDING); đã đổi nhãn thành "Order chờ thu" và chỉ hiện khi `activeOrderId` khác null (L-44; lời agent, chưa có số đo tự chạy cho riêng bản sửa này).

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
| Trình duyệt (Playwright, mock) | `/admin/kennels`: 1280 / 390 / 360 px, manager1 và manager2 · `/admin/boarding` (bảng): **chỉ 360 px** · dialog Nhật ký trong `/admin/boarding`: 1280 / 390 / 360 px · `/boarding-bookings`, `/new`, `/hotel`: 1280 / 390 / 360 px (bản build + preview) · `/boarding-bookings/:id`: 1280 px (#4, #5, #6, đang lưu trú, ảnh lớn), 360 px, 390 px (#4) · **`stay/`: không mở lần nào trên mock (27/10)**; đã mở trên BE thật 1/11 (chỉ đọc) và 2/11 (có ghi), xem ba hàng "Cụm 6 đợt 1", "Cụm 6 đợt 2" và "Cụm 6 đợt chiều" ngay dưới · MOCK: không (chỉ test). Đo `scrollWidth`: không tràn ở các cỡ đã xem |
| Cụm 6 đợt 1 (1/11, BE thật, chỉ đọc) | Playwright Chrome 1280 px trên BE 8082 + Vite 5173 `VITE_API_MOCK=false`, hai lượt (trước / sau sửa FE; lời agent): `/admin/boarding` ba tab + lọc ngày, hộp Nhận thú, Hủy vì hết chuồng, Trả thú, Thú mất, Nhật ký (mở thật; 360 / 390 px không xem). Tự đo: DB `petcare_seed` trước / sau không đổi (chỉ thêm sessions và audit `LOGIN_SUCCEEDED`); bộ rộng nhắm đích (`boarding*`, `sales`, `cashier`, `report`, `mock-api`, `mock-hybrid`, `seed-dev-phase2`, `pages/admin/boarding`, `AdminBoardingPage*`, `AdminKennelsPage`, `pages/customer/boarding`, `--maxWorkers=2`) **30 file, 992 ca xanh**, `npx tsc --noEmit` 0 lỗi, `npm run lint` (`--max-warnings 0`) 0 lỗi; **KHÔNG chạy vitest toàn dự án** (phiên QA cuối lo), nên số "147 file, 2868 ca" ở hàng trên là của 27/10. Chi tiết: `test-tay-ghep-api-theo-cum.md` cụm 6, `T49-fe.md` mục C.14 |
| Cụm 6 đợt 2 (2/11, BE thật, CÓ ghi) | Playwright Chrome headless 1280 px trên BE 8082 + Vite 5173 `VITE_API_MOCK=false` (mock tắt), 09:45–10:37 trong giờ mở cửa; guard chặn mọi request ghi ngoài allow-list từng bước, `abortedWrites` rỗng ở mọi script (lời agent). Đã chạy: hủy phiên trả thú Mochi (C6-14); nhật ký thường, sửa, bổ sung, thử 6 ảnh (C6-15); chuỗi khách đặt chỗ ONLINE, nhận sớm, nhật ký, bắt đầu trả, thu tiền mặt, giao thú, thăm dò 400 / 409 (C6-05, C6-13, C6-17). Tự đo: DB sau đợt 2 (SELECT 10:36) có 15 Order, 6 phiếu thu, 10 thú, 5 đặt chỗ lưu trú, 5 nhật ký, 2 bản bổ sung; Mochi vẫn `CHECKED_IN`. Test FE sau FE-A / FE-B (sửa `ui/dialog.tsx`, nới ảnh nhật ký): `src/components/ui/dialog.test.tsx` + `src/pages/admin/boarding` + `boarding-care.mock.test.ts` **12 file, 251 ca xanh**, `npx tsc --noEmit` exit 0 (tự đo); số này KHÔNG gồm bản sửa hộp "Chi tiết" (FE-C, L-44: lời agent, chưa đo lại). **KHÔNG chạy vitest toàn dự án** (phiên QA cuối lo), nên "147 file, 2868 ca" ở hàng trên vẫn là số của 27/10. Chi tiết: `test-tay-ghep-api-theo-cum.md` cụm 6 (C6-05, C6-13…C6-17, L-39, L-40, L-44…L-48), `T49-fe.md` mục C.14 (đợt 2) |
| Cụm 6 đợt chiều (2/11, BE thật, CÓ ghi) | Playwright Chrome headless 1280 px hoặc `fetch` có guard theo thân request, BE 8082 + Vite 5173 `VITE_API_MOCK=false` (mock tắt), 13:46–14:13 trong giờ mở cửa, `abortedWrites` rỗng ở mọi script (lời agent). Đã chạy: nhật ký BẤT THƯỜNG thật (C6-15: care_logs #6, `notifications` #4–#6, outbox #35–#38, Mailpit +1 thư); trả thú cùng ngày sau 12:00 trên đặt chỗ #6 (Order #16, 1 đêm = 180.000 ₫), thu tiền mặt (payment #7 `PT-2611-00002`), Giao thú lần hai (C6-13). Tự đo: DB lúc 14:13 có 16 Order, 7 phiếu thu (1.769.000 ₫), 10 thú, 6 đặt chỗ lưu trú (#3 `OVERDUE`, #5 và #6 `CHECKED_OUT`), 6 nhật ký, 2 bản bổ sung, `notifications` max id 6, outbox max id 38, Mailpit 2 thư, ca thu ngân #2 vẫn `OPEN`. Test FE (lời agent): sửa nút "Giao thú" / hủy phiên ở `CheckoutDialog.tsx` thêm 2 ca vào `CheckoutDialog.test.tsx` (đỏ trước sửa, đỏ khi gỡ đúng 2 ca), `stay` + `AdminBoardingPage` **8 file / 152 ca xanh**, `tsc` và `eslint` 0 lỗi; sửa `ui/sheet.tsx` thêm `ui/sheet.test.tsx` 2 ca (đỏ trên mã cũ, đỏ khi gỡ từng bản sửa), `vitest related` **12 file / 151 ca xanh**, `tsc` và `eslint` 0 lỗi (có chạy lại sau đó, chưa rõ do agent hay phiên điều phối). Đây là hai lần chạy khác nhau, không cộng số. **KHÔNG chạy vitest toàn dự án** (phiên QA cuối lo), nên "147 file, 2868 ca" ở hàng trên vẫn là số của 27/10. Chi tiết: `test-tay-ghep-api-theo-cum.md` cụm 6 (khối "Đợt chiều 2/11", C6-13, C6-15, L-45, L-49…L-52); `T49-fe.md` mục C.14 chưa có đoạn riêng cho đợt chiều |
| `npx playwright test` (e2e có sẵn, 27/10) | 13 xanh, 8 bỏ qua (`screens.spec`), **3 đỏ ở `workspaces.spec.ts`** (desk → phòng khám → thu ngân; groomer kéo thẻ; bán lẻ bằng bàn phím: chờ nút "Tiếp nhận" Lucky / "Bắt đầu" Rocky / "Mở ca thu ngân"). Không do T48: spec là kịch bản bàn cũ chưa sửa từ T5 và `T36-fe.md:329` đã ghi "để nguyên"; chưa chạy lại trên bản trước T48 để đối chứng. `branch.spec` (kỳ vọng "0 đặt chỗ lưu trú" khi thêm ngày nghỉ thứ Ba) và `catalog.spec` (loại chuồng) xanh |

Hai lần chạy cả bộ là bằng chứng hỗ trợ (bài học T46–T47: một lần sạch không chứng minh hết chập chờn); cơ chế dọn `setInterval` của GSAP ở `src/test/setup.ts` vẫn là thứ làm kết quả đáng tin. Các lỗi tìm ra bằng trình duyệt hoặc đo, không phải bằng test: nhãn "còn 61 phút" do đồng hồ dialog cũ tới 10 giây (CARELOG, đã sửa); padding hero `/hotel` bị luật CSS không-layer nuốt (CUST-BOOK, đã sửa); `Modal` không có tên truy cập (STAY phát hiện, đã thêm `aria-labelledby`).
