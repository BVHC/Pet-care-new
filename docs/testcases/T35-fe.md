# Test case FE — T35 (thu tiền gộp Order, mở / chốt ca thu ngân, đối soát ca; UC70, UC71, UC72)

> FE viết 2026-10-23. Phần A: mock `thu tiền và ca thu ngân` (đã xong, có test tự động). Phần B: giao diện thu tiền, ca thu ngân, đối soát (có test tự động).
> Cột "Test tự động" ghi tên nhóm / test trong `FE/src/shared/api/mock/cashier.mock.test.ts` (viết tắt `cashier.mock.test`).
> Hợp đồng: `docs/api/sales-v1.md` #12–21 + `openapi/sales-v1.yaml`, ADR `docs/adr/0064-cashier-shift-payment.md`, quy tắc BR-TG-01…05, BR-BH-04, BR-BH-06, BR-CN-05, BR-QT-15; FSM 03 mục 8 (Ca thu ngân#1–#6) và Order#8.
> **Khi hợp đồng và BE khác nhau, mock theo BE** (FE sẽ gặp BE thật); các chỗ khác nhau ở mục A.6.

## A. Mock thu tiền và ca thu ngân

### A.1 Chuẩn bị

**Chạy với mock (mặc định):** `cd FE && npm run dev`, đăng nhập ở `/admin/login`, mật khẩu chung **`Petcare123`**.

| Tài khoản | Role | Dùng để |
|---|---|---|
| `reception@store1.vn` (id 4, "Lan Chi", chi nhánh 1) | RECEPTIONIST | Mở / chốt ca, thu tiền, xem phiếu thu và ca của mình. **Chưa có ca OPEN** khi mới nạp dữ liệu (thu tiền → 400 BR-TG-01 để thấy luồng nhắc mở ca) |
| `manager@store1.vn` (id 3, "Hoàng Nam", chi nhánh 1) | BRANCH_MANAGER | Danh sách ca, chi tiết ca, đối soát, xem phiếu thu; chỉnh tồn kho (`/admin/warehouse`) để dựng tình huống thiếu tồn |
| `manager2@store2.vn` (id 9, "Phạm Minh Đức", chi nhánh 2) | BRANCH_MANAGER | Chỉ thấy ca #4 và phiếu #8 của chi nhánh 2; mọi thứ của chi nhánh 1 → 403 |
| `chuoi@petcare.vn` (id 2) | SUPER_MANAGER | Bật / tắt cờ nhận cấp cứu ngoài giờ của chi nhánh (`PATCH /api/branches/1`) để thử BR-CN-05; mọi API thu ngân → 403 |
| `doctor@store1.vn`, `caretaker@store1.vn`, `admin@petcare.vn`, `khach@petcare.vn` | VET, CARETAKER, ADMIN, CUSTOMER | Chỉ để thử 403 |
| `newstaff@store1.vn` (id 8) | RECEPTIONIST, còn `mustChangePassword` | **Không dùng để gọi API** (mọi lời gọi → 400 BR-TK-17). Chỉ là chủ của ca tự chốt mẫu #2 (để thử "lễ tân không đọc được ca của người khác → 403") |

**Chạy thử mở ca bằng giờ thật:** ca thường chỉ mở được trong giờ mở cửa chi nhánh 1 (thứ Hai–thứ Bảy 08:00–12:00 và 13:30–19:00, chủ nhật 08:00–12:00, **giờ Việt Nam +07:00**, không mở vào ngày nghỉ của chi nhánh). Ngoài khoảng đó chỉ mở được **ca ngoài giờ** (chi nhánh 1 có cờ cấp cứu ngoài giờ). Mock đọc `Date.now()` trong từng lời gọi nên đồng hồ giả của test (`vi.useFakeTimers({ toFake: ['Date'] })` + `vi.setSystemTime`) có tác dụng.

**Đưa dữ liệu mock về ban đầu:** thu tiền đổi cả ba kho dữ liệu — DevTools → Application → Local Storage → xóa **`petcare-mock-cashier`, `petcare-mock-sales`, `petcare-mock-inventory`** (cùng lúc), rồi F5.

**Chạy với BE thật:** `VITE_API_MOCK=false` — BE đã có sales-v1 #12–21 (T30, ADR-0064).

### A.2 Dữ liệu mẫu

Ngày tính từ lúc nạp, theo giờ Việt Nam: D1 = ngày trả của Order #5 (3 ngày trước), D2 = 2 ngày trước, D3 = hôm qua. Mã phiếu thu `PT-yyMM-nnnnn` đánh số theo tháng Việt Nam của thời điểm thu, các phiếu mẫu dùng số 1–8 của (các) tháng đó; phiếu mới nối tiếp số lớn nhất của tháng — **đừng assert số cố định, chỉ assert "+1"**.

**Ca thu ngân** (id mới của ca mở thêm bắt đầu từ **101**):

| Ca | Chi nhánh | Lễ tân | Ngày | Loại | Trạng thái | Phiếu thu | expectedCash / expectedTransfer | countedCash | difference | Ghi chú |
|---|---|---|---|---|---|---|---|---|---|---|
| #1 | 1 | #4 Lan Chi | D1 | thường | **RECONCILED** | #1, #2 | 108.000 / 350.000 | 100.000 | −8.000 | `reconcileNote` "Thiếu 8.000đ…", `reconciledBy` 3 |
| #2 | 1 | #8 Lễ tân mới | D2 | thường | **CLOSED, `autoClosed`** | #3, #4 | 200.000 / 80.000 | **null** | null | Đối soát phải gửi `countedCash` (BR-TG-05) |
| #3 | 1 | #4 Lan Chi | D3 | thường | **CLOSED** chờ đối soát | #5, #6, #7 | 576.000 / 300.000 | 570.000 (lệch 6.000) | **null** (chỉ đối soát mới ghi `difference`) | |
| #4 | 2 | #11 Vũ Hải Yến | D3 | thường | CLOSED | #8 | 900.000 / 0 | 900.000 | null | Chứng minh phạm vi chi nhánh với `manager2@store2.vn`. Lễ tân #11 chưa có tài khoản trong mock (chỉ có tên hiển thị) |

Danh sách ca của quản lý chi nhánh 1 (`business_date DESC, id DESC`): **#3, #2, #1**; của chi nhánh 2: **#4**.

**Phiếu thu mẫu:**

| Phiếu | Ca | Chi nhánh | Khách | Phương thức | Số tiền | Order |
|---|---|---|---|---|---|---|
| #1 | 1 | 1 | #1 Nguyễn Thu Trang | CASH | 108.000 | **Order #5** (`paymentId = 1`, hết treo) |
| #2 | 1 | 1 | #3 | TRANSFER | 350.000 | — |
| #3, #4 | 2 | 1 | #4, #2 | CASH 200.000, TRANSFER 80.000 | — | — |
| #5, #6, #7 | 3 | 1 | #3, #1, #4 | CASH 450.000, CASH 126.000, TRANSFER 300.000 | — | — |
| #8 | 4 | 2 | #1 | CASH | 900.000 | — |

