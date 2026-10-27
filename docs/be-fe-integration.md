# BE đã làm đến đâu và chỗ nào FE ghép được (theo task T)

> **Nguồn:** code trên nhánh `dev` sau khi gộp `origin/dev` (`d065b66`, T39 của BE-1) ngày **28/10/2026**; bảng task `docs/time.md`; hợp đồng `docs/api/*-v1.md` (230 endpoint); kết quả chạy FE với BE thật ở `docs/testcases/T49-fe.md` (§C.3–C.5).
> **Cách kiểm đã dùng:** (1) đọc code BE từng endpoint so với FE (201 endpoint, §C của T49-fe); (2) chạy BE thật ở cổng 8082 trên DB tạm `petcare_seed`, đăng nhập thật 6 vai trò, quét 34 trang, chạy luồng tiếp nhận → khám → tiêm → kê đơn → hoàn tất → thu tiền → chốt ca → đối soát, lưu trú tại quầy, quản trị, khách đăng ký; (3) sau merge: `mvn test` BE 5320/5320, V22 / V23 chạy trên DB có dữ liệu, Care Task chạy thật với FE.
> **Không kiểm:** integration test BE (`*IT`, cần Docker, ~20 phút) chưa chạy sau merge; số liệu IT lấy từ báo cáo của BE-1.

## Ký hiệu

| Ký hiệu | Nghĩa |
|---|---|
| 🟢 | **Ghép được ngay**: BE có API, dữ liệu đủ hoặc tạo được, FE đã chạy thật với BE |
| 🟡 | **Ghép được, cần 1 việc nhỏ**: BE có API nhưng FE chưa thử sâu / cần tạo thêm dữ liệu / còn lệch nhẹ |
| 🔴 | **Chưa ghép được**: BE chưa có endpoint, FE đang chạy mock |
| ⚪ | Không phải việc ghép (tài liệu, hạ tầng, test BE) |
| **L** · **P** · **S** · **M** | Mức FE đã kiểm: **L** chạy luồng trên giao diện với BE thật · **P** mở trang với BE thật, không lỗi · **S** chỉ đối chiếu tĩnh với code BE (khớp) · **M** chỉ mock |

## 1. Tổng quan: BE có bao nhiêu endpoint

**205 / 230 endpoint đã có (89%).** Còn thiếu 25, nằm gọn ở 6 module:

| Module (hợp đồng) | Có / tổng | Thiếu | Ai · task | FE đã chạy thật |
|---|---|---|---|---|
| identity-v1 | 34 / 37 | #25–27 (đổi email / liên kết tài khoản của khách, UC22) | BE-1 | đăng nhập, đăng ký OTP, quản trị NV / tài khoản / [CFG] / mẫu / audit: **L** |
| appointment-v1 | 9 / 9 | — | BE-1 | khách đặt, đổi, hủy: **L**; lễ tân đặt hộ: **S** |
| visit-v1 | 20 / 21 | #21 hồ sơ sức khỏe thú | BE-1 · T28b | tiếp nhận, hàng đợi, bệnh án, tiêm, kê đơn, hoàn tất: **L** |
| care-v1 | 8 / 10 | #9–10 cài đặt nhận thông báo (D025, chờ PO) | BE-1 | thông báo: **P**; Care Task lễ tân: **L** |
| branch-v1 | 21 / 21 | — | BE-2 | **P** (xem), ca thu ngân dùng giờ mở cửa: **L** |
| catalog-v1 | 22 / 22 | — | BE-2 | **P**, seed-dev tạo qua đúng API này |
| customer-v1 | 21 / 21 | — | BE-2 | dùng trong luồng khám: **L**; trang khách: **P** |
| inventory-v1 | 17 / 17 | — | BE-2 | FEFO khi thu tiền: **L**; phiếu nhập / điều chỉnh qua UI: **S** |
| sales-v1 | 19 / 21 | #9 hủy phiên trả thú, #11 chuyển dòng sang mua ngoài | BE-2 · T43, wiring #11 | Order, thu tiền, ca, đối soát: **L** |
| boarding-v1 | 12 / 22 | #13–22 (nhận / trả thú, nhật ký, kiểm điều kiện) | BE-2 · T42, T43 | chuồng, đặt chỗ, gia hạn, hủy: **L** |
| content-v1 | 22 / 22 | — | BE-2 | trang quản trị: **P**; luồng soạn bài / feedback chưa thao tác |
| report-v1 | **0 / 7** | tất cả | BE-2 · T44 | chỉ mock |

