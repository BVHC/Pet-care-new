# Test case FE — T33 (đặt lịch, lịch hẹn của tôi, lễ tân đặt hộ / đổi giờ / hủy, hạn chế đặt online)

> Hợp đồng: `docs/api/appointment-v1.md` #1–9 (UC39, UC40); dữ liệu công khai `branch-v1` #20, `catalog-v1` #19. BR-LH-01…09, BR-KH-05, BR-KH-06, BR-TK-19, BR-CK-02; 03 §3 Lịch hẹn #1, #2, #4, #5, #6. ADR-0045…0049, 0055, 0057. Cập nhật 2026-10-23.
> Cột cuối ghi test tự động tương ứng; "(test sau)" là bước chỉ kiểm tay.

## 1. Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`. Khách đăng nhập ở `/auth/login`, nhân viên ở `/admin/login`. Mật khẩu chung **`Petcare123`**.

| Tài khoản | Role | Thấy gì |
|---|---|---|
| `khach@petcare.vn` | CUSTOMER, hồ sơ #1 Nguyễn Thu Trang | `/booking`, `/appointments` |
| `khachmoi@petcare.vn` | CUSTOMER, hồ sơ còn cờ chờ quyết định liên kết | `/booking` bị thay bằng lời đề nghị liên kết (BR-TK-19); `/appointments` vẫn mở (trống) |
| `reception@store1.vn` | RECEPTIONIST chi nhánh 1 | Menu **Lịch hẹn** (`/admin/appointments`): xem, đặt hộ, đổi giờ, hủy, gỡ hạn chế |
| `manager@store1.vn` | BRANCH_MANAGER chi nhánh 1 | `/admin/appointments` trả 403 trong khung (route chỉ cho RECEPTIONIST theo `staff-menu.ts`). API: xem được lịch chi nhánh nhưng cờ đổi / hủy luôn false (ADR-0048), gỡ được hạn chế (BR-LH-09) — kiểm ở `appointment.mock.test` |

