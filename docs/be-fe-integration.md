# BE đã làm đến đâu và chỗ nào FE ghép được (theo task T)

> **Nguồn:** code trên nhánh `dev` sau khi gộp `origin/dev` (`d065b66`, T39 của BE-1) ngày **28/10/2026**, rồi gộp tiếp `origin/dev` (`443a6cf`, T42–T44 của BE-2) ngày **30/10/2026**; bảng task `docs/time.md`; hợp đồng `docs/api/*-v1.md` (230 endpoint); kết quả chạy FE với BE thật ở `docs/testcases/T49-fe.md` (§C.3–C.9).
> **Cách kiểm đã dùng:** (1) đọc code BE từng endpoint so với FE (201 endpoint, §C của T49-fe); (2) chạy BE thật ở cổng 8082 trên DB tạm `petcare_seed`, đăng nhập thật 6 vai trò, quét 34 trang, chạy luồng tiếp nhận → khám → tiêm → kê đơn → hoàn tất → thu tiền → chốt ca → đối soát, lưu trú tại quầy, quản trị, khách đăng ký; (3) sau merge: `mvn test` BE 5320/5320, V22 / V23 chạy trên DB có dữ liệu, Care Task chạy thật với FE.
> **Không kiểm:** integration test BE (`*IT`, cần Docker, ~20 phút) chưa chạy sau merge; số liệu IT lấy từ báo cáo của BE-1. **T42, T43, T44 (BE-2, 29–30/10):** số endpoint (223 / 230) đối chiếu với các controller trong code (boarding #13–22, `report` #1–7, sales #9 `abort-checkout`); chưa chạy BE thật với chúng, FE chưa nối thật.

## Ký hiệu

| Ký hiệu | Nghĩa |
|---|---|
| 🟢 | **Ghép được ngay**: BE có API, dữ liệu đủ hoặc tạo được, FE đã chạy thật với BE |
| 🟡 | **Ghép được, cần 1 việc nhỏ**: BE có API nhưng FE chưa thử sâu / cần tạo thêm dữ liệu / còn lệch nhẹ |
| 🔴 | **Chưa ghép được**: BE chưa có endpoint, FE đang chạy mock |
| ⚪ | Không phải việc ghép (tài liệu, hạ tầng, test BE) |
| **L** · **P** · **S** · **M** | Mức FE đã kiểm: **L** chạy luồng trên giao diện với BE thật · **P** mở trang với BE thật, không lỗi · **S** chỉ đối chiếu tĩnh với code BE (khớp) · **M** chỉ mock |

## 1. Tổng quan: BE có bao nhiêu endpoint

**223 / 230 endpoint đã có (97%).** Còn thiếu 7, nằm gọn ở 4 module (identity 3, visit 1, care 2, sales 1):

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
| sales-v1 | 20 / 21 | #11 chuyển dòng sang mua ngoài (#9 hủy phiên trả thú có từ 30/10, T43) | BE-2 · wiring #11 | Order, thu tiền, ca, đối soát: **L** |
| boarding-v1 | 22 / 22 | — (T42 29/10: #13–15, #19–22; T43 30/10: #16–18) | BE-2 · T42, T43 | chuồng, đặt chỗ, gia hạn, hủy: **L**; nhận / trả thú, nhật ký: BE có, FE chưa chạy với BE thật |
| content-v1 | 22 / 22 | — | BE-2 | trang công khai, nội dung trang, chuyên mục + bài viết, feedback: **L** (28/10) |
| report-v1 | 7 / 7 (T44 30/10) | — | BE-2 · T44 | báo cáo + thẻ "Đã thu hôm nay": **M** (BE có, FE chưa chạy với BE thật) |

**Tác vụ hệ thống (giai đoạn 1: 12):** đã có ST01, ST02, ST03, ST04, ST05 (lịch hẹn + lưu trú), ST06 (trừ kho FEFO khi tiêm / thu tiền; phần giữ hàng đơn online là tầng 3), ST13, ST18, ST19, ST20; ST08 (cảnh báo tồn thấp / lô sắp hết hạn, T44 30/10) và ST15 (lưu trú quá hạn đón, T43 30/10).

**Việc BE còn dở:** BE-1: T28b (#21), T40 (nối interface chéo, IN_PROGRESS), T41 (E2E BE, chưa bắt đầu), D025. **BE-2: T42 (29/10), T43 và T44 (30/10) đã `DONE`** (boarding #13–22, sales #9, report #1–7, ST08, ST15); còn T45 (test FSM + E2E bán lẻ, lưu trú, `NOT_STARTED`) và wiring sales #11. Rủi ro lớn nhất của buổi demo 30/10 (T50) nay nằm ở phía FE: nối thật báo cáo (T44) và nhận / trả thú, nhật ký (T42, T43), mới chỉ chạy mock.

## 2. Dữ liệu và database: có sẵn gì, tạo thế nào

**Database tự dựng:** BE chạy Flyway khi khởi động trên DB trống: **V1–V23**, 57 bảng trong DB (gồm bảng Flyway), 52 tham số [CFG], 22 mẫu thông báo, 4 trang nội dung (`page_contents` = `{}`). Chỉ **ADMIN đầu tiên** phải tạo bằng SQL: `FE/scripts/seed-dev/create-admin.sql` (kèm dòng `staff_profiles`). `V22`, `V23` (BE-1) **chưa được BE-2 xác nhận**; BE-2 phải bắt đầu từ `V24`.

**Dữ liệu nền: `FE/scripts/seed-dev`** (chạy qua chính API của BE, từ chối DB không trống): 9 tài khoản nhân viên (`admin`, `ha.nguyen` SUPER_MANAGER, `hung.tran` BRANCH_MANAGER, `mai.le` / `anh.pham` lễ tân, `khoi.vo` / `hang.dang` bác sĩ, `tai.bui` / `lan.ngo` chăm sóc; email `@petcare.local`, mật khẩu `Petcare@2026`), 4 loại sản phẩm, 5 loại vaccine, 8 sản phẩm, 7 dịch vụ (khám, tiêm, 2 thẩm mỹ, 3 loại chuồng), 1 dòng phác đồ chó, 1 chi nhánh `PetCare Tây Hồ` đã kích hoạt (giờ mở cửa, 7 dịch vụ bật, quota), 2 nhà cung cấp, 1 phiếu nhập 10 dòng (có 1 lô sắp hết hạn, 1 lô đã hết hạn), 3 khách tại quầy, 4 thú. **Không có** lịch hẹn, Order, lượt khám, ca thu ngân, lưu trú, bài viết, feedback, Care Task.

**Dữ liệu demo đang có trong DB tạm `petcare_seed`** (cổng 5433, sau các lần chạy thật 27–28/10): 26 tài khoản (thêm nhân viên và khách `khach.thu@` để thử), 4 khách, 6 thú, 3 dòng phác đồ (thêm 2 dòng mèo), 17 lượt khám, 2 bệnh án, 2 mũi tiêm, 2 dòng kê đơn, 18 Order, 3 phiếu thu, 2 ca thu ngân (`RECONCILED`), 4 chuồng, 1 đặt chỗ, 9 lịch hẹn, 1 Care Task, 227 dòng audit. Hiện không có bài viết, chuyên mục, feedback (cụm 8 tạo để thử rồi đã dọn sạch, xem dưới). Container `petcare-seed-pg` đang tắt (`docker start petcare-seed-pg`). **Từ chiều 28/10 DB này có thêm dữ liệu test cụm 3** (tên có `[TEST C3]`): 2 khách, 8 thú (1 thú đã mất, không hoàn tác được), 21 lịch hẹn, 7 lượt tiếp nhận đã hủy kèm Order, 2 bản ghi hạn chế đặt online (khách 1 bị hạn chế tới 27/11), thêm dòng audit. Cụm 5 thêm: ca thu ngân #3–#5 (đã đối soát), phiếu thu `PT-2610-00004…08`, Order 26–37 (khách `[TEST C3]`), bán 8 gói Pate (tồn 120 → 112); không đụng Order demo 17, 18 (vẫn "Chờ thu"). **Cụm 8 (28/10) đã dọn sạch dữ liệu test:** 0 bài, 0 chuyên mục, 0 feedback, 4 `page_contents` `{}`, `feedback.max_per_day` = 5 (đã khôi phục); bài đã xuất bản và feedback không xóa được qua API nên dọn bằng SQL; `audit_logs` còn 2 dòng `SYSTEM_CONFIG_UPDATED` (5 → 1, 1 → 5) và vài `LOGIN_SUCCEEDED` (bảng chỉ ghi thêm). **Không còn bài mẫu cho demo 30/10.** **Cụm 2 (29/10) CHƯA dọn dữ liệu test** (tên có `[TEST C2]`): 5 khách quầy, 4 thú (2 thú đã mất, không hoàn tác được), lượt khám #25 + bệnh án #25 kèm **Order 38 (150.000 đ, "Chờ thu")** làm số "Chờ thu" ở thanh bên tăng 2 → 3 (cùng Order demo 17, 18); 3 phiếu nhập, 4 phiếu điều chỉnh, 1 nhà cung cấp "Ngừng hợp tác", 9 dòng sổ kho (sổ kho không xóa được), 1 dòng audit `PRICE_CHANGED`; chi nhánh và danh mục đã dọn hết. Script dọn đã viết nhưng **chưa chạy**: `D:/Do-an/.c2-tmp/customer/cleanup-dryrun.sql` (chạy thử bằng `BEGIN … ROLLBACK`). Dọn hoặc dựng lại DB trước demo 30/10 nếu không muốn chúng lẫn vào danh sách và báo cáo. Ca thu ngân #6 trong DB (`CLOSED`, chưa đối soát) **không do cụm 2 tạo**: mở 28/10 15:56 giờ VN, tự đóng 29/10 09:49:00 (đúng phút job ST13 `0 4/5` chạy); đối soát hoặc bỏ qua trước demo.

**Dữ liệu chưa có và cách tạo (bằng chính nghiệp vụ, không cần SQL):**

| Cần cho | Cách tạo |
|---|---|
| Care Task `FOLLOW_UP_DUE` | khám khách **không có email** (ví dụ Lê Thị Hoa / Miu), ghi bệnh án có `followUpDate` trong cửa sổ `reminder.follow_up_days_before` (3 ngày; [CFG] 1–14) → hoàn tất → job ST04 sinh task (mặc định chạy 07–20h; thử nhanh: đặt `FOLLOW_UP_REMINDER_CRON="30 * * * * *"` khi chạy BE) |
| Care Task `VACCINE_DUE` / `VACCINE_OVERDUE` | tiêm một mũi có `next_due_date` trong cửa sổ `reminder.vaccine_days_before` (7 ngày) hoặc quá hạn `reminder.vaccine_overdue_days` (7 ngày), khách không email; job ST04 / ST18 |
| Lịch hẹn quá hạn (NO_SHOW, hạn chế đặt online) | đặt lịch rồi để qua `appointment.no_show_after_minutes`; ST05 chạy 5 phút một lần |
| Tiêm cho mèo | seed thiếu phác đồ mèo: thêm bằng API catalog (`POST /api/vaccination-protocols`) kèm sản phẩm vaccine gắn loại vaccine mèo |
| Bài viết, chuyên mục, feedback | tạo bằng `/admin/articles`, khách gửi ở `/review` (BR-TK-19: khách phải có hồ sơ liên kết). Đã chạy thật 28/10 rồi dọn; bài đã xuất bản và feedback không xóa được qua API (`BR-BV-02`), dọn bằng SQL: `docker exec petcare-seed-pg psql -U postgres -d petcare_seed` |
| Lưu trú | `/admin/kennels` tạo chuồng, `/admin/boarding` "Đặt tại quầy". **Ngoài giờ mở cửa, ngày nhận hôm nay bị `BR-LT-04`**: chọn ngày nhận từ ngày mai |
| Tiếp nhận không cấp cứu | chỉ trong giờ mở cửa (`BR-CN-05`); ngoài giờ phải tích cấp cứu |

**Chưa có và nên làm cho demo 30/10:** một **script seed giai đoạn 2** (tiếp nhận, khám, tiêm, thu tiền, lưu trú, Care Task, bài viết) để dựng lại dữ liệu demo bằng một lệnh; hiện dữ liệu demo nằm trong DB tạm và không dựng lại tự động được.

## 3. Kế hoạch ghép theo cụm (bảng theo dõi trạng thái)

**Nguyên tắc: ghép và test theo cụm (luồng nghiệp vụ); bảng theo từng T ở mục 4 chỉ để tra cứu và theo dõi.**
- Task T chia theo tầng và người làm (BE một task, FE một task, ví dụ T26 là BE, T36 là FE của cùng một luồng): kiểm một T riêng không chạy được luồng thật.
- Lỗi và dữ liệu đi xuyên nhiều T. Ví dụ login không trả `branchId` làm hỏng cùng lúc T16, T30, T31, T35, T48; kiểm từng T rời sẽ báo trùng và khó thấy gốc.
- Một kịch bản dữ liệu dùng chung cho cả cụm (thu thiếu tồn cần kê đơn T38 + thu tiền T30 + kho T18): dựng một lần cho cả cụm.
- Chia việc song song dễ: mỗi cụm một agent / một người, ít đụng file của nhau.
- **Ngoại lệ:** khi BE giao thêm endpoint mới (T44 báo cáo, T42 / T43 nhận–trả thú, T28b hồ sơ sức khỏe, care #9–10, sales #11, identity #25–27) FE đã xong sẵn nên chỉ cần **kiểm riêng từng T** (bước 2–6 bên dưới), không cần gom cụm.

**Test tay:** kịch bản từng cụm (mã ca `C1-01`…`C9-02`) ở **`docs/testcases/test-tay-ghep-api-theo-cum.md`**. Test case theo từng T (chạy trên mock, viết ở T12–T49) vẫn ở `docs/testcases/T*-fe.md`.

**Quy trình mỗi cụm (6 bước):**
1. Bật BE thật trên DB tạm, dựng dữ liệu của cụm (mục 2).
2. Chạy các ca của cụm trong file test tay, giữ DevTools → Network lọc `/api/` để thấy mọi 4xx / 5xx.
3. Ghi từng lệch và phân loại: **FE** / **BE** / **DỮ LIỆU** / **GIỜ MỞ CỬA** / **BE-note đã biết**.
4. Sửa lỗi FE kèm test; nếu mock đang che lỗi thì chỉnh mock cho giống BE; lỗi BE chỉ ghi BE-note (không sửa BE).
5. Chạy `tsc`, lint, vitest (`--maxWorkers=4`) và chạy lại các ca đã lỗi.
6. Cập nhật bảng dưới đây, cột "Ghép" của các T trong cụm ở mục 4 và `docs/testcases/T49-fe.md`.

**Ký hiệu trạng thái:** ⬜ chưa bắt đầu · 🔄 đang làm · ✅ xong (ghi ngày) · 🟡 xong một phần · 🔴 chờ BE.

| Ưu tiên | Cụm | Gồm T | Trạng thái (28/10) | Còn lại | Chờ BE | Mục tiêu (đề xuất) | Xong ngày | Test tay |
|---|---|---|---|---|---|---|---|---|
| **1** | **3. Đặt lịch → tiếp nhận** | T16, T25, T26, T33, T36 | 🟡 (28/10: chạy thật API + UI tự động ✔ cho cả 11 ca `C3-01…C3-11`, riêng ngày nghỉ + thú mất chỉ API; chưa có lệch FE) | bạn tick các ca giao diện; hộp gán người chờ BE-1 | BE-1: `GET /staff` cho lễ tân | 28/10 | | `C3-01…C3-11` |
| **2** | **5. Thu ngân và bán lẻ** | T19, T29, T30, T34 (POS), T35 | 🟡 (28/10: 9 ca `C5-01…C5-09` chạy thật API + UI tự động ✔, gồm thu tiền, ca, đối soát, BR-BH-04, BR-BH-05; 1 lỗi FE đã sửa) | bạn tick các ca giao diện; ca ngoài giờ (sau 19:00); mua ngoài khi thu (sales #11) | BE-2: sales #11 (mua ngoài khi thu); #9 abort-checkout đã có từ 30/10 (T43), FE chưa chạy thật (cụm 6) | 28/10 | | `C5-01…C5-09` |
| **3** | **8. Nội dung công khai** | T32, T46 (công khai), T47 (bài viết, feedback, nội dung trang) | 🟡 (28/10: 6 ca `C8-01…C8-06` chạy thật API + UI tự động ✔, gồm 11 trang công khai, nội dung trang, chuyên mục + bài viết, feedback NEW → SEEN → RESOLVED, giới hạn mỗi ngày; chưa có lệch FE) | bạn tick các ca giao diện; (tùy chọn) đưa cấu trúc JSON nội dung trang (Q1) vào hợp đồng, báo BE-2 | — | 28/10 | | `C8-01…C8-06` |
| 4 | 2. Nền: danh mục, chi nhánh, kho, khách | T9–T11, T17, T18, T20, T21, T23, T24 | 🟡 (29/10: 9 ca `C2-01…C2-09` chạy thật API + UI tự động ✔, gồm danh mục, giờ mở cửa, ngày nghỉ, quota, tồn theo lô, phiếu nhập, điều chỉnh tồn, khách quầy, thú, thú mất; 8 lỗi FE + 1 mock lệch BE đã sửa 30/10, chưa commit) | bạn tick các ca giao diện; tab hồ sơ sức khỏe 404 chờ T28b; SĐT đã chốt 10 số (30/10); BE còn nhận 11 số, BE-note cho BE-2; badge "sắp hết hạn" ở bảng tồn đã duyệt, đang bổ sung (30/10); dọn dữ liệu `[TEST C2]` trước demo | BE-1: T28b (hồ sơ sức khỏe) | 29/10 | | `C2-01…C2-09` |
| 5 | 1. Định danh và quản trị | T3, T6–T8, T12–T15, T22 | 🟡 (đăng nhập, đăng ký, quản trị ✅ 27/10) | hồ sơ cá nhân; sổ địa chỉ; liên kết hồ sơ BR-TK-19; quên mật khẩu qua UI | BE-1: UC22 (identity #25–27) | 29/10 | | `C1-01…C1-15` |
| 6 | 4. Khám → tiêm → kê đơn → hoàn tất | T27, T28, T37, T38, T46 (kê đơn) | ✅ 27/10 (chạy thật) | hồi quy sau mỗi lần sửa; thử phác đồ mèo | — | — | 27/10 | `C4-01…C4-08` |
| 7 | 6. Lưu trú | T31, T48 (+ T42, T43) | 🟡 (chuồng, đặt chỗ, gia hạn, hủy ✅ 27/10; nhận / trả thú, nhật ký: BE đã giao 29–30/10, FE chưa nối thật) | khách đặt chỗ; nối thật và chạy nhận / trả thú, nhật ký, hủy phiên trả (boarding #13–22, sales #9, `activeOrderId`); đối chiếu mock T48 #13–22 / sales #9 (lúc viết là ASSUMPTION) với BE | — (BE-2 đã giao T42 29/10, T43 30/10; việc còn lại là phía FE) | khi FE nối thật | | `C6-01…C6-05` |
| 8 | 7. Care Task và nhắc | T39, T47 (Care Task), thông báo T34 | 🟡 (Care Task list + "Đã gọi" ✅ 28/10) | hủy Care Task; `VACCINE_DUE` / `VACCINE_OVERDUE`; hủy khi thú mất / chuyển chủ; email + thông báo trong app | 🔴 BE-1: care #9–10 (cài đặt nhận thông báo, D025) | 29/10 | | `C7-01…C7-08` |
| 9 | 9. Báo cáo | T44, T49 | 🟡 chờ ghép (UI + mock xong; BE đã giao T44 30/10, FE chưa nối thật) | nối báo cáo thật, đối chiếu biên 12 tháng, thứ tự 403 / 400, câu lỗi, tên chi nhánh / dịch vụ, cách tính doanh thu (mock khác BE) | — (BE-2 đã giao T44 30/10: report #1–7, ST08) | khi FE nối thật | | `C9-01…C9-02` |

**Nhật ký tiến độ** (thêm một dòng mỗi lần xong một cụm):

| Ngày | Cụm | Kết quả | Lỗi tìm thấy / sửa |
|---|---|---|---|
| 27–28/10 | 1, 4, 5 (phần thu tiền), 6 (phần có BE), 7 (Care Task), 2 (xem + FEFO) | chạy thật BE 8082: quét 34 trang × 5 vai trò + luồng chính, `docs/testcases/T49-fe.md` §C.3–C.5 | login không trả `branchId` (nặng nhất); BM tạo NV gửi `branchId: 0`; thiếu phân trang; PATCH mô tả; ngày giờ máy; 2 lỗi FE nhẹ |
| 28/10 | 3 (một phần) | chạy thật BE 8082 + FE 5173 trên DB seed: API 11 ca + giao diện tự động bằng Playwright (đặt hộ, đổi / hủy, hạn chế + gỡ, vãng lai ngoài giờ, check-in lịch hẹn đúng hạn / trễ / sớm / nghỉ trưa, NO_SHOW thật 14:06, hộp gán người). Dữ liệu `[TEST C3]`, xem đầu mục Cụm 3 ở file test tay | **không có lệch FE ↔ BE cần sửa FE** (không đổi code FE; vitest 15 file / 196 ca xanh). BE-note: `GET /api/staff` 403 với lễ tân (2–3 lần mỗi lần mở quầy, hộp gán người vẫn khóa); BE cần Redis (`petcare-redis`) bật mới khởi động sạch; hạn chế đặt online do 3 lần hủy muộn (không cần đợi NO_SHOW). Lệch tài liệu: kế hoạch ghi "không có trình duyệt tự động", thực tế FE có sẵn Playwright + Chrome |
| 28/10 | 5 (thu ngân và bán lẻ) | chạy thật BE 8082 + FE 5173: API ba đợt (lỗi trước khi mở ca; thu tiền, thiếu tồn, hủy; chốt ca, đối soát khớp và lệch) và giao diện tự động (mở ca, bán lẻ, thu tiền mặt có thối, thu gộp chuyển khoản, thiếu tồn, hủy chưa chốt, chốt ca, hủy khách không thanh toán, đối soát). Dữ liệu: ca #3–#5, `PT-2610-00004…08`, Order 26–37, bán 8 gói Pate (tồn 120 → 112) | **1 lỗi FE đã sửa (hai lối vào, cùng gốc):** quản lý (BRANCH_MANAGER) ở `/staff/reception` thấy khung "Bán lẻ" có ô tìm khách và nút "Khách hàng", nhưng BE trả 403 cho mọi endpoint khách (`GET /api/customers` chỉ lễ tân, bác sĩ, người chăm; tạo / sửa chỉ lễ tân) và `POST /api/orders` chỉ lễ tân, nên tìm không ra gì, im lặng. Nay `RetailOrderPanel` chỉ hiện lời nhắc và `ReceptionWorkspacePage` ẩn nút "Khách hàng" với vai trò khác lễ tân (`RetailOrderPanel.tsx`, `ReceptionWorkspacePage.tsx` và hai file test). Mock khách đã đúng vai trò (`LOOKUP`): lỗi là UI mở lối vào cho quản lý và thiếu test vai trò quản lý. BE-note: sales #9 `abort-checkout` và #11 `move-to-external-purchase` 404 (BE-2); `GET /api/reports/revenue` 404 ở mọi trang quản lý (T44); `difference` của ca chỉ có sau đối soát. Ghi nhận, chưa sửa: mock `cancel-unpaid` kiểm BOARDING trước lý do rỗng và dùng rule `BR-LT-09` (BE kiểm lý do trước, rule `BR-BH-05`; chỉ xảy ra khi có T42 / T43); E2E `e2e/workspaces.spec.ts` còn bấm nút "Mở ca thu ngân" đã cũ |
| 28/10 | 8 (nội dung công khai) | chạy thật BE 8082 + FE 5173 trên DB seed: API + giao diện tự động bằng Playwright 6 ca `C8-01…C8-06` (11 trang công khai; sửa 4 khóa nội dung trang; chuyên mục + bài nháp → xuất bản → ẩn → đăng lại, lọc HTML, phân trang `/news` với 16 bài; khách gửi feedback; BM / SM xử lý feedback NEW → SEEN → RESOLVED; `feedback.max_per_day` 5 → 1 → 5). Số đo thật: 2 bác sĩ, 7 dịch vụ, 1 chi nhánh, sản phẩm hiển thị 7/8. **Dữ liệu test đã dọn sạch:** 0 bài, 0 chuyên mục, 0 feedback, 4 `page_contents` `{}` (không còn bài mẫu cho demo 30/10) | **không có lệch FE ↔ BE cần sửa FE** (không đổi code FE nên không chạy lại tsc / lint / vitest). BE-note: `PUT` nội dung trang chỉ kiểm "là object" và ≤ 64 KB, không kiểm cấu trúc (`{"foo":1}` cho ABOUT vẫn 200, trang công khai lặng lẽ về mặc định cả khóa; Q1 chưa vào hợp đồng, chưa báo BE-2); tên chuyên mục phân biệt hoa / thường; thông điệp 409 dùng tên entity tiếng Anh ("Article", "Feedback"); bài đã từng xuất bản và feedback không xóa được qua API (`BR-BV-02`) nên dọn bằng SQL. Ghi nhận ngoài BE: modal chung `components/ui/modal-templates.tsx` không đóng bằng Esc, không chuyển focus vào hộp thoại (mọi hộp thoại); `/help` FAQ cứng, không gọi API; `/about` hiện "50+ Bác sĩ" mặc định (thật 2), footer cứng hotline 1900 1234 và địa chỉ "123 Nguyễn Trãi, Quận 1" (chi nhánh thật ở Tây Hồ, Hà Nội). BR-TK-19 chỉ kiểm bằng cờ pending dựng bằng SQL; luồng thật thuộc ca C1-15 |
| 29–30/10 | 2 (nền) | chạy thật BE 8082 trên DB `petcare_seed` + FE `VITE_API_MOCK=false` ở cổng **5174** (không dùng 5173 vì không rõ chế độ): API + giao diện tự động bằng Playwright 9 ca `C2-01…C2-09` (ba mảng: chi nhánh, kho, khách), mỗi ca hàng chục đến hàng trăm response từ 8082, không ca nào abort. **CORS:** BE chỉ cho origin `http://localhost:5173` và `http://localhost:3000`, nên FE ở 5174 trượt preflight; Playwright mở Chrome với `--disable-web-security` (request vẫn đi thật tới 8082); chạy tay dùng cổng 5173. Đạt: mô tả rỗng (để trống → không gửi `description`; xóa trắng → gửi `""`), danh mục (số dòng UI = API = DB), giờ mở cửa / ngày nghỉ / dịch vụ / quota (hiệu lực từ ngày mai, `BR-CN-04`), tồn theo lô, phiếu nhập (trùng lô + hạn thì cộng vào lô cũ, nhà cung cấp ngừng hợp tác bị chặn, hủy phiếu `BR-KO-04`), điều chỉnh tồn (vượt tồn bị từ chối), khách quầy + thú (`BR-KH-10`, `BR-KH-06`, `BR-KH-03`), thú mất (lịch và đặt chỗ lưu trú `BOOKED` → `CANCELLED` không tính hủy muộn, Care Task `OPEN` → `CANCELLED`, thông báo EMAIL + IN_APP đã gửi, đối chiếu SQL), tab hồ sơ sức khỏe 404 (T28b, đã biết). Vai trò: HTTP khớp `@PreAuthorize`, không có lối vào chết (chi nhánh: SM chỉ xem, BM sửa; danh mục chỉ SM; kho: BM 4 tab, lễ tân chỉ "Tồn kho"; khách: chỉ lễ tân tạo / sửa). Khôi phục đã đo lại bằng SQL: giờ mở cửa, quota, ngày nghỉ, tồn 8 SP demo đúng như trước. Dữ liệu `[TEST C2]` **chưa dọn**, xem mục 2 | **8 lỗi FE + 1 mock che lỗi, đã sửa 30/10 kèm test (K1–K9, chưa commit).** K1–K3 `shouldRetry` / `QueryClient`: `shouldRetry` chỉ nhận `ApiError` mà lỗi thật là `AxiosError`, nên 4xx bị thử lại 3 lần (tab "Hồ sơ sức khỏe" 404 treo 7,4 giây; mọi `useQuery` bị ảnh hưởng); ~13 hook gọi `shouldRetry(count, toApiError(error))` nên lỗi mạng (status 0) không bao giờ retry; `QueryClient` của `App.tsx` không đặt `retry` (`shared/constants/realtime.ts`, `app/App.tsx`). K4 panel tìm khách quầy: gõ đủ 10 số rồi xóa bớt, dòng khách cũ vẫn hiện (có thể mở nhầm hồ sơ), không gửi `size` (BE cắt ở 20) (`CustomerSearchPanel.tsx`). K5 `useCustomerLookup` (4 nơi: bán lẻ, chuyển chủ, đặt hộ, panel khách) giữ dữ liệu cũ khi truy vấn tắt, không gửi `size` (`useCustomerPet.ts`). K6 phiếu điều chỉnh cho chọn 2 dòng cùng lô, BE luôn 400 `lotsDistinct` (`AdjustmentForm.tsx`). K7 phiếu nhập nháp giữ NCC đã ngừng hợp tác, lưu / xác nhận luôn 400 (`ReceiptForm.tsx`, `ReceiptsTab.tsx`, `modal-templates.tsx` thêm prop `submitDisabled`). K8 ô chọn lô giữ tồn cũ sau khi BE từ chối (`useInventory.ts`, làm mới ở `onSettled`). K9 mock kho dễ dãi hơn BE: cộng dồn lô trùng, câu `BR-KO-02` / `BR-KO-06` khác (`inventory.mock.ts`). Kiểm FE (tsc / lint / vitest): tsc sạch (exit 0); lint sạch (exit 0, --max-warnings 0); vitest toàn bộ 163 file / 3279 ca xanh, chạy 3 lần liền đều exit 0, không dính lỗi chập chờn GSAP. BE-note: CORS mặc định chỉ 5173 và 3000; `POST /vaccination-protocols` nhận mũi 3 khi chưa có mũi 2 (201); `addHoliday` thêm được ngày **hôm nay** (hủy cả ngày, không hoàn tác) còn `deleteHoliday` chỉ xóa từ ngày mai; không có DELETE cho phiên bản giờ mở cửa, phác đồ, dịch vụ (chỉ ngừng); hai đường cân nặng không đồng nhất (khách `4.555` → 400, bác sĩ → 201 làm tròn 4,56); lỗi 400 chạy trước 403; BE giới hạn `size` tối đa 100. Lệch tài liệu (đã sửa lời ca): lễ tân không có đường ghi cân nặng; cảnh báo lô chỉ cho lô **đã** hết hạn; cảnh báo số lịch ảnh hưởng chỉ hiện khi ≥ 1; bỏ vế bàn khám mèo (là C4-11). Đã chốt 10 số (30/10), sửa tài liệu; BE còn nhận 10–11, BE-note cho BE-2. Ghi nhận, chưa sửa: badge "sắp hết hạn" ở bảng tồn đã duyệt, đang bổ sung (30/10); modal chung không đóng bằng Esc; `main.tsx` có `QueryClient` thứ hai `retry: 1` vô dụng; còn lại ở bảng ghi lỗi `L-08…L-15` của file test tay và `T49-fe.md` mục C.9. **Dữ liệu `[TEST C2]` còn lại:** 5 khách quầy, 4 thú (2 thú đã mất, không hoàn tác), **Order 38 (150.000 đ, "Chờ thu")** làm số "Chờ thu" ở thanh bên tăng 2 → 3, 3 phiếu nhập + 4 phiếu điều chỉnh + 1 NCC "Ngừng hợp tác" + 9 dòng sổ kho, 1 dòng audit `PRICE_CHANGED`; script dọn `D:/Do-an/.c2-tmp/customer/cleanup-dryrun.sql` chưa chạy. Chạy lại ca đã sửa trên BE thật 30/10 (R1–R6, chỉ đọc, 0 request ghi): đạt hết, ô nhập trống không bắn truy vấn khách, 4xx không retry (lỗi hiện sau dưới 0,4 s), 5xx và lỗi mạng retry 4 lần |
| | | | |

## 4. Theo từng task T (cột "Cụm" xem mục 3)

Hạn và trạng thái lấy từ `docs/time.md` (28/10; T42–T44 cập nhật 30/10). "API" ghi số thứ tự endpoint trong hợp đồng.

| T | Cụm | Việc | Ai · hạn · trạng thái | API đã có | Dữ liệu | FE (task · mức kiểm) | Ghép |
|---|---|---|---|---|---|---|---|
| T1 | — | Chốt kiến trúc, quy ước | Cả nhóm · 05/10 · IN_PROGRESS | — | — | — | ⚪ |
| T2 | — | Hợp đồng giữa module | Cả nhóm · 05/10 · DONE | `docs/06`, `docs/api` | — | — | ⚪ |
| T3 | 1 | Khởi tạo BE, xác thực 7 role | BE-1 · 06/10 · DONE | đăng nhập, `GET /me`, phiên, phân quyền | 9 tài khoản seed; ADMIN bằng SQL | T5, T12 · **L** (6 vai trò đăng nhập thật). **Login không trả `branchId`** → FE lấy từ `GET /me` | 🟢 |
| T4 | — | Schema DB + seed nền | BE-2 · 06/10 · DONE | — | V1–V23 tự tạo: 57 bảng, 52 [CFG], 22 mẫu | — | 🟢 |
| T5 | 1 | FE khởi tạo, chặn route theo role | FE-1 · 06/10 · DONE | — | — | **L** (route guard 6 vai trò) | 🟢 |
| T6 | 1 | Đăng ký, OTP, ST02 | BE-1 · 07/10 · DONE | identity #1–3 | OTP đọc ở Mailpit (dev) | T12 · **L** (đăng ký → OTP → ACTIVE → đăng nhập) | 🟢 |
| T7 | 1 | Đăng nhập / xuất, ST01, quên / đổi mật khẩu | BE-1 · 08/10 · DONE | identity #4–9 | seed đã chạy đổi MK lần đầu (BR-TK-17) | T12 · **L** đăng nhập, đổi MK; quên MK qua UI **S** | 🟢 (quên MK 🟡) |
| T8 | 1 | Hồ sơ cá nhân, sổ địa chỉ, hồ sơ VET công khai, liên kết hồ sơ | BE-1 · 09/10 · DONE | identity #10–14, #37 | khách đăng ký; hồ sơ quầy trùng SĐT / email để thử BR-TK-19 | T13 · **S** (chưa mở UI thật) | 🟡 |
| T9 | 2 | Danh mục: loại SP, SP, DV, loại vaccine, phác đồ | BE-2 · 07/10 · DONE | catalog #1–22 | seed: 4 loại, 8 SP, 7 DV, 5 loại vaccine, 3 phác đồ | T21 · **L** (29/10: giao diện danh mục; số dòng UI = API = DB; tạo / sửa / ngừng / xóa dòng thử; mô tả rỗng; tên trùng → 400 hiện đúng câu; chỉ SUPER_MANAGER) | 🟢 |
| T10 | 2 | Chi nhánh: tạo / kích hoạt, giờ mở cửa, ngày nghỉ | BE-2 · 08/10 · DONE | branch-v1 | 1 chi nhánh ACTIVE, giờ T2–T7, CN | T20 · **L** (29/10: sửa giờ mở cửa hiệu lực từ ngày mai, thêm / xóa ngày nghỉ kèm xem trước số lịch ảnh hưởng; nhập hôm nay → `BR-CN-04`) | 🟢 |
| T11 | 2 | Chi nhánh: bật dịch vụ, quota | BE-2 · 09/10 · DONE | branch-v1 | 7 DV bật, quota MEDICAL 3 / GROOMING 2 | T20 · **L** (29/10: bật / tắt dịch vụ, quota mặc định và quota riêng khung; FE chặn -1 và 40000; đã khôi phục) | 🟢 |
| T12 | 1 | FE: đăng ký, OTP, đăng nhập, mật khẩu | FE-1 · 08/10 · DONE | (T6, T7) | — | **L** | 🟢 |
| T13 | 1 | FE: hồ sơ, sổ địa chỉ, liên kết hồ sơ | FE-1 · 09/10 · DONE | (T8) | — | **S** | 🟡 |
| T14 | 1 | Quản trị tài khoản nhân viên | BE-1 · 13/10 · DONE | identity #15–24 | 26 tài khoản | T22 · **L** (BRANCH_MANAGER tạo NV không gửi `branchId` → 201; phân trang). `GET /staff` **403 với lễ tân, VET** (BE-note) | 🟢 |
| T15 | 1 | [CFG], mẫu thông báo, audit | BE-1 · 14/10 · DONE | identity #28–36 | 52 [CFG], 22 mẫu, 227 dòng audit | T22 · **L** (lọc audit theo loại / hành động + ngày; PUT [CFG] 200) | 🟢 |
| T16 | 3 | Lịch hẹn: khung giờ, đặt, đổi, hủy | BE-1 · 16/10 · DONE | appointment #1–9 | 9 lịch | T33 · **L** (khách đặt 201; đổi / hủy → 409 hiện đúng câu); lễ tân đặt hộ **L** (28/10: UI tự động, chờ bạn tick) | 🟢 |
| T17 | 2 | Khách và thú cưng | BE-2 · 13/10 · DONE | customer #1–21 | 4 khách, 6 thú | T23 · **L** (29/10: khách quầy, thú, tra cứu, cảnh báo trùng `BR-KH-10`, xóa thú, thú mất `BR-KH-05`), dùng trong luồng khám **L**. Tab "Hồ sơ sức khỏe" 404 (#21) | 🟢 · hồ sơ sức khỏe 🔴 |
| T18 | 2 | Kho: NCC, tồn theo lô, phiếu nhập, FEFO | BE-2 · 15/10 · DONE | inventory #1–17 | 2 NCC, 1 phiếu nhập, 10 lô | T24 · **L** (29/10: tồn theo lô, phiếu nhập nháp → xác nhận → hủy, điều chỉnh tồn); FEFO khi thu tiền **L** (lô hạn sớm trừ trước) | 🟢 |
| T19 | 5 | Order lõi, dòng snapshot giá | BE-2 · 16/10 · DONE | sales-v1 (20 / 21; #9 từ T43) | 18 Order, 21 dòng | T34, T35 · **L** (Order sinh từ lượt → `PENDING` → `PAID`) | 🟢 |
| T20 | 2 | FE: chi nhánh, giờ, quota | FE-1 · 12/10 · DONE | (T10, T11) | — | **L** (29/10: sửa giờ, ngày nghỉ, dịch vụ, quota; SM chỉ xem, BM sửa, vai trò khác 403) | 🟢 |
| T21 | 2 | FE: danh mục, loại chuồng, vaccine, phác đồ | FE-1 · 13/10 · DONE | (T9) | — | **L** (29/10: giao diện danh mục, loại vaccine, phác đồ, dịch vụ); PATCH xóa mô tả đã sửa | 🟢 |
| T22 | 1 | FE admin: nhân viên, khóa, [CFG], mẫu, audit | FE-1 · 14/10 · IN_PROGRESS | (T14, T15) | — | **L** (đã chạy thật 27/10; nên rà lại lý do task còn ghi IN_PROGRESS ở `docs/time.md`) | 🟢 |
| T23 | 2 | FE: khách, thú, hồ sơ sức khỏe | FE-1 · 15/10 · DONE | (T17) | — | **L** (29/10: khách quầy, thú, thú mất); tab hồ sơ sức khỏe 404 | 🟢 · hồ sơ sức khỏe 🔴 |
| T24 | 2 | FE: kho | FE-1 · 16/10 · DONE | (T18) | — | **L** (29/10: xem tồn theo lô, phiếu nhập, điều chỉnh; BM đủ 4 tab, lễ tân chỉ tab "Tồn kho") + FEFO **L** | 🟢 |
| T25 | 3 | Lịch hẹn: ST05 NO_SHOW, hạn chế đặt, ST03 nhắc | BE-1 · 19/10 · DONE | job trong BE | cần lịch quá hạn (xem §2) | T33 hiển thị trạng thái: **L** (28/10: hạn chế đặt online do 3 lần hủy muộn, gỡ hạn chế trên UI; NO_SHOW thật ✔ 14:06) | 🟡 |
| T26 | 3 | Tiếp nhận: Visit, hàng đợi, gán / gán lại, gọi lượt | BE-1 · 21/10 · IN_PROGRESS | visit #1–9, #11, `GET /me/queue` | 17 lượt | T36 · **L** (cấp cứu 201, hàng đợi, đưa lên trước, hủy, gọi; ngoài giờ `BR-CN-05` hiện đúng câu). **Lễ tân không đọc được `GET /staff` nên hộp gán người bị chặn** (gán qua API) | 🟡 |
| T27 | 4 | Khám: bệnh án, hoàn tất lượt | BE-1 · 22/10 · DONE | visit #10, #12–15 | 2 bệnh án | T37 · **L** (SOAP, cân nặng, hoàn tất, bản bổ sung sau khóa) | 🟢 |
| T28 | 4 | Tiêm chủng | BE-1 · 23/10 · DONE | visit #18–20 | 2 mũi; phác đồ chó + mèo (thêm tay) | T37 · **L** (gợi ý, ghi mũi, trừ kho, hết phác đồ, xóa) | 🟢 |
| T28b | 4 | Hồ sơ sức khỏe thú | BE-1 · chưa xếp · NOT_STARTED | **chưa có** (visit #21) | — | T23 · tab 404 | 🔴 |
| T29 | 5 | Order bán lẻ tại quầy | BE-2 · 19/10 · DONE | sales-v1 (Order bán lẻ) | — | T34 · **L** (28/10: tạo, thêm hàng, chốt, hủy chưa chốt ở quầy lễ tân; `/admin/pos`: xem, hủy khách không thanh toán) | 🟢 |
| T30 | 5 | Thu ngân: ca, thu tiền, đối soát, ST13 | BE-2 · 21/10 · DONE | sales-v1 (ca, thu, đối soát) | 3 phiếu thu, 2 ca | T35 · **L** (mở ca ngoài giờ, thu tiền mặt có thối, chốt ca, đối soát `RECONCILED`) | 🟢 (BR-BH-04 ✔ 28/10) |
| T31 | 6 | Lưu trú: chuồng, đặt chỗ, gia hạn, hủy | BE-2 · 22/10 · DONE | boarding #1–12, ST05 | 4 chuồng, 1 đặt chỗ | T48 · **L** (tạo / bảo trì chuồng, đặt tại quầy, gia hạn, hủy; trùng mã `BR-LT-01`) | 🟢 |
| T32 | 8 | Công khai, bài viết, feedback | BE-2 · 23/10 · DONE | content #1–22, `/api/public/*` | 4 `page_contents` rỗng; 0 bài; 0 chuyên mục; 0 feedback (đã dọn sau test 28/10) | T46, T47 · **L** (28/10: 11 trang công khai, nội dung trang, chuyên mục + bài viết, feedback, [CFG] giới hạn mỗi ngày; UI tự động, chờ bạn tick). `PUT` nội dung trang không kiểm cấu trúc (BE-note) | 🟢 |
| T33 | 3 | FE: đặt lịch, lịch của tôi | FE-1 · 19/10 · DONE | (T16) | — | **L** | 🟢 |
| T34 | 5, 7 | FE: thông báo, bán lẻ (POS) | FE-1 · 20/10 · DONE | care #5–8; sales | 3 thông báo | thông báo **P**; POS **L** (28/10); **cài đặt nhận thông báo 404** (care #9–10) | 🟡 · cài đặt 🔴 |
| T35 | 5 | FE: thu tiền, mở / chốt ca, đối soát | FE-1 · 21/10 · DONE | (T30) | — | **L** (28/10: chạy lại, thêm thu gộp chuyển khoản, thiếu tồn, đối soát lệch; ca ngoài giờ chờ sau 19:00) | 🟢 |
| T36 | 3 | FE: tiếp nhận, hàng đợi, gọi lượt | FE-1 · 22/10 · DONE | (T26) | — | **L** trừ hộp gán người | 🟢 · gán người 🟡 |
| T37 | 4 | FE: khám VET, thẩm mỹ CARETAKER | FE-1 · 23/10 · DONE (mock) | (T27, T28) | — | **L** (khám, tiêm, hoàn tất; thẩm mỹ Bắt đầu / Xong) | 🟢 |
| T38 | 4 | Kê đơn, hẹn tái khám | BE-1 · 26/10 · DONE | visit #16–17 | 2 dòng đơn | T46 · **L** (đủ tồn; thiếu tồn → giảm / mua ngoài / hủy; xóa dòng). Thu tiền dòng mua ngoài cần sales #11 | 🟢 · thu mua ngoài 🔴 |
| T39 | 7 | Care Task, ST04, ST18, ST19, `FollowUpApi` | BE-1 · 27/10 · DONE (chờ BE-2 xác nhận V22 / V23) | care #1–4; jobs; V22, V23 | 1 Care Task (tạo bằng luồng khám, §2) | T47 · **L** 28/10 (danh sách, "Đã gọi" 200); BRANCH_MANAGER, VET **403** (chỉ lễ tân). Chưa thử hủy, `VACCINE_*` | 🟢 |
| T40 | — | Nối interface chéo BE, test FSM | BE-1 · 28/10 · IN_PROGRESS | — | — | — | ⚪ |
| T41 | — | E2E BE luồng chính | BE-1 · 29/10 · NOT_STARTED | — | — | luồng chính đã chạy tay bằng UI ở T49 | ⚪ |
| T42 | 6 | Lưu trú: nhận thú, nhật ký | BE-2 · 26/10 · **DONE** 29/10 | boarding #13–15, #19–22 (ADR-0085) | — | T48 · **M** (dialog nhận thú, nhật ký, khách xem nhật ký); mock T48 #13–22 chưa đối chiếu BE thật | 🟡 BE có, FE chưa nối thật |
| T43 | 6 | Lưu trú: trả thú, Order BOARDING, ST15 | BE-2 · 27/10 · **DONE** 30/10 | boarding #16–18, sales #9, `activeOrderId`, ST15 (ADR-0086) | — | T48 · **M** (bắt đầu trả → thu tiền ở bàn Lễ tân → giao thú; hủy phiên; thú mất); mock T48 #16–18 / sales #9 chưa đối chiếu BE thật | 🟡 BE có, FE chưa nối thật |
| T44 | 9 | Báo cáo + ST08 | BE-2 · 28/10 · **DONE** 30/10 | report #1–7, 5 `*ReportApi`, ST08 (ADR-0087) | — | T49 · **M** (đối chiếu biên 12 tháng, thứ tự 403 trước 400, tên chi nhánh / dịch vụ; mock khác cách tính doanh thu; dashboard "Đã thu hôm nay" chưa thử với BE) | 🟡 BE có, FE chưa nối thật |
| T45 | — | Test FSM + E2E bán lẻ, lưu trú | BE-2 · 29/10 · NOT_STARTED | — | — | — | ⚪ |
| T46 | 4, 8 | FE: kê đơn, trang công khai | FE-1 · 26/10 · DONE (mock) | (T38, T32) | — | kê đơn **L**; trang công khai (`/services`, `/branches`, `/doctors`, `/products`, `/news`) **L** (28/10: 11 trang, 0 4xx lạ; `/help` FAQ cứng, `/about` và footer dùng mặc định trong mã) | 🟢 kê đơn · 🟢 công khai |
| T47 | 7, 8 | FE: nội dung trang, bài viết, feedback, Care Task | FE-1 · 27/10 · DONE (mock) | (T32, T39) | — | Care Task **L**; nội dung trang, bài viết, feedback **L** (28/10: UI tự động, chờ bạn tick) | 🟢 Care Task · 🟢 nội dung / bài viết / feedback |
| T48 | 6 | FE: lưu trú | FE-1 · 28/10 · DONE (mock) | (T31) | — | chuồng, đặt chỗ, gia hạn, hủy **L**; nhận / trả thú, nhật ký, khách xem nhật ký **M** | 🟢 · 🟡 nhận / trả / nhật ký (BE có từ 29–30/10, FE chưa nối thật) |
| T49 | 9 | FE: báo cáo + tích hợp API thật | FE-1 · 29/10 · IN_PROGRESS | (T44 có từ 30/10) | — | tích hợp **L**; báo cáo **M** | 🟢 tích hợp · 🟡 báo cáo (BE có từ 30/10, FE chưa nối thật) |
| T50 | — | Tổng duyệt demo, bàn giao | Cả nhóm · 30/10 · NOT_STARTED | — | cần seed giai đoạn 2 | — | ⚪ phụ thuộc nối thật T42–T44 (BE đã giao) |

## 5. Tóm lại: ghép được ngay / cần việc nhỏ / chưa ghép được

**🟢 Ghép được ngay (đã chạy thật):** đăng nhập, đăng ký OTP, đổi mật khẩu; quản trị nhân viên, tài khoản, [CFG], mẫu thông báo, audit; danh mục, chi nhánh, kho (xem, FEFO, và sửa / nhập / điều chỉnh / khách quầy: cụm 2, 29/10); khách đặt / đổi / hủy lịch; bàn Lễ tân (mở ca, tiếp nhận, hàng đợi), bác sĩ (khám, bệnh án, tiêm, kê đơn, hoàn tất), grooming; thu ngân (thu tiền, chốt ca, đối soát); Care Task của lễ tân; lưu trú (chuồng, đặt chỗ tại quầy, gia hạn, hủy); dashboard theo vai trò (trừ "Đã thu hôm nay").

**🟡 Ghép được, cần 1 việc nhỏ (BE đã có API):**
1. **Hộp "Gán người phụ trách" của lễ tân**: BE-1 mở `GET /api/staff` cho lễ tân (hoặc endpoint "nhân viên gán được"). Hiện gán được qua API trực tiếp.
2. **Trang công khai, bài viết, chuyên mục, nội dung trang, feedback** (T32, T46, T47): đã chạy thật 28/10 (6 ca `C8-01…C8-06`, API + giao diện tự động, không lệch FE ↔ BE); còn lại: bạn tick giao diện; "Tạm hết hàng" chưa kiểm được (cả 7 sản phẩm còn hàng); `PUT` nội dung trang chưa kiểm cấu trúc ở BE (Q1 chưa vào hợp đồng). Dữ liệu test đã dọn sạch (0 bài, 0 chuyên mục, 0 feedback, nội dung trang `{}`): demo `/news` có nội dung phải tạo lại bằng `/admin/articles`.
3. **Hồ sơ cá nhân, sổ địa chỉ, liên kết hồ sơ (BR-TK-19)**: chưa mở UI thật với BE.
4. **POS bán lẻ, thu thiếu tồn (BR-BH-04), lễ tân đặt lịch hộ, tiếp nhận từ lịch hẹn**: có API, chưa thao tác thật. **Phiếu nhập kho / điều chỉnh tồn** đã chạy thật 29/10 (cụm 2, ca `C2-05`, `C2-06`, API + giao diện tự động): nháp → xác nhận, hủy phiếu đã nhập, điều chỉnh tồn; còn lại: bạn tick giao diện.
5. **Sửa giờ mở cửa, ngày nghỉ, quota**: đã chạy thật 29/10 (cụm 2, ca `C2-03`, API + giao diện tự động, không lệch FE ↔ BE); còn lại: bạn tick giao diện.
6. **NO_SHOW / hạn chế đặt online (ST05)**: cần tạo lịch quá hạn.
7. **`e2e/workspaces.spec.ts`**: 3 ca lỗi thời từ T35 (phía FE, không phải BE).
8. **BE-2 đã giao 29–30/10, FE vẫn chạy mock, chưa nối thật:** **Báo cáo** (T44, report #1–7 + ST08) → `/admin/reports` và thẻ "Đã thu hôm nay"; **nhận thú, nhật ký chăm sóc, trả thú, hủy phiên trả thú** (T42, T43, boarding #13–22, sales #9, ST15, `activeOrderId`) → bốn hộp thoại ở `/admin/boarding` và khách xem nhật ký (UC57, UC59, UC60). Việc: nối thật, đối chiếu mock T48 #13–22 / sales #9 (lúc viết là ASSUMPTION) và mock báo cáo (cách tính doanh thu khác, biên 12 tháng, thứ tự 403 trước 400, tên chi nhánh / dịch vụ) với BE thật.

**🔴 Chưa ghép được (BE chưa có, FE chạy mock):**
- **Hồ sơ sức khỏe thú** (T28b, visit #21) → tab trong chi tiết thú.
- **Cài đặt nhận thông báo** (care #9–10, D025, chờ PO).
- **Chuyển dòng đơn sang mua ngoài khi thu tiền** (sales #11).
- **UC22** (identity #25–27: đổi email / liên kết tài khoản của khách).

## 6. Nên làm gì để kịp demo 30/10 (theo thứ tự ảnh hưởng)

1. **BE-2 đã giao T44, T42, T43 (29–30/10).** Còn của BE-2: T45 (test FSM + E2E bán lẻ, lưu trú, `NOT_STARTED`) và wiring sales #11 (mua ngoài khi thu).
2. **BE-2: xác nhận `V22`, `V23`** của BE-1 (BE-2 đã lấy `V24`–`V26` và ADR-0085…0087 cho T42–T44, ghi "chưa xác nhận với BE-1"); kiểm test của BE-2 dùng `new VaccinationQueryApi() {…}` có vỡ không (T39 thêm hàm trừu tượng).
3. **BE-1: mở `GET /api/staff` cho lễ tân**, rồi T28b, T40, T41.
4. **FE-1:** nối báo cáo thật (T44 đã xong 30/10: đối chiếu biên 12 tháng, câu lỗi, thứ tự 403 / 400), rồi nhận / trả thú, nhật ký (T42, T43; nếu không kịp, demo nói rõ phần đó chạy mock); chạy thử nhóm 🟡 ở mục 5; sửa 3 ca e2e; **viết seed giai đoạn 2** để dựng dữ liệu demo bằng một lệnh.
5. **Cả nhóm:** `docs/time.md` và `docs/architecture/system-overview.md` phải sạch dấu xung đột: `origin/dev` mang dấu đã commit ở hai file này (từ commit `a210199` của BE-1; bản gộp 28/10, `28345f1`, từng sạch). Bản gộp 30/10 đã dọn lại; chạy `git grep -n '^<<<<<<< \|^>>>>>>> '` trước khi push.

## 7. Dựng môi trường để tự kiểm

```
docker start petcare-seed-pg petcare-mailpit petcare-redis   # DB tạm 5433 (đã có dữ liệu) + Mailpit + Redis (BE cần)
cd BE && DB_PORT=5433 DB_NAME=petcare_seed DB_PASSWORD=123456 SERVER_PORT=8082 MAIL_HOST=localhost MAIL_PORT=1025 \
  mvn -B -ntp spring-boot:run -Dmaven.test.skip=true "-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Ho_Chi_Minh -Xmx768m"
cd FE && VITE_API_MOCK=false VITE_API_URL=http://localhost:8082 npx vite --port 5173
```

Cổng **8082** vì Docker Desktop tự bật lại container `petcare-api` cũ ở 8081. BE chỉ cho CORS origin `http://localhost:5173` và `http://localhost:3000` (`CORS_ALLOWED_ORIGINS`): chạy FE ở cổng khác sẽ trượt preflight (Playwright cụm 2 phải mở Chrome với `--disable-web-security`). Cần ~2,6 GB RAM cho Docker Desktop (đóng game client). Đăng nhập tại `/auth/login` bằng `<tên>@petcare.local` / `Petcare@2026`. Bẫy build và cách dựng lại đầy đủ: `docs/testcases/T49-fe.md` §F.