**Tác vụ hệ thống (giai đoạn 1: 12):** đã có ST01, ST02, ST03, ST04, ST05 (lịch hẹn + lưu trú), ST06 (trừ kho FEFO khi tiêm / thu tiền; phần giữ hàng đơn online là tầng 3), ST13, ST18, ST19, ST20; **chưa có ST08** (cảnh báo tồn thấp / lô sắp hết hạn, T44) và **ST15** (lưu trú quá hạn đón, T43).

**Việc BE còn dở:** BE-1: T28b (#21), T40 (nối interface chéo, IN_PROGRESS), T41 (E2E BE, chưa bắt đầu), D025. **BE-2: T42, T43, T44, T45 đều `NOT_STARTED` dù hạn 26–29/10, `origin/dung` chưa có commit mới** → đây là rủi ro lớn nhất của buổi demo 30/10 (T50).

## 2. Dữ liệu và database: có sẵn gì, tạo thế nào

**Database tự dựng:** BE chạy Flyway khi khởi động trên DB trống: **V1–V23**, 57 bảng trong DB (gồm bảng Flyway), 52 tham số [CFG], 22 mẫu thông báo, 4 trang nội dung (`page_contents` = `{}`). Chỉ **ADMIN đầu tiên** phải tạo bằng SQL: `FE/scripts/seed-dev/create-admin.sql` (kèm dòng `staff_profiles`). `V22`, `V23` (BE-1) **chưa được BE-2 xác nhận**; BE-2 phải bắt đầu từ `V24`.

**Dữ liệu nền: `FE/scripts/seed-dev`** (chạy qua chính API của BE, từ chối DB không trống): 9 tài khoản nhân viên (`admin`, `ha.nguyen` SUPER_MANAGER, `hung.tran` BRANCH_MANAGER, `mai.le` / `anh.pham` lễ tân, `khoi.vo` / `hang.dang` bác sĩ, `tai.bui` / `lan.ngo` chăm sóc; email `@petcare.local`, mật khẩu `Petcare@2026`), 4 loại sản phẩm, 5 loại vaccine, 8 sản phẩm, 7 dịch vụ (khám, tiêm, 2 thẩm mỹ, 3 loại chuồng), 1 dòng phác đồ chó, 1 chi nhánh `PetCare Tây Hồ` đã kích hoạt (giờ mở cửa, 7 dịch vụ bật, quota), 2 nhà cung cấp, 1 phiếu nhập 10 dòng (có 1 lô sắp hết hạn, 1 lô đã hết hạn), 3 khách tại quầy, 4 thú. **Không có** lịch hẹn, Order, lượt khám, ca thu ngân, lưu trú, bài viết, feedback, Care Task.

**Dữ liệu demo đang có trong DB tạm `petcare_seed`** (cổng 5433, sau các lần chạy thật 27–28/10): 26 tài khoản (thêm nhân viên và khách `khach.thu@` để thử), 4 khách, 6 thú, 3 dòng phác đồ (thêm 2 dòng mèo), 17 lượt khám, 2 bệnh án, 2 mũi tiêm, 2 dòng kê đơn, 18 Order, 3 phiếu thu, 2 ca thu ngân (`RECONCILED`), 4 chuồng, 1 đặt chỗ, 9 lịch hẹn, 1 Care Task, 227 dòng audit. Không có bài viết, feedback. Container `petcare-seed-pg` đang tắt (`docker start petcare-seed-pg`).

**Dữ liệu chưa có và cách tạo (bằng chính nghiệp vụ, không cần SQL):**

| Cần cho | Cách tạo |
|---|---|
| Care Task `FOLLOW_UP_DUE` | khám khách **không có email** (ví dụ Lê Thị Hoa / Miu), ghi bệnh án có `followUpDate` trong cửa sổ `reminder.follow_up_days_before` (3 ngày; [CFG] 1–14) → hoàn tất → job ST04 sinh task (mặc định chạy 07–20h; thử nhanh: đặt `FOLLOW_UP_REMINDER_CRON="30 * * * * *"` khi chạy BE) |
| Care Task `VACCINE_DUE` / `VACCINE_OVERDUE` | tiêm một mũi có `next_due_date` trong cửa sổ `reminder.vaccine_days_before` (7 ngày) hoặc quá hạn `reminder.vaccine_overdue_days` (7 ngày), khách không email; job ST04 / ST18 |
| Lịch hẹn quá hạn (NO_SHOW, hạn chế đặt online) | đặt lịch rồi để qua `appointment.no_show_after_minutes`; ST05 chạy 5 phút một lần |
| Tiêm cho mèo | seed thiếu phác đồ mèo: thêm bằng API catalog (`POST /api/vaccination-protocols`) kèm sản phẩm vaccine gắn loại vaccine mèo |
| Bài viết, chuyên mục, feedback | tạo bằng `/admin/articles`, khách gửi ở `/review` (BR-TK-19: khách phải có hồ sơ liên kết) |
| Lưu trú | `/admin/kennels` tạo chuồng, `/admin/boarding` "Đặt tại quầy". **Ngoài giờ mở cửa, ngày nhận hôm nay bị `BR-LT-04`**: chọn ngày nhận từ ngày mai |
| Tiếp nhận không cấp cứu | chỉ trong giờ mở cửa (`BR-CN-05`); ngoài giờ phải tích cấp cứu |

**Chưa có và nên làm cho demo 30/10:** một **script seed giai đoạn 2** (tiếp nhận, khám, tiêm, thu tiền, lưu trú, Care Task, bài viết) để dựng lại dữ liệu demo bằng một lệnh; hiện dữ liệu demo nằm trong DB tạm và không dựng lại tự động được.

## 3. Theo từng task T

Hạn và trạng thái lấy từ `docs/time.md` (28/10). "API" ghi số thứ tự endpoint trong hợp đồng.

| T | Việc | Ai · hạn · trạng thái | API đã có | Dữ liệu | FE (task · mức kiểm) | Ghép |
|---|---|---|---|---|---|---|
| T1 | Chốt kiến trúc, quy ước | Cả nhóm · 05/10 · IN_PROGRESS | — | — | — | ⚪ |
| T2 | Hợp đồng giữa module | Cả nhóm · 05/10 · DONE | `docs/06`, `docs/api` | — | — | ⚪ |
| T3 | Khởi tạo BE, xác thực 7 role | BE-1 · 06/10 · DONE | đăng nhập, `GET /me`, phiên, phân quyền | 9 tài khoản seed; ADMIN bằng SQL | T5, T12 · **L** (6 vai trò đăng nhập thật). **Login không trả `branchId`** → FE lấy từ `GET /me` | 🟢 |
| T4 | Schema DB + seed nền | BE-2 · 06/10 · DONE | — | V1–V23 tự tạo: 57 bảng, 52 [CFG], 22 mẫu | — | 🟢 |
| T5 | FE khởi tạo, chặn route theo role | FE-1 · 06/10 · DONE | — | — | **L** (route guard 6 vai trò) | 🟢 |
| T6 | Đăng ký, OTP, ST02 | BE-1 · 07/10 · DONE | identity #1–3 | OTP đọc ở Mailpit (dev) | T12 · **L** (đăng ký → OTP → ACTIVE → đăng nhập) | 🟢 |
| T7 | Đăng nhập / xuất, ST01, quên / đổi mật khẩu | BE-1 · 08/10 · DONE | identity #4–9 | seed đã chạy đổi MK lần đầu (BR-TK-17) | T12 · **L** đăng nhập, đổi MK; quên MK qua UI **S** | 🟢 (quên MK 🟡) |
| T8 | Hồ sơ cá nhân, sổ địa chỉ, hồ sơ VET công khai, liên kết hồ sơ | BE-1 · 09/10 · DONE | identity #10–14, #37 | khách đăng ký; hồ sơ quầy trùng SĐT / email để thử BR-TK-19 | T13 · **S** (chưa mở UI thật) | 🟡 |
| T9 | Danh mục: loại SP, SP, DV, loại vaccine, phác đồ | BE-2 · 07/10 · DONE | catalog #1–22 | seed: 4 loại, 8 SP, 7 DV, 5 loại vaccine, 3 phác đồ | T21 · **P**, **S** | 🟢 |
| T10 | Chi nhánh: tạo / kích hoạt, giờ mở cửa, ngày nghỉ | BE-2 · 08/10 · DONE | branch-v1 | 1 chi nhánh ACTIVE, giờ T2–T7, CN | T20 · **P**; sửa giờ / ngày nghỉ **S** | 🟢 xem · 🟡 sửa |
| T11 | Chi nhánh: bật dịch vụ, quota | BE-2 · 09/10 · DONE | branch-v1 | 7 DV bật, quota MEDICAL 3 / GROOMING 2 | T20 · **P** | 🟢 xem · 🟡 sửa |
| T12 | FE: đăng ký, OTP, đăng nhập, mật khẩu | FE-1 · 08/10 · DONE | (T6, T7) | — | **L** | 🟢 |
| T13 | FE: hồ sơ, sổ địa chỉ, liên kết hồ sơ | FE-1 · 09/10 · DONE | (T8) | — | **S** | 🟡 |
| T14 | Quản trị tài khoản nhân viên | BE-1 · 13/10 · DONE | identity #15–24 | 26 tài khoản | T22 · **L** (BRANCH_MANAGER tạo NV không gửi `branchId` → 201; phân trang). `GET /staff` **403 với lễ tân, VET** (BE-note) | 🟢 |
| T15 | [CFG], mẫu thông báo, audit | BE-1 · 14/10 · DONE | identity #28–36 | 52 [CFG], 22 mẫu, 227 dòng audit | T22 · **L** (lọc audit theo loại / hành động + ngày; PUT [CFG] 200) | 🟢 |
| T16 | Lịch hẹn: khung giờ, đặt, đổi, hủy | BE-1 · 16/10 · DONE | appointment #1–9 | 9 lịch | T33 · **L** (khách đặt 201; đổi / hủy → 409 hiện đúng câu); lễ tân đặt hộ **S** | 🟢 (đặt hộ 🟡) |
| T17 | Khách và thú cưng | BE-2 · 13/10 · DONE | customer #1–21 | 4 khách, 6 thú | T23 · **P**, dùng trong luồng khám **L**. Tab "Hồ sơ sức khỏe" 404 (#21) | 🟢 · hồ sơ sức khỏe 🔴 |
| T18 | Kho: NCC, tồn theo lô, phiếu nhập, FEFO | BE-2 · 15/10 · DONE | inventory #1–17 | 2 NCC, 1 phiếu nhập, 10 lô | T24 · **P**; FEFO khi thu tiền **L** (lô hạn sớm trừ trước); nhập / điều chỉnh qua UI **S** | 🟢 · nhập / điều chỉnh 🟡 |
| T19 | Order lõi, dòng snapshot giá | BE-2 · 16/10 · DONE | sales-v1 (19 / 21) | 18 Order, 21 dòng | T34, T35 · **L** (Order sinh từ lượt → `PENDING` → `PAID`) | 🟢 |
| T20 | FE: chi nhánh, giờ, quota | FE-1 · 12/10 · DONE | (T10, T11) | — | **P** | 🟢 |
| T21 | FE: danh mục, loại chuồng, vaccine, phác đồ | FE-1 · 13/10 · DONE | (T9) | — | **P**; PATCH xóa mô tả đã sửa | 🟢 |
| T22 | FE admin: nhân viên, khóa, [CFG], mẫu, audit | FE-1 · 14/10 · IN_PROGRESS | (T14, T15) | — | **L** (đã chạy thật 27/10; nên rà lại lý do task còn ghi IN_PROGRESS ở `docs/time.md`) | 🟢 |
| T23 | FE: khách, thú, hồ sơ sức khỏe | FE-1 · 15/10 · DONE | (T17) | — | **P**; tab hồ sơ sức khỏe 404 | 🟡 (🔴 hồ sơ sức khỏe) |
| T24 | FE: kho | FE-1 · 16/10 · DONE | (T18) | — | **P** + FEFO **L** | 🟢 · nhập / điều chỉnh 🟡 |
| T25 | Lịch hẹn: ST05 NO_SHOW, hạn chế đặt, ST03 nhắc | BE-1 · 19/10 · DONE | job trong BE | cần lịch quá hạn (xem §2) | T33 hiển thị trạng thái: **S** | 🟡 |
| T26 | Tiếp nhận: Visit, hàng đợi, gán / gán lại, gọi lượt | BE-1 · 21/10 · IN_PROGRESS | visit #1–9, #11, `GET /me/queue` | 17 lượt | T36 · **L** (cấp cứu 201, hàng đợi, đưa lên trước, hủy, gọi; ngoài giờ `BR-CN-05` hiện đúng câu). **Lễ tân không đọc được `GET /staff` nên hộp gán người bị chặn** (gán qua API) | 🟡 |
| T27 | Khám: bệnh án, hoàn tất lượt | BE-1 · 22/10 · DONE | visit #10, #12–15 | 2 bệnh án | T37 · **L** (SOAP, cân nặng, hoàn tất, bản bổ sung sau khóa) | 🟢 |
| T28 | Tiêm chủng | BE-1 · 23/10 · DONE | visit #18–20 | 2 mũi; phác đồ chó + mèo (thêm tay) | T37 · **L** (gợi ý, ghi mũi, trừ kho, hết phác đồ, xóa) | 🟢 |
| T28b | Hồ sơ sức khỏe thú | BE-1 · chưa xếp · NOT_STARTED | **chưa có** (visit #21) | — | T23 · tab 404 | 🔴 |
| T29 | Order bán lẻ tại quầy | BE-2 · 19/10 · DONE | sales-v1 (Order bán lẻ) | — | T34 · **P** `/admin/pos`; tạo Order bán lẻ trên UI chưa thao tác | 🟡 |
| T30 | Thu ngân: ca, thu tiền, đối soát, ST13 | BE-2 · 21/10 · DONE | sales-v1 (ca, thu, đối soát) | 3 phiếu thu, 2 ca | T35 · **L** (mở ca ngoài giờ, thu tiền mặt có thối, chốt ca, đối soát `RECONCILED`) | 🟢 (thu thiếu tồn BR-BH-04 chưa thử) |
| T31 | Lưu trú: chuồng, đặt chỗ, gia hạn, hủy | BE-2 · 22/10 · DONE | boarding #1–12, ST05 | 4 chuồng, 1 đặt chỗ | T48 · **L** (tạo / bảo trì chuồng, đặt tại quầy, gia hạn, hủy; trùng mã `BR-LT-01`) | 🟢 |
| T32 | Công khai, bài viết, feedback | BE-2 · 23/10 · DONE | content #1–22, `/api/public/*` | 4 `page_contents` rỗng; 0 bài; 0 feedback | T46, T47 · **P** (trang quản trị); trang công khai **S** | 🟡 |
| T33 | FE: đặt lịch, lịch của tôi | FE-1 · 19/10 · DONE | (T16) | — | **L** | 🟢 |
| T34 | FE: thông báo, bán lẻ (POS) | FE-1 · 20/10 · DONE | care #5–8; sales | 3 thông báo | thông báo **P**; POS **P**; **cài đặt nhận thông báo 404** (care #9–10) | 🟡 · cài đặt 🔴 |
| T35 | FE: thu tiền, mở / chốt ca, đối soát | FE-1 · 21/10 · DONE | (T30) | — | **L** | 🟢 |
| T36 | FE: tiếp nhận, hàng đợi, gọi lượt | FE-1 · 22/10 · DONE | (T26) | — | **L** trừ hộp gán người | 🟢 · gán người 🟡 |
| T37 | FE: khám VET, thẩm mỹ CARETAKER | FE-1 · 23/10 · DONE (mock) | (T27, T28) | — | **L** (khám, tiêm, hoàn tất; thẩm mỹ Bắt đầu / Xong) | 🟢 |
| T38 | Kê đơn, hẹn tái khám | BE-1 · 26/10 · DONE | visit #16–17 | 2 dòng đơn | T46 · **L** (đủ tồn; thiếu tồn → giảm / mua ngoài / hủy; xóa dòng). Thu tiền dòng mua ngoài cần sales #11 | 🟢 · thu mua ngoài 🔴 |
| T39 | Care Task, ST04, ST18, ST19, `FollowUpApi` | BE-1 · 27/10 · DONE (chờ BE-2 xác nhận V22 / V23) | care #1–4; jobs; V22, V23 | 1 Care Task (tạo bằng luồng khám, §2) | T47 · **L** 28/10 (danh sách, "Đã gọi" 200); BRANCH_MANAGER, VET **403** (chỉ lễ tân). Chưa thử hủy, `VACCINE_*` | 🟢 |
| T40 | Nối interface chéo BE, test FSM | BE-1 · 28/10 · IN_PROGRESS | — | — | — | ⚪ |
| T41 | E2E BE luồng chính | BE-1 · 29/10 · NOT_STARTED | — | — | luồng chính đã chạy tay bằng UI ở T49 | ⚪ |
| T42 | Lưu trú: nhận thú, nhật ký | BE-2 · 26/10 · **NOT_STARTED** | **chưa có** (boarding #13–17…) | — | T48 · **M** (dialog nhận thú, nhật ký, khách xem nhật ký) | 🔴 |
| T43 | Lưu trú: trả thú, Order BOARDING, ST15 | BE-2 · 27/10 · **NOT_STARTED** | **chưa có** (boarding #18–22, sales #9, `activeOrderId` luôn null) | — | T48 · **M** | 🔴 |
| T44 | Báo cáo + ST08 | BE-2 · 28/10 · **NOT_STARTED** | **chưa có** (report #1–7) | — | T49 · **M** (trang + dashboard "Đã thu hôm nay" hiện "–" với BE thật) | 🔴 |
| T45 | Test FSM + E2E bán lẻ, lưu trú | BE-2 · 29/10 · NOT_STARTED | — | — | — | ⚪ |
| T46 | FE: kê đơn, trang công khai | FE-1 · 26/10 · DONE (mock) | (T38, T32) | — | kê đơn **L**; trang công khai (`/services`, `/branches`, `/doctors`, `/products`, `/news`) **S** | 🟢 kê đơn · 🟡 công khai |
| T47 | FE: nội dung trang, bài viết, feedback, Care Task | FE-1 · 27/10 · DONE (mock) | (T32, T39) | — | Care Task **L**; nội dung, bài viết, feedback **P** (chưa thao tác) | 🟢 Care Task · 🟡 còn lại |
| T48 | FE: lưu trú | FE-1 · 28/10 · DONE (mock) | (T31) | — | chuồng, đặt chỗ, gia hạn, hủy **L**; nhận / trả thú, nhật ký, khách xem nhật ký **M** | 🟢 · 🔴 nhận / trả / nhật ký |
| T49 | FE: báo cáo + tích hợp API thật | FE-1 · 29/10 · IN_PROGRESS | (T44 chưa có) | — | tích hợp **L**; báo cáo **M** | 🟢 tích hợp · 🔴 báo cáo |
| T50 | Tổng duyệt demo, bàn giao | Cả nhóm · 30/10 · NOT_STARTED | — | cần seed giai đoạn 2 | — | ⚪ phụ thuộc T42–T44 |

## 4. Tóm lại: ghép được ngay / cần việc nhỏ / chưa ghép được

**🟢 Ghép được ngay (đã chạy thật):** đăng nhập, đăng ký OTP, đổi mật khẩu; quản trị nhân viên, tài khoản, [CFG], mẫu thông báo, audit; danh mục, chi nhánh, kho (xem, FEFO); khách đặt / đổi / hủy lịch; bàn Lễ tân (mở ca, tiếp nhận, hàng đợi), bác sĩ (khám, bệnh án, tiêm, kê đơn, hoàn tất), grooming; thu ngân (thu tiền, chốt ca, đối soát); Care Task của lễ tân; lưu trú (chuồng, đặt chỗ tại quầy, gia hạn, hủy); dashboard theo vai trò (trừ "Đã thu hôm nay").

**🟡 Ghép được, cần 1 việc nhỏ (BE đã có API):**
1. **Hộp "Gán người phụ trách" của lễ tân**: BE-1 mở `GET /api/staff` cho lễ tân (hoặc endpoint "nhân viên gán được"). Hiện gán được qua API trực tiếp.
2. **Trang công khai, bài viết, chuyên mục, nội dung trang, feedback** (T32, T46, T47): BE đủ 22 endpoint, FE chưa thao tác thật; cần một buổi chạy thử và tạo bài viết / feedback.
3. **Hồ sơ cá nhân, sổ địa chỉ, liên kết hồ sơ (BR-TK-19)**: chưa mở UI thật với BE.
4. **POS bán lẻ, phiếu nhập kho / điều chỉnh tồn, thu thiếu tồn (BR-BH-04), lễ tân đặt lịch hộ, tiếp nhận từ lịch hẹn**: có API, chưa thao tác thật.
5. **Sửa giờ mở cửa, ngày nghỉ, quota**: chưa thao tác thật.
6. **NO_SHOW / hạn chế đặt online (ST05)**: cần tạo lịch quá hạn.
7. **`e2e/workspaces.spec.ts`**: 3 ca lỗi thời từ T35 (phía FE, không phải BE).

**🔴 Chưa ghép được (BE chưa có, FE chạy mock):**
- **Báo cáo** (T44, report #1–7 + ST08) → `/admin/reports` và thẻ "Đã thu hôm nay".
- **Nhận thú, nhật ký chăm sóc, trả thú, hủy phiên trả thú** (T42, T43, boarding #13–22, sales #9, ST15, `activeOrderId`) → bốn hộp thoại ở `/admin/boarding` và khách xem nhật ký (UC57, UC59, UC60).
- **Hồ sơ sức khỏe thú** (T28b, visit #21) → tab trong chi tiết thú.
- **Cài đặt nhận thông báo** (care #9–10, D025, chờ PO).
- **Chuyển dòng đơn sang mua ngoài khi thu tiền** (sales #11).
- **UC22** (identity #25–27: đổi email / liên kết tài khoản của khách).

## 5. Nên làm gì để kịp demo 30/10 (theo thứ tự ảnh hưởng)

1. **BE-2: T44 → T42 → T43** (báo cáo trước vì FE đã xong và chỉ cần nối; rồi nhận / trả thú). Nếu không kịp, demo bỏ báo cáo thật và phần nhận / trả thú, nói rõ là chạy mock.
2. **BE-2: xác nhận `V22`, `V23`** của BE-1, tự lấy `V24`+ và ADR 0085+; kiểm test của BE-2 dùng `new VaccinationQueryApi() {…}` có vỡ không (T39 thêm hàm trừu tượng).
3. **BE-1: mở `GET /api/staff` cho lễ tân**, rồi T28b, T40, T41.
4. **FE-1:** nối báo cáo ngay khi T44 xong (đối chiếu biên 12 tháng, câu lỗi, thứ tự 403 / 400); chạy thử nhóm 🟡 ở mục 4; sửa 3 ca e2e; **viết seed giai đoạn 2** để dựng dữ liệu demo bằng một lệnh.
5. **Cả nhóm:** `docs/time.md` phải sạch dấu xung đột (origin/dev đang mang dấu xung đột đã commit ở `docs/time.md` và `docs/architecture/system-overview.md`; bản gộp ngày 28/10 đã xóa).

## 6. Dựng môi trường để tự kiểm

```
docker start petcare-seed-pg petcare-mailpit          # DB tạm 5433 (đã có dữ liệu) + Mailpit
cd BE && DB_PORT=5433 DB_NAME=petcare_seed DB_PASSWORD=123456 SERVER_PORT=8082 MAIL_HOST=localhost MAIL_PORT=1025 \
  mvn -B -ntp spring-boot:run -Dmaven.test.skip=true "-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Ho_Chi_Minh -Xmx768m"
cd FE && VITE_API_MOCK=false VITE_API_URL=http://localhost:8082 npx vite --port 5173
```

Cổng **8082** vì Docker Desktop tự bật lại container `petcare-api` cũ ở 8081. Cần ~2,6 GB RAM cho Docker Desktop (đóng game client). Đăng nhập tại `/auth/login` bằng `<tên>@petcare.local` / `Petcare@2026`. Bẫy build và cách dựng lại đầy đủ: `docs/testcases/T49-fe.md` §F.