**Cấu hình chi nhánh liên quan** (`branch.mock.ts`): chỉ **PetCare Cầu Giấy** (#1) ACTIVE nên trang đặt lịch chọn sẵn; #2, #3 DRAFT không hiện công khai. Giờ mở cửa thứ Hai–Bảy 08:00–12:00 và 13:30–19:00, Chủ nhật 08:00–12:00. Quota Khám/Tiêm 3, Thẩm mỹ chưa cấu hình (= 1). Quota riêng: thứ Ba tới, 08:00, Khám/Tiêm = 0 (khóa khung). Ngày nghỉ: hôm nay + 20. Dịch vụ bật: Khám tổng quát, Tiêm phòng (Khám/Tiêm), Tắm & sấy, Cắt tỉa lông (Thẩm mỹ), Chuồng chó nhỏ (Lưu trú — không đặt qua lịch hẹn).

**Dữ liệu mẫu lịch hẹn** (`appointment.mock.ts`, ngày tính theo hôm nay, giờ Việt Nam, chi nhánh 1):

| # | Khách | Thú | Dịch vụ | Ngày, giờ | Trạng thái | Dùng để kiểm |
|---|---|---|---|---|---|---|
| 1 | Nguyễn Thu Trang (khach@) | Mochi | Khám tổng quát | hôm nay 11:00 | Đã đặt (online) | Còn dưới 12 giờ: hủy được nhưng là hủy muộn, không đổi giờ online được. Sau 11:30 tự thành **Không đến** |
| 2 | 〃 | Mochi | Tiêm phòng | hôm nay + 3, 09:00 | Đã đặt (online) | Đổi giờ / hủy bình thường. Mochi đã đủ 2 lịch đang chờ (BR-LH-05) |
| 3 | 〃 | Bim | Tắm & sấy | mai 10:00 | Đã đặt (tại quầy), đã đổi 3 lần | Hết lượt đổi (BR-LH-06); khung Thẩm mỹ mai 10:00 hết chỗ |
| 4 | 〃 | Mochi | Khám tổng quát | hôm nay − 13, 10:00 | Hoàn tất | Tab "Đã qua" |
| 5 | 〃 | Bim | Tiêm phòng | hôm nay − 5, 14:00 | Đã hủy — khách hủy, **hủy muộn** | 1 vi phạm BR-LH-09 của khach@ |
| 6 | 〃 | Mochi | Tắm & sấy | hôm nay − 30, 09:00 | Đã hủy — phòng khám hủy ("Chi nhánh nghỉ vào ngày hẹn") | Hiện nguồn hủy CLINIC |
| 7 | Lê Hoàng Long (hồ sơ tại quầy #3, 0905111222, lo\*\*\*@gmail.com) | Lu | Khám tổng quát | hôm nay − 25, 09:00 | Không đến | vi phạm |
| 8 | 〃 | Lu | Tắm & sấy | hôm nay − 15, 16:00 | Đã hủy — lễ tân hủy, hủy muộn | vi phạm |
| 9 | 〃 | Lu | Tiêm phòng | hôm nay − 10, 10:30 | Không đến | vi phạm |
| 10 | 〃 | Lu | Khám tổng quát | hôm nay 08:30 | Đã tiếp nhận | Không còn nút đổi / hủy (409 nếu gọi thẳng) |
| 11 | 〃 | Lu | Tiêm phòng | mai 10:00 | Đã đặt (tại quầy), ghi chú "Tái khám" | |

**Khách đang bị hạn chế đặt online (BR-LH-09):** **Lê Hoàng Long — hồ sơ tại quầy #3** (3 vi phạm #7, #8, #9), từ hôm nay − 10 lúc 11:00 đến hôm nay + 20 lúc 11:00. Hồ sơ này chưa có tài khoản nên chỉ kiểm được phía lễ tân (mục 5). Giao diện khách bị hạn chế kiểm bằng khach@ theo mục 6.

Tôm (#3 của khach@) và Miu (#5, hồ sơ #4) chưa có lịch hẹn nào nên vẫn xóa được (BR-KH-06); đặt lịch cho Tôm xong thì Tôm không xóa được nữa.

**Giờ kiểm tốt nhất:** buổi sáng 08:00–10:30. Dữ liệu tính theo giờ thật: sau 11:30 lịch #1 thành Không đến (ST05 chạy khi đọc), khung đã qua không còn hiện.

**Đưa dữ liệu mock về ban đầu:** DevTools → Application → Local Storage → xóa `petcare-mock-appointments` (và `petcare-mock-pets`, `petcare-mock-customer`, `petcare-mock-branch` nếu đã sửa thú / khách / chi nhánh), rồi F5.

**Chạy với BE thật:** BE đã có appointment-v1 #1–9 (T16, T25), nhưng **chưa có** `GET /api/public/branches` (branch-v1 #20) và `GET /api/public/services` (catalog-v1 #19) — trang `/booking` chưa tải được chi nhánh / dịch vụ trên BE thật. `/appointments` và `/admin/appointments` chạy được.

## 2. Trang

| Trang | Path | Ai mở được |
|---|---|---|
| Đặt lịch khám | `/booking` | CUSTOMER, không còn cờ chờ liên kết |
| Lịch hẹn của tôi | `/appointments` | CUSTOMER |
| Quản lý lịch hẹn | `/admin/appointments` | RECEPTIONIST |

## 3. Đặt lịch online (`/booking`, UC39)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 3.1 | khach@ mở `/booking` | Không còn ô họ tên / SĐT. Dải thông tin là quy định thật: đặt trước 24 giờ, trong 30 ngày, đổi giờ online đến 12 giờ trước hẹn, số chi nhánh nhận đặt lịch | (test sau) |
| 3.2 | Bước 1 | Chỉ thú còn sống: Mochi, Bim, Tôm | (test sau) |
| 3.3 | Bước 2, 3 | PetCare Cầu Giấy chọn sẵn; dịch vụ Khám tổng quát, Tiêm phòng, Tắm & sấy, Cắt tỉa lông; **không** có Chuồng chó nhỏ (BR-LH-01). Giá "Từ X đ" theo danh mục, kèm câu "chi phí thực tế phụ thuộc chỉ định của bác sĩ" (BR-CK-02) | `BookingPage.test` |
| 3.4 | Bước 4: mở lịch | Ngày sớm nhất là ngày của (bây giờ + 24 giờ), xa nhất hôm nay + 30 | (test sau); cửa sổ ở `appointment.mock.test` |
| 3.5 | Tôm + Tắm & sấy + ngày mai | Khung 10:00 ghi **"Hết chỗ"**, không bấm được (BR-LH-03); không có khung bắt đầu trước now + 24 giờ (BR-LH-04) | `BookingPage.test`, `appointment.mock.test` |
| 3.6 | Chọn ngày hôm nay + 20 (ngày nghỉ) | "Chi nhánh không nhận lịch ngày này" | `BookingPage.test` |
| 3.7 | Thứ Ba tới, Khám tổng quát | Khung 08:00 "Hết chỗ" (quota riêng 0) | `appointment.mock.test` |
| 3.8 | Tôm + Khám tổng quát + hôm nay + 3, 10:00 + ghi chú → Xác nhận | Hộp thoại "Đặt lịch thành công": mã `LH-yyMMdd-NNNN`, dịch vụ, thú, chi nhánh, "10:00–10:30, Thứ …"; nút **Xem lịch hẹn của tôi** → `/appointments` | `BookingPage.test` |
| 3.9 | Mochi + bất kỳ dịch vụ / khung | "Thú cưng đã có 2 lịch hẹn đang chờ" (BR-LH-05) | `BookingPage.test` |
| 3.10 | Bim + Cắt tỉa lông + mai 15:00 | "Thú cưng đã có lịch Thẩm mỹ trong ngày này" (BR-LH-05) | `appointment.mock.test` |
| 3.11 | Hai tab cùng chọn một khung còn 1 chỗ; tab 1 đặt trước, tab 2 đặt sau | Tab 2: "Khung giờ bạn chọn vừa hết chỗ…", khung tải lại thành Hết chỗ và bị bỏ chọn | (test sau) |
| 3.12 | `/pets` → đánh dấu Tôm đã mất (sau khi đặt 3.8) | Tôm biến khỏi bước 1; lịch của Tôm ở `/appointments` thành "Phòng khám hủy — Thú cưng đã được ghi nhận là đã mất", không tính hủy muộn (BR-KH-05). Gọi API đặt cho Tôm: "Thú cưng đã mất, không đặt được lịch" | `appointment.mock.test` |
| 3.13 | Khách chưa có thú (tài khoản mới đăng ký) | "Bạn chưa có thú cưng nào để đặt lịch" + nút **Thêm thú cưng** → `/pets` | (test sau) |
| 3.14 | khachmoi@ mở `/booking` | Lời đề nghị liên kết hồ sơ, không có form (BR-TK-19) | (route guard có sẵn) |
| 3.15 | khach@ đang bị hạn chế (mục 6) mở `/booking` | Không có form; khung cảnh báo "Bạn đang tạm ngừng đặt lịch online đến HH:mm dd/MM/yyyy", số lần vi phạm, hướng dẫn gọi chi nhánh, lịch cũ giữ nguyên (BR-LH-09) | `BookingPage.test` |
| 3.16 | Đang mở form thì bị hạn chế (tab khác gây vi phạm thứ 3), bấm đặt | Báo lỗi BR-LH-09 kèm thời điểm hết hạn; trang tải lại tình trạng hạn chế, form ẩn | (test sau) |

## 4. Lịch hẹn của tôi (`/appointments`, UC40)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 4.1 | khach@ mở `/appointments` | Tab **Sắp tới** (lịch Đã đặt, tăng dần theo giờ hẹn): Mochi hôm nay, Bim mai, Mochi + 3. Không còn số thứ tự queue, giá, lịch lưu trú giả | `AppointmentsPage.test` |
| 4.2 | Mochi hôm nay 11:00 | Chỉ có **Hủy lịch**; dòng "Không đổi giờ online được nữa (còn dưới 12 giờ trước giờ hẹn); cần đổi xin liên hệ chi nhánh" | `AppointmentsPage.test` |
| 4.3 | Bim mai | Chỉ có **Hủy lịch**; "… (lịch đã đổi đủ 3 lần) …" | `AppointmentsPage.test` |
| 4.4 | Mochi + 3 → **Đổi giờ** | Hộp thoại nhắc "muốn đổi chi nhánh hoặc dịch vụ, hãy hủy lịch này rồi đặt lại", còn N lần đổi; khung hiện tại ghi "Giờ hiện tại", không chọn được. Chọn 09:30 → thẻ hiện 09:30–10:00, "đã đổi giờ 1/3 lần", **mã giữ nguyên** | `AppointmentsPage.test` |
| 4.5 | Mochi hôm nay → **Hủy lịch** | Cảnh báo "Còn dưới 12 giờ trước giờ hẹn: hủy lúc này được tính là hủy muộn", nhắc quy tắc 3 lần / 90 ngày → 30 ngày. Lý do không bắt buộc. Sau khi hủy thẻ chuyển sang tab **Đã qua / đã hủy**: "Khách hủy lúc …", lý do, "Hủy muộn … tính vào hạn chế" | `AppointmentsPage.test` |
| 4.6 | Mochi + 3 → Hủy | Không có cảnh báo hủy muộn, chỉ lưu ý chung; lịch hủy không ghi hủy muộn | `appointment.mock.test` |
| 4.7 | Tab **Đã qua / đã hủy** | Mới nhất lên đầu: Bim hủy muộn (Khách hủy), Mochi hoàn tất, Mochi "Phòng khám hủy — Chi nhánh nghỉ vào ngày hẹn" | `AppointmentsPage.test` |
| 4.8 | Sau 11:30 mở lại | Mochi hôm nay ở tab Đã qua: **Không đến**, "tính vào hạn chế đặt lịch online" (BR-LH-08) | `appointment.mock.test` |
| 4.9 | Khi đang bị hạn chế | Khung cảnh báo hạn chế ở đầu trang; nút **Đặt lịch mới** ẩn | (test sau) |

## 5. Lễ tân (`/admin/appointments`)

| # | Bước | Kỳ vọng | Test tự động |
|---|---|---|---|
| 5.1 | reception@ mở **Lịch hẹn** | Mặc định hôm nay: Lu 08:30 **Đã tiếp nhận** (không có Đổi giờ / Hủy), Mochi 11:00 **Đã đặt** (có Đổi giờ, Hủy). Lọc trạng thái chỉ có 5 trạng thái thật; không còn nút Check-in / "Bắt đầu khám" (check-in ở T36) | `AdminAppointmentsPage.test` |
| 5.2 | Bấm **Mọi ngày** | Mọi lịch của chi nhánh, giờ kèm ngày | (test sau) |
| 5.3 | Tích "Chỉ lịch đang trong giờ check-in" lúc 10:30–11:30 | Chỉ Mochi 11:00 (A3, BR-TN-02) | `appointment.mock.test` |
| 5.4 | **Đặt lịch hộ** → tìm "Nguyễn Thu Trang" → Chọn → Tôm → Khám tổng quát → hôm nay, khung chưa bắt đầu gần nhất → Đặt lịch | Dòng mới của Tôm, kênh **Đặt tại quầy**; lễ tân thấy cả khung sắp bắt đầu hôm nay | `AdminAppointmentsPage.test`, `appointment.mock.test` |
| 5.5 | Đặt hộ cho Mochi | Vẫn bị "Thú cưng đã có 2 lịch hẹn đang chờ" (BR-LH-05 áp dụng cả khi đặt hộ) | `appointment.mock.test` |
| 5.6 | Đặt hộ: tìm 0905111222 → chọn Lê Hoàng Long (lo\*\*\*@gmail.com) | Khung "Đang bị hạn chế đặt online đến …" nhưng vẫn đặt hộ được | `appointment.mock.test` |
| 5.7 | Mochi 11:00 → **Đổi giờ** sang 11:30 hôm nay | Lễ tân đổi được tới trước giờ hẹn; số lần đổi tăng | `appointment.mock.test` |
| 5.8 | Mochi → **Hủy** | Cảnh báo "hủy hộ lúc này vẫn được tính là hủy muộn của khách"; nguồn hủy **Lễ tân hủy** | `appointment.mock.test` |
| 5.9 | Bấm mắt (Chi tiết) dòng Lu | Chi tiết lịch (GET #4) + khung hạn chế: bắt đầu, hết hạn, 3 lần vi phạm → **Gỡ hạn chế** → để trống lý do → "Vui lòng nhập lý do"; nhập lý do (≤ 300 ký tự) → "Khách không bị hạn chế đặt lịch online." | `AdminAppointmentsPage.test` |
| 5.10 | Gỡ lần nữa từ tab cũ chưa tải lại | "Khách không còn bị hạn chế (đã hết hạn hoặc đã được gỡ)." (409) | `appointment.mock.test` |

## 6. Cho khach@ bị hạn chế để xem giao diện khách

1. Khach@ đã có 1 vi phạm mẫu (#5). Sau 11:30 lịch #1 đã thành Không đến, tính thêm 1 — **số lần cần thêm tùy giờ kiểm**.
2. Lặp lại cho tới khi đủ 3 vi phạm trong 90 ngày:
   - khach@ hủy một lịch còn dưới 12 giờ (vd #1 trước 11:00); hoặc
   - lễ tân **Đặt lịch hộ** cho Bim / Tôm vào khung chưa bắt đầu sớm nhất (còn dưới 12 giờ), rồi **Hủy** ngay.
3. Khach@ mở `/booking` → thấy khung hạn chế (3.15), `/appointments` → banner (4.9).
4. Gỡ: lễ tân mở chi tiết một lịch của Nguyễn Thu Trang → **Gỡ hạn chế**. Lần đánh giá sau chỉ đếm vi phạm sau thời điểm bắt đầu lần hạn chế vừa gỡ (ADR-0055 §7).

## 7. Giả định (ít phát minh nhất) và việc còn để lại

| ID | Giả định / giới hạn | Lý do |
|---|---|---|
| A1 | Các [CFG] trên giao diện (24 giờ, 30 ngày, 12 giờ, 3 lần đổi, 3 vi phạm / 90 ngày → 30 ngày) là hằng số mặc định trong `shared/types/appointment.ts`; BE / mock vẫn quyết định | UC10 (`/api/system-configs`) chỉ ADMIN đọc được |
| A2 | Ngày chọn được của khách: từ ngày của (bây giờ + 24 giờ) đến hôm nay + 30 (theo ngày) | BR-LH-04, ADR-0046 §1 |
| A3 | Tab "Sắp tới" chỉ gồm lịch Đã đặt; Đã tiếp nhận nằm ở "Đã qua / đã hủy". Khách lấy một trang 100 lịch, không phân trang | Yêu cầu T33; khách có ít lịch |
| A4 | Giá dịch vụ hiện "Từ X đ" theo cờ `priceIsFrom` của danh mục, kèm ghi chú chi phí thực tế | BR-CK-02, catalog-v1 `PublicService` |
| A5 | Chuỗi chỉ có 1 chi nhánh ACTIVE thì trang đặt lịch chọn sẵn | Tiện dụng, không đổi rule |
| A6 | Mock chạy ST05 (Không đến sau giờ hẹn + 30 phút, kèm đánh giá hạn chế lúc giờ hẹn + 30 phút) và hủy do thú mất / chuyển chủ **khi đọc**, không có job | BR-LH-08, 03 §3 #5, #6; ADR-0057 |
| A7 | Mock chưa có module lưu trú: BR-LH-09 chỉ đếm vi phạm của lịch hẹn | Lưu trú (UC58) chưa làm ở FE |
| A8 | Gỡ hạn chế không ghi audit trong mock (BE ghi `BOOKING_RESTRICTION_LIFTED`) | Mock audit nằm trong identity-admin.mock, không xuất hàm ghi |
| A9 | Hủy hàng loạt của mock chi nhánh (thêm ngày nghỉ / thu hẹp giờ — BR-LH-10, BR-CN-04) vẫn dùng danh sách lịch mẫu riêng của `branch.mock.ts` (Mochi / Lucky thứ Ba, Miu thứ Năm), **chưa thấy lịch của appointment.mock**. Thêm ngày nghỉ vào ngày có lịch ở đây: khung ngày đó rỗng nhưng lịch vẫn Đã đặt | Nối hai mock làm hỏng test của branch.mock (dữ liệu "hôm nay" trùng ngày test) — để lại |
| A10 | `visitId` của lịch mẫu Đã tiếp nhận / Hoàn tất là số giả | Mock chưa có module visit |
| A11 | Dịch vụ công khai (catalog-v1 #19) mock đặt trong `branch.mock.ts` | Cần cờ bật theo chi nhánh; catalog.mock không đọc ngược branch.mock |
| A12 | Ô chọn ngày dùng `DatePicker` của khu khách cho cả trang lễ tân (trong hộp thoại) | Một SlotPicker dùng chung cho mọi luồng |

## 8. Test tự động

| File | Nội dung |
|---|---|
| `FE/src/shared/api/mock/appointment.mock.test.ts` | 32 case: công khai #20 / #19; khung giờ (cửa sổ khách / lễ tân, FULL, quota 0, ngày nghỉ, lỗi BR-LH-01 / 403 / 404 / 400); đặt lịch (BR-LH-01…05, BR-KH-05, phạm vi); xem (phạm vi, cờ theo vai trò, checkInReady, NO_SHOW); đổi giờ (12 giờ, 3 lần, trùng khung, 409); hủy + hạn chế + gỡ (BR-LH-07, 09) |
| `FE/src/pages/customer/BookingPage.test.tsx` | Đặt thành công có mã; "Hết chỗ"; ngày nghỉ; BR-LH-05; khách bị hạn chế không thấy form |
| `FE/src/pages/customer/AppointmentsPage.test.tsx` | Nút theo cờ; hủy muộn; đổi giờ giữ mã |
| `FE/src/pages/admin/AdminAppointmentsPage.test.tsx` | Danh sách hôm nay, 5 trạng thái, không có check-in; đặt hộ; gỡ hạn chế |

Các test đặt giờ cố định thứ Sáu 23/10/2026 09:00 (giờ Việt Nam) bằng `vi.useFakeTimers({ toFake: ['Date'] })`.