> **Phiếu #2–#8 là phiếu lịch sử không gắn Order thật** (mock bán lẻ `sales.mock.ts` chỉ có 8 Order cố định để không vỡ test cũ), nên `GET /api/payments/{id}` trả `orders: []` — BE không bao giờ trả mảng rỗng. **Đừng assert `orders` của phiếu #2–#8**; chỉ phiếu #1 (Order #5) và phiếu tạo mới có Order. Các `expected*` và `paymentCount` của ca luôn tính từ chính các phiếu này nên khớp nhau.

**Order chờ thu (PENDING) dùng để thu** — chi nhánh 1 (xem thêm `docs/testcases/T34-fe.md` B.1):

| Order | Khách | Nội dung | Tổng | Ghi chú thu |
|---|---|---|---|---|
| #2 (Visit) | #1 Nguyễn Thu Trang | Khám tổng quát 150.000 (SERVICE) · Amoxicillin ×10 @8.000 (DRUG) · Pate ×2 @18.000 (GOODS) | 266.000 | Đủ tồn (Pate 3, Amoxicillin 200 + 500). Thu gộp được với Order bán lẻ khác của khách #1 |
| #3 (Visit, quá hạn) | #4 Lê Thị Hoa | Tiêm phòng 50.000 (SERVICE) · Vaccine Dại Rabisin 150.000 (VACCINE) | 200.000 | **Chỉ dịch vụ + vaccine: không kiểm / không trừ kho** dù tồn Rabisin về 0 |
| #4 (bán lẻ) | #2 Lê Hoàng Long | Royal Canin ×1 | 450.000 | Đủ tồn (12) |

Chi nhánh 2: Order #8 (khách #1, Royal Canin ×2 = 900.000) — chi nhánh 2 **không có lễ tân** trong mock nên chỉ dùng để thử 403 chéo chi nhánh. Order khác: #1 OPEN (khách #3), #5 PAID (phiếu #1), #6 và #7 CANCELLED → không thu được.

**Tồn kho chi nhánh 1** (`inventory.mock.ts`; lô, số lượng): Royal Canin #1 lô 11 = 12 · Pate #2 lô 12 = 3 · Amoxicillin #3 lô 13 (AMX2405, hạn +20 ngày) = 200 và lô 14 (AMX2501) = 500 · Rabisin #5 lô 15 (RB2401) = 15 và lô 16 (RB2309, **đã hết hạn**) = 2 · Vitamin #4 = 0 (chưa có lô). Thu tiền trừ FEFO: hạn sớm nhất trước, lô mặc định (không hạn) cuối, **không bao giờ lấy lô đã hết hạn**.

**Công thức dựng tình huống thiếu tồn (BR-BH-04).** Dữ liệu mẫu **không** có Order PENDING thiếu tồn sẵn: thêm Order vào seed làm vỡ test cũ (`sales.mock.test`: 7 Order chi nhánh 1, `[8]` của chi nhánh 2, `[1, 2, 4]` của hôm nay, mã `…-00009`; `AdminPOSPage.test` mở `DH-…-00004`). Dựng bằng API (có test tự động, xem A.3 #13 / #14):

1. Vitamin hết hàng: lễ tân tạo Order bán lẻ cho khách #4 → thêm Vitamin tổng hợp PetCare (`productId` 4) ×1 → chốt (PENDING). Preview báo 1 dòng `available: 0`; thu → 400 BR-BH-04.
2. Thiếu do cộng dồn: lễ tân tạo Order bán lẻ cho khách #1 với Pate ×2 và chốt; thu gộp với Order #2 (Pate ×2) cần 4 > tồn 3 → preview báo **cả hai dòng** (mỗi dòng `requested: 2, available: 3`), thu → câu báo theo sản phẩm "Pate Whiskas 85g (cần 4, còn 3)".
3. Thiếu thuốc (DRUG): quản lý lập phiếu điều chỉnh tồn giảm lô 13 đi 195 và lô 14 đi 500 (còn 5) → Order #2 thiếu Amoxicillin (`lineType: 'DRUG'`, `requested: 10`, `available: 5`).
4. Tồn vaccine bằng 0 mà vẫn thu được: điều chỉnh lô 15 giảm 15 rồi thu Order #3.

### A.3 Quy tắc theo endpoint (chép từ BE, kiểm đúng thứ tự)

Chung: đọc JSON (kiểu sai → 400 `MALFORMED_REQUEST` "Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu") và Bean Validation của body / tham số path / query chạy **trước** kiểm vai trò (body sai + sai vai trò → 400, không phải 403); tham số path không phải số → 400 `MALFORMED_REQUEST` "Tham số 'shiftId' sai kiểu dữ liệu". Số tiền là số nguyên VND. Mã rule nối cuối `message`: `"… (BR-TG-01)"`. Chưa đăng nhập → 401. Vai trò sai → 403 `ACCESS_DENIED`; ngoài phạm vi (chi nhánh khác, không phải chủ ca) → 403 `ACCESS_DENIED_SCOPE_MISMATCH`. Các vai trò khác ngoài bảng (SUPER_MANAGER, VET, CARETAKER, ADMIN, CUSTOMER) → 403 ở cả 10 route.

| # | Endpoint | Vai trò | Thứ tự kiểm (sau vai trò) → kết quả | Test tự động |
|---|---|---|---|---|
| 12 | `GET /api/customers/{customerId}/payable-orders` | RECEPTIONIST | khách không có → 404 "Không tìm thấy khách hàng #id". Thành công: mảng `OrderSummary` PENDING của **khách này tại chi nhánh của lễ tân**, `pendingAt` tăng dần rồi `orderId` tăng dần | `cashier.mock.test` › "Order chờ thu của khách (#12)" (3 test) |
| 13 | `POST /api/payments/preview` `{orderIds}` | RECEPTIONIST | Order không có → 404 (id nhỏ nhất thiếu) "Không tìm thấy đơn hàng #id" → Order chi nhánh khác → **403 SCOPE** → khác khách → 400 BR-TG-03 "Chỉ thu gộp Order của cùng một khách tại cùng chi nhánh" → có Order không PENDING → **400 BR-TG-01** "Chỉ thu được Order đang chờ thu tiền". Thành công `{customerId, branchId, totalAmount, shortages}`; **không cần ca**, không ghi gì. `shortages`: mỗi **dòng** GOODS / DRUG của sản phẩm mà tổng cần (qua mọi Order) > tồn khả dụng, theo `orderId` rồi `lineId`; `requested` = số lượng của dòng, `available` = tồn khả dụng của sản phẩm; vaccine / dịch vụ / lưu trú không bị kiểm | "xem trước phiếu thu (#13)" (9 test) |
| 14 | `POST /api/payments` `{orderIds, method, amount}` → 201 "Đã thu tiền" | RECEPTIONIST có ca OPEN **của chính mình** | chưa có ca → **400 BR-TG-01** "Bạn chưa mở ca thu ngân" (đứng đầu) → 404 → **403 SCOPE** (kiểm cả tập Order) → **409 `INVALID_STATE_TRANSITION`** "Không thể chuyển trạng thái đơn hàng (chỉ thu được Order đang chờ thu tiền) từ {status} sang PAID" (Order không PENDING đầu tiên theo id) → 400 BR-TG-03 (khác khách) → 400 BR-TG-02 "Số tiền thu (X) khác tổng phải trả (Y)" (kể cả `amount: 0`) → 400 BR-BH-04 "Không đủ tồn kho cho: {tên} (cần N, còn M), … Xem trước phiếu thu để biết dòng cần xử lý" (**theo sản phẩm**, tổng cần qua các Order, mã sản phẩm tăng dần; không trừ gì). Thành công: Order → PAID (`paidAt`, `paymentId`), trừ kho FEFO, phiếu gắn `cashierShiftId` của ca, `receivedBy` = người thu, `orders` = Order sau khi PAID (id tăng dần), mã `PT-yyMM-nnnnn` liên tục theo tháng VN | "thu tiền (#14, Order#8, BR-TG-01…04)" (17 test), "ca ngoài giờ vắt qua nửa đêm… PT-2611-00001" |
| 15 | `GET /api/payments/{paymentId}` | BRANCH_MANAGER, RECEPTIONIST | 404 "Không tìm thấy phiếu thu #id" → chi nhánh khác 403 SCOPE | "phiếu thu mẫu (#15)" (3 test) |
| 16 | `POST /api/cashier-shifts` `{afterHours}` → 201 "Đã mở ca thu ngân" | RECEPTIONIST | đã có ca OPEN → **409** "Không thể chuyển trạng thái ca thu ngân (bạn đã có ca đang mở) từ OPEN sang OPEN" (trước mọi rule) → `afterHours = true`: chi nhánh không có cờ → 400 BR-CN-05, đang trong giờ mở cửa → 400 BR-TG-05 "Đang trong giờ mở cửa: hãy mở ca thường"; `afterHours = false`: ngoài giờ mở cửa / nghỉ trưa / ngày nghỉ → 400 BR-TG-05 "Chỉ mở được ca thường trong giờ mở cửa của chi nhánh". Giờ mở cửa là khoảng **[mở, đóng)** (08:00 mở được, 12:00 không; 13:29 không, 13:30 được; 19:00 không). `businessDate` = ngày Việt Nam lúc mở; JSON key `isAfterHours`. `afterHours` thiếu / null → 400 `VALIDATION_FAILED` "afterHours: Vui lòng chọn loại ca" | "mở ca thường (#16)", "mở ca ngoài giờ (#16…)", "mở ca — mỗi lễ tân tối đa 1 ca OPEN…" (30 test gồm bảng biên giờ) |
| 17 | `GET /api/me/cashier-shift` | RECEPTIONIST | `{ open: true, shift }` hoặc `{ open: false, shift: null }`. Ca OPEN: `expectedCash` / `expectedTransfer` **null**, `paymentCount` đếm ngay | "lễ tân #4 chưa có ca OPEN…", "201 'Đã mở ca thu ngân'…" |
| 18 | `POST /api/cashier-shifts/{shiftId}/close` `{countedCash}` → 200 "Đã chốt ca thu ngân" | RECEPTIONIST chủ ca | `countedCash` thiếu → 400 "countedCash: Vui lòng nhập số tiền mặt thực đếm", âm → "Số tiền thực đếm không được âm" (0 hợp lệ) → 404 "Không tìm thấy ca thu ngân #id" → không phải chủ ca (kể cả khác chi nhánh, kể cả ca đã chốt) **403 SCOPE** → ca không OPEN **409** "Không thể chuyển trạng thái ca thu ngân từ {status} sang CLOSED". Thành công: `status CLOSED`, `closedAt`, `countedCash`, `expectedCash` / `expectedTransfer` = tổng phiếu thu của ca theo phương thức, **`difference` vẫn null**. Chốt xong không thu được nữa (BR-TG-01) nhưng mở ca mới được | "chốt ca (#18, Ca thu ngân#3)" (6 test) |
| 19 | `GET /api/cashier-shifts` `status, businessDate, cashierId, page, size` | BRANCH_MANAGER | tham số sai kiểu (`status` lạ, ngày không hợp lệ, không phải số) → 400 `MALFORMED_REQUEST` (kiểm trước vai trò). Chi nhánh của quản lý; `business_date DESC, id DESC`; `page` 0-based, **`size` kẹp 1–100 (mặc định 20), `page` âm về 0, không báo lỗi**; rỗng → `totalElements 0, totalPages 0` | "bộ lọc status, businessDate, cashierId…", "tham số sai kiểu…", "danh sách chỉ BRANCH_MANAGER…" |
| 20 | `GET /api/cashier-shifts/{shiftId}` | BRANCH_MANAGER (cùng chi nhánh), RECEPTIONIST (chỉ ca của mình) | 404 → quản lý chi nhánh khác / lễ tân xem ca người khác (kể cả cùng chi nhánh) 403 SCOPE | "chi tiết: quản lý đúng chi nhánh…" |
| 21 | `POST /api/cashier-shifts/{shiftId}/reconcile` `{countedCash?, note}` → 200 "Đã đối soát ca thu ngân" | BRANCH_MANAGER | `note` rỗng / chỉ khoảng trắng / thiếu → 400 "note: Vui lòng nhập ghi chú đối soát", > 1000 ký tự → "Ghi chú tối đa 1000 ký tự", `countedCash` âm → "Số tiền thực đếm không được âm" → 404 → chi nhánh khác **403 SCOPE** → ca không CLOSED **409** "Không thể chuyển trạng thái ca thu ngân (chỉ đối soát được ca đã chốt) từ {status} sang RECONCILED" → ca chưa có `countedCash` (tự chốt) mà request không gửi → **400 BR-TG-05** "Ca tự chốt chưa có số tiền thực đếm: vui lòng nhập số tiền khi đối soát". Thành công: `RECONCILED`, `countedCash` (gửi lên thì **ghi đè**; 0 là số được gửi), **`difference = countedCash − expectedCash`**, `reconcileNote` đã bỏ khoảng trắng hai đầu, `reconciledBy` / `reconciledAt` | "đối soát ca (#21, Ca thu ngân#6)" (6 test) |

Phủ chung: đủ 10 khóa route; 401 và `mustChangePassword`; ma trận 5 vai trò × 10 route bị 403; lưu / nạp lại `petcare-mock-cashier`; `cashierName` từ danh bạ nhân viên (kể cả nhân viên tạo lúc chạy `extraStaff`, chưa biết tên → "Lễ tân #id") — nhóm "bao trùm" ở cuối `cashier.mock.test`.

### A.4 Giả định (mock)

| ID | Giả định | Căn cứ |
|---|---|---|
| M1 | **ST13 tự chốt ca không mô phỏng** (job của BE `CashierShiftAutoCloseJob`): mock không bao giờ tự chốt ca OPEN. Dữ liệu mẫu có sẵn ca #2 đã tự chốt (`autoClosed`, chưa có `countedCash`) để thử luồng đối soát | ADR-0064 §4 |
| M2 | Không ghi audit (BE ghi `ORDER_PAID` mỗi Order, `CASHIER_SHIFT_RECONCILED`), không gửi thông báo, không ghi sổ kho (`stock_movements`) — chỉ cập nhật số dư lô | ADR-0064 §2.8, §3.3 |
| M3 | `expectedCash` / `expectedTransfer` tính **lúc đọc** từ các phiếu thu của ca (BE chụp lúc chốt). Khác nhau không quan sát được vì ca CLOSED không nhận thêm phiếu (BR-TG-01) | ADR-0064 §1 |
| M4 | Phiếu thu mẫu #2–#8 không gắn Order thật (`orders: []`), xem A.2 | giới hạn của dữ liệu mẫu bán lẻ |
| M5 | Lễ tân #11 của ca mẫu #4 (chi nhánh 2) không có tài khoản đăng nhập: chỉ có tên hiển thị trong `cashier.mock.ts`. Chi nhánh 2 và 3 không có lễ tân và chưa có giờ mở cửa trong mock nên **không mở được ca ở đó**; thử BR-CN-05 bằng cách để SUPER_MANAGER tắt cờ của chi nhánh 1 | `identity.mock.ts`, `branch.mock.ts` |
| M6 | Hết hạn của lô tính theo `inventory.mock.ts` (hạn < hôm nay theo giờ máy, như `availableQuantity` của T24); giờ mở ca / thu tiền, ngày nghiệp vụ và tháng mã phiếu tính theo giờ Việt Nam cố định +07:00 (`shared/utils/vn-time.ts`) | `docs/time.md` |
| M7 | Kiểu JSON chặt: mock từ chối (400 `MALFORMED_REQUEST`) chuỗi / số thực / boolean sai kiểu ở `orderIds`, `amount`, `countedCash`, `afterHours`, `method`, `note`. Jackson mặc định của BE còn ép được chuỗi số (`"450000"`); FE không gửi vậy nên không mô phỏng | ADR-0032 (BE chỉ chặn số thực) |
| M8 | Không khóa đồng thời, không idempotency: thu hai lần cùng Order → lần hai 409 vì Order không còn PENDING | sales-v1 A7 |
| M9 | Mã rule BR-TG-03 chỉ còn bắt được "khác khách": Order chi nhánh khác đã bị 403 ở bước phạm vi | PaymentService.java:78 |
| M10 | Không có endpoint sửa / hoàn phiếu thu: sai sót phát hiện sau khi thu (ví dụ ghi nhầm phương thức) chỉ ghi nhận ở ghi chú và chênh lệch khi đối soát | BR-TG-04 |
| M11 | `localStorage` hỏng → dùng lại dữ liệu mẫu; ghi lỗi (chế độ riêng tư) → thay đổi chỉ mất khi tải lại | như các mock khác |

### A.5 API mock dùng chung (export thêm, không đổi hành vi route cũ)

| File | Export thêm |
|---|---|
| `shared/utils/vn-time.ts` | `vnClock(atMs?)` → `{ date, minutes, isoWeekday }`, `vnDate(atMs?)` (giờ +07:00) |
| `shared/api/mock/sales.mock.ts` | `ordersByIds`, `payableOrdersOf`, `ordersOfPayment`, `orderTotal`, `markOrdersPaid`; export kiểu `OrderRow`, `LineRow` |
| `shared/api/mock/inventory.mock.ts` | `issueFefo(branchId, lines)` → danh sách thiếu (`StockShortage`), kiểu `StockShortage` |
| `shared/api/mock/branch.mock.ts` | `isOpenAt(branchId, atMs)`, `acceptsAfterHours(branchId)` |
| `shared/api/mock/identity-admin.mock.ts` | `staffNameOf(accountId)` (tìm cả `extraStaff`) |

### A.6 Lệch hợp đồng ↔ BE (chuyển cho BE-2; FE không sửa `docs/api`)

Mock theo cột BE. Chỉ ghi chỗ **hai nguồn thật sự khác nhau** (L1–L4).

| # | Nội dung | Hợp đồng | BE | Đề xuất |
|---|---|---|---|---|
| L1 | Order **chi nhánh khác** trong xem trước / thu: hợp đồng ghi 400 `BR-TG-03`; BE trả **403 `ACCESS_DENIED_SCOPE_MISMATCH`** (kiểm phạm vi chi nhánh trước rule). BR-TG-03 chỉ còn khi khác khách | `docs/api/sales-v1.md:91` (xem trước), `:92` (thu), `docs/api/openapi/sales-v1.yaml:619`, `:661` ("Order khác khách / khác chi nhánh") | `PaymentQueryService.java:58` (`branchScope.check` trước BR-TG-03 ở `:59–63`), `PaymentService.java:78` (trước `:85–88`); ADR-0064 §2.4 ghi đúng thứ tự này | Sửa hợp đồng + openapi: 403 cho Order chi nhánh khác, BR-TG-03 chỉ "khác khách" |
| L2 | `size` danh sách ca ngoài [1, 100]: hợp đồng khai báo ràng buộc `minimum: 1`, `maximum: 100` và liệt kê lỗi `400` cho #19; BE **kẹp âm thầm** (size 0 → 1, 1000 → 100, `page` âm → 0), 400 chỉ khi sai kiểu | `docs/api/openapi/sales-v1.yaml:986–995` (tham số `Size`, dùng ở `:783`), `docs/api/sales-v1.md:99` (`GET /cashier-shifts`: "Lỗi: `400`"), `docs/api/00-method.md:48` ("tối đa 100") | `CashierShiftQueryService.java:62` (`Math.min(Math.max(size, 1), MAX_PAGE_SIZE)`, `Math.max(page, 0)`) | Ghi rõ "kẹp, không 400" ở 00-method §3.4 hoặc bỏ min / max khỏi openapi |
| L3 | **409 trước rule 400**: 00-method nói "quyền → tồn tại → guard nghiệp vụ (400) → chuyển trạng thái hợp lệ (409)"; cả bốn luồng T35 kiểm trạng thái (409) **trước** rule | `docs/api/00-method.md:70` | `PaymentService.java:79–82` (409) trước `:85` (BR-TG-03) và `:90` (BR-TG-02); `CashierShiftService.java:62–65` (409) trước `:67–81` (BR-CN-05 / BR-TG-05); `ShiftReconcileService.java:52–55` (409) trước `:56–59` (BR-TG-05); ADR-0064 §2.4 chủ ý ("409 trước rule như ADR-0049 Q6 / ADR-0051") | Cập nhật 00-method §3.5 thành "… → 409 (khi ADR chốt) → rule", hoặc ghi ngoại lệ |
| L4 | Bean Validation của body chạy **trước** `@PreAuthorize`: body sai + sai vai trò → 400, không 403. 00-method liệt kê "quyền" đứng đầu và không nhắc kiểm dữ liệu vào | `docs/api/00-method.md:70` | `PaymentController.java:32–33`, `CashierShiftController.java:38–39` (Javadoc) | Bổ sung một dòng vào 00-method §3.5 |

### A.7 Đã kiểm, không lệch hợp đồng (ghi để khỏi nhầm khi viết UI)

| # | Điểm | Nguồn |
|---|---|---|
| N1 | Xem trước trả **400 BR-TG-01** khi có Order không PENDING còn thu thật trả **409** — đúng như hợp đồng (`sales-v1.md:91` và `:92`), chỉ là hai nơi dùng hai cách; UI phải xử lý cả hai | `PaymentQueryService.java:64–66`, `PaymentService.java:79–82` |
| N2 | `ShortageLine` theo **dòng** ở xem trước (`requested` = số lượng dòng); câu BR-BH-04 lúc thu theo **sản phẩm** (tổng cần). Hợp đồng chỉ định nghĩa `ShortageLine` và nói "gọi preview để xem dòng thiếu" | `PaymentLines.java:48–66`, `PaymentService.java:131–143`, ADR-0064 §2.9 |
| N3 | `difference` **null khi CLOSED**, chỉ có sau đối soát (`countedCash − expectedCash`); UI muốn hiện chênh lệch của ca CLOSED phải tự tính từ `countedCash` và `expectedCash` | `CashierShift.java:94–99` (`closeByCashier` không đặt `difference`), `:110–118` (`reconcile`), ADR-0064 §3.2 |
| N4 | `sales-v1.md:33` ghi BR-TG-02 cho xem trước nhưng xem trước không có `amount` nên không bao giờ phát sinh | `PaymentPreviewRequest.java` |
| N5 | Trùng `orderIds` báo lỗi ở khóa `distinct` ("distinct: Danh sách Order bị trùng"), không gắn `orderIds[i]`; thông báo Bean Validation theo trường nối bằng "; " và thứ tự giữa các trường không cố định | `CreatePaymentRequest.java:42` (`@AssertTrue isDistinct`), `GlobalExceptionHandler.java:166–174` |
| N6 | Ca người khác (kể cả đã chốt) và ca chi nhánh khác: chốt → 403, lễ tân xem → 403, không tồn tại → 404 | sales-v1 A8, `CashierShiftCloseService.java:48–50` |
| N7 | Thông báo `MALFORMED_REQUEST` của mock cũ khác BE: `sales.mock.ts` / `mock-api.ts` dùng "Dữ liệu gửi lên không đọc được.", BE dùng "Nội dung request không đúng định dạng JSON hoặc sai kiểu dữ liệu" (`GlobalExceptionHandler.java:88`). Mock thu ngân dùng câu của BE. Việc của FE, không phải BE | `sales.mock.ts`, `mock-api.ts` |

### A.8 Còn lại / giới hạn

- Giao diện (phần B) đã làm: `CheckoutPanel`, `pos.store` và "Chờ thu" chạy trên sales API, không còn `clinic-db`.
- **Không có Order PENDING thiếu tồn trong dữ liệu mẫu** (lý do ở A.2); dựng bằng công thức A.2 hoặc xem test "thiếu tồn".
- `move-to-external-purchase` (#11) chưa có trong BE (ADR-0064 §5) và không có trong mock.

## B. Giao diện thu tiền, ca thu ngân, đối soát (UC70, UC71, UC72)

> FE viết 2026-10-23. Cột "Test tự động" ghi `file test › nhóm`. Tên file viết tắt: `ShiftControls.test`, `CheckoutPanel.test`, `FrontDeskPanel.test` (cùng thư mục `FE/src/components/staff/reception/`), `ReceptionWorkspacePage.test` (`FE/src/pages/staff/`), `AdminPaymentsPage.test` (`FE/src/pages/admin/`), `StaffSidebar.test` (`FE/src/components/staff/`), còn lại ở `FE/src/shared/` (`utils/shift-options.test`, `utils/shift-format.test`, `hooks/usePendingSalesOrders.test`, `hooks/useWorkspaceSignals.test`, `stores/pos.store.test`, `constants/staff-menu.test`). Mọi test mở ca / thu tiền ghim đồng hồ giả (chỉ `Date`) ở **thứ Năm 22/10/2026 10:00 +07:00** (`FE/src/test/cashier-harness.ts`), chạy đúng cả khi múi giờ máy là UTC, Los_Angeles, Tokyo.

### B.1 Chuẩn bị

Dùng dữ liệu mẫu và tài khoản ở A.1, A.2. Thêm cho giao diện:

- **Lễ tân** `reception@store1.vn`: bàn Lễ tân `/staff/reception` (chip ca ở đầu trang, nút **Chốt ca**, tab **Chờ thu**, thẻ **Phiếu thu**). Menu quản trị **không còn** "Thanh toán" (xem B.2).
- **Quản lý** `manager@store1.vn` (và `manager2@store2.vn` cho chi nhánh 2): menu **Ca thu ngân** (`/admin/payments`). Vào bàn Lễ tân vẫn được (A05) nhưng chỉ xem.
- Thử **mở ca ngoài giờ** bằng giờ máy thật cần giờ Việt Nam ngoài 08:00–12:00 / 13:30–19:00 (thứ Hai–thứ Bảy) hoặc chiều chủ nhật; hoặc để quản lý thêm ngày nghỉ hôm nay ở `/admin/branches`; hoặc để `chuoi@petcare.vn` tắt cờ cấp cứu của chi nhánh 1 để thấy trường hợp "không mở được ca nào".
- Dựng **thiếu tồn** (BR-BH-04): công thức ở A.2 (lễ tân tạo Order bán lẻ Vitamin cho khách #4 "Lê Thị Hoa" rồi **Chốt Order** ở tab Bán hàng; Vitamin tồn 0).
- Xóa dữ liệu mock: như A.1, thêm `petcare-mock-branch` nếu đã thêm ngày nghỉ / đổi cờ.

### B.2 Màn hình, vai trò, thay đổi menu

| Màn hình | Ai | Gì |
|---|---|---|
| Chip ca + nút **Chốt ca** (đầu bàn Lễ tân) | RECEPTIONIST | "Chưa mở ca thu ngân" hoặc "Ca mở lúc HH:mm · N phiếu thu" (+ thẻ "Ca ngoài giờ"). Ca đang mở chưa có số tiền (BE chỉ có `expected*` sau khi chốt) nên chỉ hiện số phiếu |
| Phiếu thu (`CheckoutPanel`) | RECEPTIONIST | Chưa mở ca: nút mở ca đúng luật giờ. Có ca: chọn khách → tick Order chờ thu → xem trước → thu → phiếu thu |
| Phiếu thu, tab Chờ thu, chip ca | BRANCH_MANAGER trong bàn Lễ tân | Phiếu thu chỉ có lời nhắc "Thu tiền và mở / chốt ca là việc của lễ tân. Đối soát ca ở trang Ca thu ngân." kèm liên kết `/admin/payments`; Chờ thu xem được, **không có nút Thu tiền**; không chip ca, không nút ca; **không gửi** `GET /me/cashier-shift`, `payable-orders`, `payments/preview` (tránh 403) |
| Ca thu ngân `/admin/payments` | BRANCH_MANAGER | Bảng ca, lọc, phân trang, chi tiết, đối soát |
| Tab Chờ thu, số "Chờ thu tiền" ở thanh bên, toast Order mới | A05–A08 của chi nhánh | Đọc `GET /orders?status=PENDING&size=100`, hỏi lại mỗi 15 giây. ADMIN, SUPER_MANAGER, CUSTOMER, chưa đăng nhập: không gọi |

**Thay đổi menu thấy được (T35):** mục `/admin/payments` đổi tên **"Thanh toán" → "Ca thu ngân"** và role `RECEPTIONIST, BRANCH_MANAGER` → **chỉ `BRANCH_MANAGER`**. Lễ tân **mất** mục "Thanh toán" (và URL `/admin/payments` thành 403 với lễ tân): lễ tân thu tiền, mở và chốt ca ngay ở bàn Lễ tân; đối soát là UC72, actor A05. `/admin/invoices` ("Hóa đơn") giữ nguyên. Test: `staff-menu.test › gives the shift reconciliation page to the branch manager only`, `AdminPaymentsPage.test › lễ tân không mở được trang (403)`.

### B.3 Mở ca, chốt ca (UC71)

Bảng quyết định nút mở ca (`shiftOptions`, hàm thuần `FE/src/shared/utils/shift-options.ts`; đọc giờ mở cửa #6, ngày nghỉ #9, cờ #3 của chi nhánh mình). "Mở cửa" = không phải ngày nghỉ và giờ Việt Nam nằm trong một khoảng **[mở, đóng)** của bản giờ có hiệu lực hôm nay; nghỉ trưa là ngoài giờ. `normal = mở cửa`; `afterHours = có cờ và không mở cửa`. Giờ mẫu chi nhánh 1: thứ Hai–thứ Bảy 08:00–12:00 và 13:30–19:00, chủ nhật 08:00–12:00.

| Thời điểm (+07:00) | Ca thường | Ca ngoài giờ |
|---|---|---|
| thứ Năm 07:59 | không | **có** |
| 08:00 | **có** | không |
| 11:59 | **có** | không |
| 12:00 | không | **có** |
| 13:29 | không | **có** |
| 13:30 | **có** | không |
| 18:59 | **có** | không |
| 19:00 | không | **có** |
| chủ nhật 11:59 / 15:00 | **có** / không | không / **có** |
| ngày nghỉ, 10:00 | không | **có** |
| cờ tắt, đang ngoài giờ | không | không, kèm lý do ("… không nhận cấp cứu ngoài giờ nên chưa mở được ca thu ngân.") |
| cờ tắt, trong giờ | **có** | không |

Test: `utils/shift-options.test` (17 test, gồm bản giờ tương lai, múi giờ máy khác, chưa có bản giờ). BE vẫn kiểm lại (400 BR-TG-05 / BR-CN-05) và lỗi hiện bằng `plainMessage` (không dùng `paymentErrorMessage`, vì câu 409 của thu tiền sai với ca).

| # | Bước | Kết quả | Test tự động |
|---|---|---|---|
| S1 | Lễ tân chưa có ca, 10:00 | Chip "Chưa mở ca thu ngân"; phiếu thu chỉ có "Mở ca thường"; **không** có ô tìm khách, phương thức, nút Thu (BR-TG-01) | `ShiftControls.test › chưa có ca: ẩn chức năng thu tiền…` |
| S2 | Chưa có ca nhưng đã bấm Thu tiền ở tab Chờ thu | Khách và Order vẫn hiện (không mất), chưa có nút thu | `› chưa có ca nhưng Order đã được đưa sang…` |
| S3 | 20:30 / chủ nhật chiều / nghỉ trưa 12:30 / ngày nghỉ | Chỉ "Mở ca ngoài giờ" | `› 20:30…`, `› chủ nhật chiều…`, `› nghỉ trưa…`, `› ngày nghỉ của chi nhánh…` |
| S4 | Tắt cờ cấp cứu rồi 20:30 | Không nút mở ca; lý do hiện | `› chi nhánh không có cờ cấp cứu…` |
| S5 | Bấm "Mở ca thường" / "Mở ca ngoài giờ" | Chip "Ca mở lúc … · 0 phiếu thu" (+ thẻ "Ca ngoài giờ"); hiện ô tìm khách; xuất hiện nút "Chốt ca" | `› mở ca thường…`, `› mở ca ngoài giờ…` |
| S6 | Đồng hồ qua giờ đóng cửa sau khi nút đã hiện, bấm mở | BE trả 400; hiện "Chỉ mở được ca thường trong giờ mở cửa của chi nhánh" (không có mã rule) | `› BE vẫn là chốt chặn…` |
| S7 | Đã có ca mở ở tab khác, bấm mở | 409 "…bạn đã có ca đang mở…" (câu của ca, không nhắc Order) | `› đã có ca mở ở tab khác…` |
| S8 | "Chốt ca" → nhập tiền mặt thực đếm → "Xác nhận chốt ca" | Cảnh báo "Chốt xong không thu thêm tiền được nữa…"; tóm tắt: tiền mặt / chuyển khoản theo phiếu, thực đếm, **chênh lệch tự tính = thực đếm − tiền mặt** (BE để `difference = null` tới khi đối soát), "Chờ quản lý đối soát"; bấm Xong → về trạng thái chưa mở ca | `› chốt ca thu ngân (UC71; BR-TG-05) › nhập tiền mặt thực đếm…` |
| S9 | Bỏ trống, `-5`, `1.5`, `abc` | Báo ngay, không gọi API; `0` hợp lệ ("0 ₫ (khớp)") | `› số thực đếm là số nguyên từ 0…` |
| S10 | Ca đã bị chốt ở nơi khác | 409 "Không thể chuyển trạng thái ca thu ngân…", hộp thoại còn để đọc | `› ca đã được chốt ở nơi khác…` |
| S11 | Quản lý trong bàn Lễ tân | Lời nhắc + liên kết; không nút / chip ca; không gọi API thu ngân | `› quản lý chi nhánh trong bàn lễ tân…`, `ReceptionWorkspacePage.test › quản lý chi nhánh (A05)` |

### B.4 Thu tiền (UC70)

Luồng: chọn khách (từ tab Chờ thu → "Thu tiền", hoặc gõ SĐT / họ tên / email ở ô tìm trong phiếu) → danh sách Order chờ thu của khách với ô tick (đưa từ tab Chờ thu: chỉ tick Order đó; chọn khách tại phiếu: tick sẵn tất cả, tối đa 20) → xem trước tổng + dòng thiếu → phương thức → **Thu** (hoặc Ctrl+Enter khi phiếu đang hiện) → phiếu thu. Phiếu thu = một khách (BR-TG-03); đổi khách = "Đổi khách" (xóa phiếu).

| # | Bước | Kết quả | Test tự động |
|---|---|---|---|
| P1 | Chọn "Nguyễn Thu Trang" (có Order #2 và một Order bán lẻ vừa chốt) | Cả hai Order hiện (nguồn "Lượt tiếp nhận" / "Bán lẻ", tổng), tick sẵn; nút "Thu 716.000 ₫" | `CheckoutPanel.test › thu tiền gộp theo khách › chọn khách → liệt kê…` (tạo + chốt Order bán lẻ bằng `salesApi`, như luồng T34) |
| P2 | Bỏ tick | Tổng tính lại; bỏ hết: "Chọn ít nhất một Order để thu." và nút Thu tắt | `› bỏ tick một Order…` |
| P3 | 21 Order chờ thu | Tick sẵn 20; ô còn lại khóa kèm "Một phiếu thu tối đa 20 Order." | `› một phiếu tối đa 20 Order…` |
| P4 | Bấm "Thu tiền" ở tab Chờ thu | Phiếu mang đúng khách, chỉ tick Order đó; Order khác của khách để tự tick (thu gộp). Order đã thu ở nơi khác tự rời phiếu | `› Order do tab Chờ thu đưa sang…`, `› Order đã thu ở nơi khác thì rời khỏi phiếu`, `FrontDeskPanel.test` |
| P5 | Tiền mặt, "Khách đưa" 1.000.000, thu | Hiện "Thối 284.000 ₫" trước khi thu; phiếu thu: mã `PT-yyMM-nnnnn`, thu ngân (tên lễ tân của ca), từng Order + tiền, tổng, khách đưa, tiền thối; phiếu thu trống; Order hết chờ thu; kho trừ (Pate 3 → 1, Royal Canin 12 → 11); ca đếm 1 phiếu | `› thu tiền mặt › thu gộp 2 Order…` |
| P6 | Tiền mặt, "Khách đưa" 300.000 cho tổng 266.000 | Thu được, "Thối 34.000 ₫"; **`amount` gửi lên vẫn là 266.000** (luôn bằng tổng xem trước, không phải số khách đưa) | `› số tiền gửi lên luôn bằng tổng xem trước…` (spy `salesApi.createPayment`) |
| P6a | Tiền mặt, "Khách đưa" 100.000 (hoặc `0`) cho tổng 266.000 | **Không được xác nhận thu** (BR-TG-02, cột Xử lý: "Không cho xác nhận khi số tiền thu khác tổng tiền phải trả"): nút Thu tắt, ô nhập `aria-invalid`, dòng đỏ "Khách đưa chưa đủ: còn thiếu 166.000 ₫" ngay dưới ô; **Ctrl+Enter cũng không thu** (`createPayment` không được gọi, Order vẫn chờ thu) | `› "Khách đưa" thiếu thì không được xác nhận thu… › đưa thiếu…`, `› nhập 0 cũng là đã nhập…` |
| P6b | Tiền mặt, để trống "Khách đưa" | Thu được (ô tùy chọn, không nằm trong request) | `› để trống: vẫn thu được…` |
| P6c | Đưa đúng tổng / đưa dư | Thu được; dư thì hiện "Thối …" và phiếu thu ghi tiền thối | `› đưa đúng tổng thì thu được; đưa dư…` |
| P6d | "Khách đưa" 300.000, hai Order 716.000: bỏ tick Order bán lẻ → tổng 266.000; tick lại | Thiếu 416.000 (Thu tắt) → đủ, Thối 34.000 (Thu bật) → lại thiếu: điều kiện tính lại ngay theo tổng xem trước | `› đổi tổng bằng cách bỏ / tick Order…` |
| P6e | Chuyển khoản sau khi đã nhập "Khách đưa" thiếu | Không bị ràng buộc bởi "Khách đưa"; chỉ cần tick "Đã nhận đủ tiền chuyển khoản" | `› chuyển khoản không bị ràng buộc bởi "Khách đưa"…` |
| P7 | Chuyển khoản | Không có ô "Khách đưa"; nút Thu tắt tới khi tick "Đã nhận đủ tiền chuyển khoản" (đổi tổng phải tick lại); phiếu ghi chuyển khoản, không có tiền thối | `› chuyển khoản…` |
| P8 | Có Order thiếu tồn (Vitamin tồn 0) | Khung đỏ: mã Order · "Vitamin tổng hợp PetCare: cần 1, còn 0"; Thu tắt, "Chưa thu được vì thiếu tồn kho…"; **không** có "chuyển mua ngoài" (#11 chưa làm). Bỏ tick Order thiếu thì thu được phần còn lại | `› thiếu tồn kho… › có dòng thiếu…` |
| P9 | Order #3 (chỉ dịch vụ + vaccine) khi tồn vaccine = 0 | Thu được; không kiểm / không trừ kho | `› Order chỉ có dịch vụ và vaccine vẫn thu được…` |
| P10 | Vừa thu xong | Phiếu xóa **trước** khi hiện phiếu thu; **không** có chữ lỗi (không xem trước lại Order vừa thu → không nháy 400 BR-TG-01). Truy vấn xem trước tắt trong lúc đang thu | `› thu xong không có chữ lỗi nào hiện ra…` (spy `salesApi.previewPayment`, đã thử bỏ chốt chặn thì test đỏ) |
| P11 | Order bị thu ở nơi khác trong lúc xem | 409 → danh sách tải lại, Order biến mất ("Khách không có Order chờ thu"), không có phiếu thu; khung đỏ (`paymentErrorMessage`) "Có Order không còn chờ thu (đã thu hoặc đã hủy ở nơi khác). Đã tải lại danh sách." **vẫn đọc được** dù phần thu tiền đã ẩn đi; mất khi bỏ tick, chọn khách khác hoặc "Đổi khách" | `› Order bị thu ở nơi khác…` |
| P11b | Ca bị chốt ở nơi khác ngay lúc bấm Thu | 400 BR-TG-01: khung đỏ "Bạn chưa mở ca thu ngân, hoặc có Order không còn ở trạng thái chờ thu. Mở ca rồi tải lại danh sách." còn nguyên; phần thu tiền ẩn, hiện nút mở ca | `› ca bị chốt ở nơi khác ngay lúc thu…` |
| P12 | Ctrl+Enter | Thu khi phiếu hiện và đủ điều kiện, đúng một lần; không thu khi bàn giấu phiếu (`hotkeysEnabled = false`) hay khi đang mở hộp thoại (tiếp nhận khách) | `CheckoutPanel.test › Ctrl+Enter…` (2 test), `ReceptionWorkspacePage.test › Ctrl+Enter thu tiền…`, `› never takes payment while the check-in dialog is open` |

### B.5 Chờ thu từ API (không còn clinic-db)

| Chỗ | Hành vi | Test tự động |
|---|---|---|
| Tab **Chờ thu** (`FrontDeskPanel`) | Hàng: mã Order, nguồn, tên khách, "Chốt lúc", tổng; nút "Thu tiền" / "Thu gộp" / "Trong phiếu" chỉ cho lễ tân | `FrontDeskPanel.test` (3 test) |
| Thanh bên "Chờ thu tiền" | Đếm Order PENDING của API (sales.mock: 3) | `StaffSidebar.test › counts the orders waiting for payment…` |
| Toast "Đơn mới chờ thu của …" | Chỉ ở bàn Lễ tân; Order có sẵn khi mở trang không báo | `hooks/useWorkspaceSignals.test` |
| Hook `usePendingSalesOrders` | `GET /orders?status=PENDING&size=100`, 15 giây một lần, chỉ A05–A08 | `hooks/usePendingSalesOrders.test` |
| `pos.store` | `customerId: number`, `orderIds: number[]`, `method: CASH / TRANSFER`; bỏ `retail`, `posTotal` | `stores/pos.store.test` |

### B.6 Đối soát ca `/admin/payments` (UC72)

| # | Bước | Kết quả | Test tự động |
|---|---|---|---|
| D1 | Quản lý chi nhánh 1 mở trang | Ca #3, #2, #1 (`business_date DESC, id DESC`); cột ngày, lễ tân, loại, trạng thái, mở / chốt lúc, tiền mặt, chuyển khoản, thực đếm, chênh lệch, số phiếu; thẻ "Tự chốt" ở ca #2; ca CLOSED hiện chênh lệch tự tính kèm "tạm tính" (-6.000 ₫ (thiếu) ở ca #3); ca RECONCILED hiện chênh lệch của BE (-8.000 ₫) | `AdminPaymentsPage.test › quản lý liệt kê ca…` |
| D2 | Lọc trạng thái / ngày nghiệp vụ | Bảng lọc; rỗng: "Không có ca nào khớp bộ lọc." (mock trả `totalPages: 0`, không hiện phân trang) | `› lọc theo trạng thái và ngày nghiệp vụ…` |
| D3 | Phân trang | `page` 0-based, `size` 20; "Trang x/y · N ca", Trước / Sau | `› phân trang theo trang 0-based…` |
| D4 | "Xem" một ca | Hộp thoại chi tiết (kèm ghi chú đối soát nếu có); nút "Đối soát ca" chỉ ở ca CLOSED | `› xem chi tiết một ca…` |
| D5 | Ca OPEN / RECONCILED | Không có nút Đối soát | `› nút Đối soát chỉ có ở ca đã chốt…` |
| D6 | Đối soát ca #3 (thường) | Số thực đếm điền sẵn 570.000; chênh lệch hiện ngay khi sửa; ghi chú bắt buộc (≤ 1000, có đếm ký tự); thành công: ca "Đã đối soát", chênh lệch của BE -6.000; số thực đếm không đổi thì **không** gửi, sửa thì gửi (ghi đè) | `› đối soát ca (UC72…) › ca thường…`, `› sửa số thực đếm…` |
| D7 | Đối soát ca #2 (tự chốt) | Ô thực đếm trống, có lời nhắc; để trống → "Ca tự chốt chưa có số thực đếm…" (BR-TG-05), **không gọi API**; nhập 195.000 → chênh lệch -5.000 | `› ca tự chốt chưa có số thực đếm…` |
| D8 | Số sai kiểu / ghi chú 1001 ký tự | Chặn trước khi gửi ("Số tiền thực đếm là số nguyên từ 0.", "Ghi chú tối đa 1000 ký tự.") | `› số thực đếm sai kiểu và ghi chú quá 1000 ký tự…` |
| D9 | Ca vừa được đối soát ở máy khác | 409 "…chỉ đối soát được ca đã chốt…" (câu của ca, không có "Order", không có mã rule) | `› ca vừa được đối soát ở nơi khác…` |
| D10 | `manager2@store2.vn` | Chỉ ca #4 của chi nhánh 2 | `› quản lý chi nhánh 2 chỉ thấy ca của chi nhánh 2` |
| D11 | Lễ tân mở `/admin/payments` | 403 trong khung, không gọi `GET /cashier-shifts` | `› lễ tân không mở được trang (403)…`, `staff-menu.test` |

### B.7 Giả định (giao diện)

| ID | Giả định | Căn cứ |
|---|---|---|
| U1 | Tiền mặt: "Khách đưa" **để trống** thì vẫn thu được (ô tùy chọn, không nằm trong request); **đã nhập (kể cả 0) mà nhỏ hơn tổng** thì nút Thu và Ctrl+Enter bị khóa, kèm "Khách đưa chưa đủ: còn thiếu X"; đưa bằng hoặc hơn thì thu được và hiện tiền thối. Chuyển khoản không bị ảnh hưởng. `amount` gửi lên luôn là tổng xem trước. Khóa vì BR-TG-02 ("Không cho xác nhận khi số tiền thu khác tổng tiền phải trả") nói về việc thu ngân xác nhận đã thu đủ, nên số tiền khách đưa thiếu thì chưa được xác nhận | BR-TG-02 (cột Xử lý); UI cũ cũng chặn |
| U2 | Tick `Đã nhận đủ tiền chuyển khoản` gắn với tổng đã xác nhận: đổi tổng (đổi Order được chọn) thì phải tick lại | Hợp đồng A4 (xác nhận thủ công) |
| U3 | Thu xong gọi `clear()` (xóa cả khách, phương thức về tiền mặt) rồi mới hiện phiếu thu; khách còn Order khác thì chọn lại khách | "Xóa phiếu rồi hiện phiếu thu" |
| U4 | Chọn khách ngay tại phiếu thì tick sẵn mọi Order chờ thu (tối đa 20, cũ nhất trước); Order do tab Chờ thu đưa sang thì chỉ tick Order đó | "Mặc định: tất cả" + tab Chờ thu là chủ ý chọn một Order |
| U5 | Không tải được giờ mở cửa / ngày nghỉ / cờ của chi nhánh: báo lỗi và nút "Tải lại", **không** hiện nút mở ca nào (không đoán luật) | Không bịa rule |
| U6 | Ngày nghiệp vụ hiển thị cắt từ chuỗi `yyyy-MM-dd` (không qua `Date`); giờ mở / chốt hiển thị theo múi giờ máy (`formatClock`) như phần còn lại của bàn làm việc | `docs/time.md` / các màn đã có |
| U7 | Ca CLOSED hiện chênh lệch tự tính (`countedCash − expectedCash`) kèm nhãn "tạm tính"; sau đối soát dùng `difference` của BE | N3 ở A.7 |
| U8 | Khi đối soát, ô thực đếm điền sẵn số lễ tân đã chốt; chỉ gửi `countedCash` khi khác số đó (BE: gửi lên là ghi đè). Ca tự chốt thì bắt buộc nhập | BR-TG-05, ADR-0064 §3.3 |
| U9 | Chuyển dòng thiếu tồn sang mua ngoài **không** có (sales #11 chưa làm); câu hướng dẫn chỉ nói bỏ Order thiếu khỏi phiếu hoặc nhờ quản lý bổ sung tồn kho | A.8 |
| U10 | `usePendingSalesOrders` lấy tối đa 100 Order PENDING (`size=100`); số trên tab / thanh bên là số trong trang đó | Yêu cầu T35 |
| U11 | Quản lý trong bàn Lễ tân không có phiếu thu: `CheckoutPanel` tách hai thành phần theo role nên không chạy hook thu ngân nào | Ma trận quyền #12–14, #16–18 chỉ A06 |

### B.8 Còn lại, phụ thuộc, ghi chú cho người sau

- **Lượt khám / grooming hoàn tất ở bàn Phòng khám và Grooming vẫn ghi vào `clinic-db`**, nên Order của chúng **không** xuất hiện ở tab Chờ thu (nguồn Chờ thu giờ là sales mock) cho tới khi T36 / T37 chuyển hai bàn đó sang API Visit / Order. Chờ thu hiện chỉ có Order dữ liệu mẫu và Order bán lẻ đã chốt.
- `FE/e2e/workspaces.spec.ts` (bước thu tiền ca khám, bước "counter sale with the keyboard only") còn theo luồng cũ (nút "Mở ca thu ngân", Order của clinic-db): **không sửa**, sẽ đỏ khi chạy Playwright.
- Dashboard `AdminDashboardPage` ("Đã thu hôm nay"…) vẫn tính từ clinic-db, sẽ không khớp số liệu phiếu thu của sales.
- Mã không còn ai dùng sau T35 (để T36 / T37 gỡ cùng `clinic-db`): `clinicApi.listPendingOrders`, `getOpenShift`, `openShift`, `collectPayment` và các kiểu `PaymentReceipt`, `CollectPaymentInput` (`clinic.api.ts`), `PendingOrderView`, `CashierShift`, `ShiftStatus` (`types/clinic.ts`); `QUERY_KEYS.shift`, `QUERY_KEYS.orders.pending`, `INVALIDATE.openShift`, `INVALIDATE.collectPayment` (`constants/queryKeys.ts`); `METHOD_LABEL` (`constants/clinic-labels.ts`) và `PaymentMethod` của `types/clinic`. Đã gỡ trong `useClinic.ts`: `usePendingOrders`, `useCurrentShift`, `useOpenShift`, `useCollectPayment`.
- "Đặt lại dữ liệu demo" ở thanh bên (`useResetDemo`) chỉ đặt lại `clinic-db`, **không** đặt lại sales / cashier / inventory / branch mock (xóa `localStorage` như A.1).
- Thông báo cho quản lý khi ca tự chốt (ST13), audit, khóa đồng thời: ngoài phạm vi mock (A.4 M1, M2, M8).
