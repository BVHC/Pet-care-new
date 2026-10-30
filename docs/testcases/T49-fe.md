# T49 (FE): Báo cáo + dọn mảng cứng + đối chiếu FE ↔ BE thật (UC89)

> Phạm vi: **A. Báo cáo** (UC89, `/admin/reports`, mock report-v1 #1–7) · **B. Dashboard và route ẩn** (bỏ `clinic-db`) · **C. Đối chiếu tĩnh FE ↔ code BE thật** và các chỗ đã sửa · **D. Màn hình còn bị BE chặn** · **E. Test và đột biến** · **F. Còn lại**.
> Hợp đồng: `docs/api/report-v1.md` (7 endpoint), BR-BC-01…04. BE: module `report` **chưa có** (T44); mọi endpoint khác của BE đã có đã được đối chiếu với code ở mục C.
> Kế hoạch / bàn giao: `docs/superpowers/plans/2026-10-27-t49-fe-reports-integration.md`, `…-t49-handoff.md`; bảng lệch gốc `…-t49-diff-group1.md` (identity, appointment, visit, care) và `…-t49-diff-group2.md` (branch, catalog, customer, sales, inventory, boarding, content) — thư mục gitignore, chỉ ở máy dev.
> **Quyết định phạm vi 27/10 (người dùng):** *chưa cần responsive*, chỉ 1280 px. **Đã chạy FE với BE thật tối 27/10** (mục C.3, C.4: quét trang + luồng chính); phần còn lại là test jsdom trên mock + đọc code BE.
> **Hàng T49 trong `docs/time.md` giữ `IN_PROGRESS`**, không đánh DONE: tiêu chí "tắt mock, mọi màn gọi API thật" không đạt được khi BE còn thiếu (mục D).

## 0. Khung chung

| Route | Role | `<h1>` | Ghi chú |
|---|---|---|---|
| `/admin/reports` | SUPER_MANAGER, BRANCH_MANAGER | Báo cáo theo phạm vi | menu Quản trị › Báo cáo (UC89) |
| `/admin` → `/admin/dashboard` | SUPER_MANAGER, BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER | Bảng điều khiển | KPI theo vai trò (mục B.1) |
| `/admin/queue`, `/admin/exam`, `/admin/vaccination`, `/admin/grooming`, `/admin/invoices` | — | — | **chuyển hướng** theo vai trò (mục B.2), 5 trang mảng cứng đã xóa |

Tài khoản demo mock (mật khẩu `Petcare123`): `chuoi@petcare.vn` SUPER_MANAGER · `manager@store1.vn` BRANCH_MANAGER chi nhánh 1 · `manager2@store2.vn` BRANCH_MANAGER chi nhánh 2 (DRAFT) · `reception@store1.vn` · `doctor@store1.vn` · `caretaker@store1.vn` · `admin@petcare.vn`.

## A. Báo cáo (UC89)

### A.1 Màn hình (`pages/admin/AdminReportsPage.tsx`, `pages/admin/reports/*`)

- 6 tab cố định theo BR-BC-03 (danh mục **đóng băng**: không báo cáo tùy biến, **không xuất / lưu file**): **Doanh thu** (#1 theo chi nhánh × nguồn VISIT / RETAIL / BOARDING, kèm bảng **Thất thu** #2) · **Lượt dịch vụ** (#3 nhóm Khám/Tiêm, Thẩm mỹ) · **NO_SHOW & hủy muộn** (#4, tử/mẫu + %) · **Tái chủng** (#5 theo loại vaccine) · **Tồn kho** (#6 dưới ngưỡng + lô hết / sắp hết hạn, ô `withinDays` mặc định 30) · **Feedback** (#7). Chỉ tab đang mở truy vấn.
- Bộ lọc: ô chọn chi nhánh **chỉ SUPER_MANAGER** (mặc định "Toàn chuỗi"); BRANCH_MANAGER thấy tên chi nhánh mình bằng chữ và **không gửi `branchId`** (BR-BC-01). Kỳ: hai ô ngày + 5 nút (Hôm nay, 7 ngày qua, 30 ngày qua, Tháng này, Tháng trước), mặc định **Tháng này**; ngày tính theo giờ Việt Nam. Kỳ sai (từ > đến, > 12 tháng) hiện câu báo và **không gửi request**; lỗi của server vẫn hiện nguyên thông điệp. Tồn kho không theo kỳ nên ẩn ô ngày.
- Tỷ lệ API là số thập phân 0–1 (A3 hợp đồng): FE nhân 100, làm tròn 1 chữ số; **mẫu số 0 → "—"**; điểm trung bình "4,5 / 5", `null` → "—"; feedback không gắn chi nhánh → "Không gắn chi nhánh".
- Lớp nối: `shared/types/report.ts`, `shared/api/report.api.ts`, `shared/hooks/useReports.ts` (gốc khóa `['reports', tên, query]`, không tự hỏi lại khi focus).

### A.2 Mock `shared/api/mock/report.mock.ts` (BE chưa có → hợp đồng + BR là nguồn duy nhất)

Không trạng thái, không `localStorage`: mỗi request tính lại từ mock nguồn (`sales.mock` +`allOrderRows()`, `inventory.mock` +`stockSnapshot(today)`, `feedback.mock` +`feedbackRecords()`) và dữ liệu mẫu tất định. **Thứ tự kiểm:** 401 → 400 tham số (thiếu / sai kiểu = `MALFORMED_REQUEST`; `branchId` ≤ 0, `withinDays` < 0 = `VALIDATION_FAILED`) → 403 vai trò (chỉ SUPER_MANAGER, BRANCH_MANAGER) → 403 phạm vi (`ACCESS_DENIED_SCOPE_MISMATCH`, đuôi `(BR-BC-01)`) → 400 `BR-BC-01` (từ > đến, rồi kỳ quá dài).

**ASSUMPTION (đầu file mock, A1–A10):**
- A2 biên 12 tháng: hợp lệ khi `to ≤ from + 12 tháng` theo lịch (như `LocalDate.plusMonths`; `2025-10-27 → 2026-10-27` qua, `→ 2026-10-28` là 400); 12 là hằng số, mock không đọc `report.max_period_months`. **BE T44 có thể đếm khác: đối chiếu khi có BE.**
- A4 **doanh thu #1 = Order PAID thật của mock bán hàng + `BASELINE_REVENUE` cố định** (mock bán hàng chỉ có 1 Order PAID mẫu); số này **không khớp** màn thu ngân / phiếu thu.
- A5 thất thu #2 chỉ Order `cancelType = UNPAID` (BR-BH-05); **hủy phiên trả thú lưu trú (`abort-checkout`), hủy lượt, bán lẻ chưa chốt không tính** (BR-BC-02). Dữ liệu mẫu nằm vài ngày trước nên đầu tháng có thể rỗng.
- A6 #3, #4, #5 là dòng mẫu tất định theo "số ngày trước hôm nay", không khớp màn lịch hẹn / lượt khám; **không mô phỏng BR-BC-04** (7 ngày, thú mất).
- A7 tỷ lệ làm tròn 4 chữ số, mẫu số 0 → 0. A8 tồn kho: thiếu khi tồn khả dụng < ngưỡng (ngưỡng 0 không báo); lô liệt kê khi còn tồn > 0, có hạn và còn ≤ `withinDays` (gồm ngày thứ `withinDays`). A9 feedback: `averageRating` = trung bình feedback có điểm, `null` khi không có điểm nào; dòng `branchId: null` xếp cuối, chỉ SUPER_MANAGER toàn chuỗi thấy. A10 câu lỗi rule / phạm vi tự đặt.
- Lệch nhỏ đã biết: mock nhận `withinDays = 0`, FE chặn ≤ 0. Chi nhánh 2, 3 là DRAFT nhưng vẫn có doanh thu mẫu; tồn kho / feedback mẫu chỉ chi nhánh 1.

### A.3 Ca kiểm thử tay (1280 px, `VITE_API_MOCK` bật)

| # | Làm | Mong đợi |
|---|---|---|
| A1 | `chuoi@petcare.vn` → Báo cáo | có ô chọn chi nhánh ("Toàn chuỗi"); tab Doanh thu có số, tổng = tổng các dòng |
| A2 | `manager@store1.vn` → Báo cáo | không ô chọn chi nhánh, hiện tên chi nhánh mình; request không có `branchId` khác |
| A3 | đổi "Đến" trước "Từ" / kỳ 13 tháng | câu báo, không có request mới |
| A4 | chọn "Tháng trước", "30 ngày qua" | khoảng ngày đúng, số liệu đổi |
| A5 | tab NO_SHOW | % = tử / mẫu × 100; dòng mẫu số 0 hiện "—" |
| A6 | tab Tồn kho, `withinDays` = 0 hoặc rỗng | báo không hợp lệ, không request |
| A7 | tab Feedback | điểm TB "x,x / 5"; dòng không chi nhánh (chỉ SUPER_MANAGER) |
| A8 | `reception@store1.vn` mở `/admin/reports` | 403 tại chỗ, không gọi API báo cáo |
| A9 | `manager@store1.vn` → Bảng điều khiển | thẻ "Đã thu hôm nay" có số (từ report #1) |
| A10 | tìm nút "Xuất" / "Tải về" ở mọi tab | **không có** |

## B. Dashboard và route ẩn (bỏ `clinic-db`)

### B.1 KPI `/admin/dashboard` (nguồn hợp đồng; thẻ lỗi hiện "–" + "Không tải được. Thử lại", thẻ khác vẫn chạy)

| KPI | Nguồn | Vai trò thấy |
|---|---|---|
| Lượt tiếp nhận hôm nay | `useVisitQueue({date: hôm nay})` (visit-v1 #2) | BRANCH_MANAGER, RECEPTIONIST, VET, CARETAKER |
| Đang chờ / đang làm | `useVisitQueue({})` (dùng chung khóa với thanh bên) | như trên |
| Dịch vụ hôm nay (2 nhóm, không tính lượt hủy) | cùng dữ liệu | như trên |
| Đơn chờ thu tiền | `usePendingSalesOrders` (sales-v1 #2) | như trên |
| Đã thu hôm nay | `useRevenueReport({từ = đến = hôm nay})` (report-v1 #1, đúng BR-BC-02: lọc theo **ngày thu**, không theo ngày tạo) | SUPER_MANAGER (nhãn "toàn chuỗi"), BRANCH_MANAGER |

SUPER_MANAGER chỉ thấy thẻ doanh thu (không có quyền hàng đợi / đơn chờ thu, `GET /visits` 403). **Bỏ:** "Hoạt động gần đây" (không có nguồn hợp đồng), nút "Đặt lại dữ liệu demo" ở thanh bên (chỉ reset `clinic-db`). Phân bố dịch vụ còn 2 nhóm MEDICAL / GROOMING.

### B.2 Chuyển hướng (`RedirectByRole`, `replace`; vai trò không có đích → trang đầu của chính nó, không vào 403)

| Route cũ | RECEPT. | BRANCH_MGR | VET | CARETAKER | SUPER_MGR | ADMIN |
|---|---|---|---|---|---|---|
| `/admin/queue` | `/staff/reception` | `/staff/reception` | `/staff/doctor` | `/staff/grooming` | dashboard | `/admin/users` |
| `/admin/exam`, `/admin/vaccination`, `/admin/grooming` | `/staff/reception` | dashboard | `/staff/doctor` | `/staff/grooming` | dashboard | `/admin/users` |
| `/admin/invoices` | `/staff/reception` | **`/admin/payments`** | `/staff/doctor` | `/staff/grooming` | dashboard | `/admin/users` |

`/admin/invoices` tách theo vai trò vì v16 **không có hóa đơn**: lễ tân thu tiền ở bàn Lễ tân (UC70), quản lý đối soát ca ở "Ca thu ngân" (UC72). Bỏ mục menu "Hóa đơn" và 4 mục menu ẩn.

### B.3 `clinic-db`

Chuỗi nhập khẩu `clinic-db ← clinic.api ← useClinic ← (không ai)`; `useClinicSync`, `useResetDemo`, `useDashboardSummary` đã xóa; `errorMessage` chuyển sang `shared/utils/error-message.ts`. Test `App.no-clinic-db.test.tsx` ném lỗi nếu `App` còn nạp `clinic-db` / `clinic.api`. **Mã chết để dọn ở T57:** `hooks/useClinic.ts`, `clinic.api.ts`, `clinic-db.ts`, `QUERY_KEYS.dashboard`, kiểu `DashboardSummary` / `ActivityEntry` / `ActivityKind`, `KIND_LABEL`.

**Trang mảng cứng còn route** (ngoài phạm vi v16 hoặc tầng 3, chưa động): `/admin/orders` (UC65), `/admin/refunds` (UC68), `/admin/promotions`, `/admin/membership`, `/admin/incidents` (UC86), `/admin/workforce` (UC34–38), `/admin/ai`; file mồ côi `AdminPurchasingPage.tsx`, `AdminVaccinesPage.tsx`.

## C. Đối chiếu FE ↔ code BE thật

Cách làm: hai agent chỉ đọc so từng endpoint **BE đã cài** với `*.api.ts`, kiểu, hook và trang FE (đường dẫn, query, body, response, nullable, enum, mã lỗi, quyền). Phủ: **201 endpoint** (identity 34, appointment 9, visit 20, care 4; branch 21, catalog 22, customer 21, sales 19, inventory 17, boarding 12, content 22). Script một lần: 134 route BE khớp mọi URL FE (path + method), 65 cặp interface ↔ record khớp tên trường (trừ `AccountSummary.branchId`), 15 cặp `*Query` ↔ `@RequestParam` khớp. **Chỉ đọc code, chưa chạy BE / DB, chưa `git fetch`** nên `origin/dev` có thể có thêm endpoint. Một số endpoint chỉ đọc qua controller (visit #1, #5–9, #11; appointment #1, #2, #5–9; identity #1–3, #6–7, #11–14, #18–24).

### C.1 Đã sửa (FE + mock cho khớp BE)

| Mức | Lệch | Sửa |
|---|---|---|
| **CHẶN** | `POST /auth/login` **không trả `branchId`** (chỉ `GET /me` → `staffProfile`); FE đọc `account.branchId` → lễ tân **không mở được ca → `POST /payments` 400 `BR-TG-01` → không thu tiền được**; mất "Đặt tại quầy", "Tạo chuồng", "Đặt lịch hộ". Mock che vì có `branchId` | `useStaffBranch()` (+ `isPending`), `login` gộp `staffProfile.branchId` vào phiên, đổi `useShiftOptions`, `AdminBoardingPage`, `AdminKennelsPage`, `AdminAppointmentsPage`, `AdminUsersPage`; trang chuồng chỉ báo "chưa gắn chi nhánh" khi `GET /me` cũng không có |
| **CHẶN** | BRANCH_MANAGER tạo nhân viên luôn gửi `branchId: 0` → 400 `@Positive` | BRANCH_MANAGER không gửi `branchId`; SUPER_MANAGER chưa chọn thì không gửi (BE báo `BR-QT-03`) |
| Sai hiển thị | Người dùng / Tài khoản / Kiểm toán không phân trang (chỉ 20 / 20 / 50 dòng đầu); mock không cắt trang | `components/admin/Pager.tsx`, gửi `page`/`size`; mock `identity-admin` cắt trang (mặc định 20, kẹp 1–100) |
| Sai hiển thị | Kiểm toán lọc loại đối tượng bằng `Staff/Account/…` nhưng BE lưu **tên bảng**; lọc `action` không kèm người thực hiện cần `from+to ≤ 31 ngày` | 16 loại = tên bảng, 30 mã hành động, cổng 31 ngày ở FE; mock audit đổi theo BE |
| Sai hiển thị | Bộ lọc Người dùng hiện vai trò không thấy được, `PENDING`, ô chi nhánh với ADMIN; ô tìm Tài khoản ghi "tên hoặc email" (BE chỉ email) | lọc theo `visibleRoles` của BE; "Tìm theo email" |
| Sai hiển thị | PATCH sản phẩm / dịch vụ: xóa trắng mô tả → FE bỏ trường, BE coi vắng = giữ nguyên | sửa thì gửi `""` khi xóa trắng (chỉ khi khác bản gốc); mock `!= null` = giữ |
| Sai hiển thị | Ngày "hôm nay" theo giờ **máy** (`localIsoDate`), BE theo Asia/Ho_Chi_Minh; phiếu nhập ngày tương lai chỉ bị chặn lúc xác nhận | `localIsoDate = vnDate`; ô ngày phiếu nhập có `max`; mock xác nhận thêm lỗi này, 409 trước `BR-KO-02/03` |
| Sai hiển thị | Xóa mũi tiêm: FE chỉ cho người ghi, BE cho **VET phụ trách hiện tại** (ADR-0075); `BR-KB-03` dùng chung 3 ca nên khung "Giảm / Mua ngoài" hiện sai; hết phác đồ BE gợi ý mũi lớn nhất (không `null`); xóa mũi vướng Care Task = 409 | nút Xóa theo `canVaccinate`; khung chỉ khi `/chỉ còn \d+/`; "Đã hết phác đồ, gợi ý nhắc lại mũi N"; câu 409 riêng; mock `visit-clinical` chép BE (400 `BR-KB-04`, `lotNumber`/`expiryDate` luôn `null` D046, sắp sản phẩm theo tên…) |
| Nhẹ | `LoginPage` / `ChangePasswordPage` hiện cả đuôi mã rule; 409 khi đổi giờ / hủy lịch hiện tên trạng thái tiếng Anh | `plainMessage`; "Lịch hẹn đã được tiếp nhận hoặc thay đổi, vui lòng tải lại." + làm mới |

### C.2 Chưa sửa (BE-note hoặc để sau)

| Lệch | Ghi chú |
|---|---|
| `GET /api/staff` đóng với RECEPTIONIST (`StaffController` `STAFF_ADMINS`), trong khi chỉ lễ tân được gán lượt | **BE-1** (đã ghi ở `time.md` mục Đang chặn). FE đã bắt (`staffPickerBlocked`, T36 A.7); mock **cố ý giữ mở** cho lễ tân để test bàn Lễ tân chạy |
| `Booking.activeOrderId` luôn `null` (`BoardingAssembler.java:79`) | **BE-2, T43**: điền khi `start-checkout`, nếu không bước Order của "Trả thú" đứng (`CheckoutDialog.tsx:45`) |
| `end-maintenance` trên chuồng `OCCUPIED` trả 200 (hợp đồng: 409) | BE-2 thêm guard `status == MAINTENANCE` (FE chỉ hiện nút cho `MAINTENANCE`) |
| Hợp đồng `inventory-v1` #8/#9 chưa nêu "ngày nhập không được ở tương lai" (`StockReceiptService.java:301`) | ghi vào hợp đồng hoặc BE chặn từ lúc tạo; `docs/api/00-method.md` §3.5 nên ghi boarding / kho kiểm 409 trước 400 |
| Ô chọn sản phẩm (phiếu nhập, điều chỉnh, kê đơn) lấy `size=100`, không tìm kiếm | sai khi > 100 sản phẩm đang kinh doanh |
| SĐT hồ sơ tại quầy 11 số: BE cho, FE chặn 10 số; toạ độ / cân nặng quá chữ số thập phân FE không kiểm | nhẹ, tùy chọn |
| `visit` #9 reassign cho SUPER_MANAGER: BE cho, FE không có màn; `#17` danh sách "Đã khám hôm nay" của bác sĩ lấy cả chi nhánh | hỏi PO |
| Danh sách khác không gửi `size` **có thể chỉ thấy 20 dòng đầu** trên BE thật (mặc định BE 20 đã thấy ở staff, accounts; mock trả hết) | chưa rà từng trang; chỉ 3 màn quản trị đã sửa |
| `sales.mock.ts` (`new Date(\`${ngày}T00:00:00\`)`), `boarding.seed.ts` còn trộn ngày VN với nửa đêm giờ máy; `customer-pet.mock.ts:168` còn `lotNumber` mẫu | dọn ở T57 |
| `envelope.page()` ở các mock khác trả `totalPages ≥ 1` cho danh sách rỗng (BE: 0) | nhẹ |

## C.3 Chạy với BE thật (tối 27/10/2026)

Máy dev sau khi bạn đóng game client: Docker Desktop + Postgres tạm `petcare-seed-pg` (cổng 5433, DB `petcare_seed`, đã seed từ 24/10, Flyway V21), **BE chạy ở cổng 8082** (cổng 8081 đang bị container `petcare-api` bản cũ của người dùng giữ, không đụng), FE `VITE_API_MOCK=false VITE_API_URL=http://localhost:8082 npx vite`. Đăng nhập thật qua `/auth/login` (trang `/admin/login` chuyển về đó khi tắt mock).

**Xác minh bằng request thật (khớp đối chiếu tĩnh):** `POST /auth/login` không trả `branchId` (`GET /me` → `staffProfile.branchId = 1`); `GET /staff` → ADMIN / SUPER_MANAGER / BRANCH_MANAGER 200, **RECEPTIONIST và VET 403**; `GET /visits` → SUPER_MANAGER 403; `GET /kennels` → VET 403; `/reports/*` 404; `/care-tasks` 404; `/pets/{id}/health-record` 404; `/me/notification-settings` 404; `GET /accounts?size=5` → `page 0, size 5, total 9, pages 2`; `GET /staff` mặc định `size = 20`.

**Quét 6 vai trò × ~35 trang (Playwright, đăng nhập thật):** mọi trang mở được đúng `<h1>`, **không có 403 nào, không có lỗi trang (`pageerror`)**. Chỉ có 404 đã biết: `/api/reports/revenue`, `/lost-revenue` (dashboard SUPER_MANAGER / BRANCH_MANAGER và trang báo cáo: hiện "Không tải được báo cáo … Thử lại" và thẻ "–", đúng thiết kế), `/api/care-tasks` (lễ tân). Bàn Lễ tân với phiên không có `branchId`: panel ca thu ngân **tải được giờ mở cửa và hiện "Mở ca ngoài giờ"** (ngoài giờ), thay vì báo "Không tải được giờ mở cửa" như trước khi sửa `useShiftOptions`.

Phát hiện phụ: dashboard gọi `GET /api/reports/revenue` hai lần liền khi 404 (**chưa rõ nguyên nhân**: không phải thử lại tự động vì `shouldRetry` không thử lại 4xx và dashboard chỉ có một chỗ gọi; có thể do `StrictMode` ở dev, chưa kiểm); lượt `WAITING` cũ ngày 24/10 trong DB seed làm thanh bên hiện "Đang chờ 7".

## C.4 Luồng chính trên giao diện thật (FE ↔ BE 8082, Playwright 1280 px, 23:09–23:45)

Chi tiết từng bước (kỳ vọng · thực tế · request / response · phân loại): `docs/superpowers/plans/2026-10-27-t49-live-flow.md`. **Không có lỗi chức năng FE↔BE mới ở luồng chính**; `t49-be.log` không có `ERROR`, không có 5xx cả phiên. Chi nhánh 1 **ngoài giờ mở cửa suốt phiên** nên tiếp nhận không cấp cứu bị `BR-CN-05` và đặt lưu trú nhận hôm nay bị `BR-LT-04`: đúng luật, UI hiện đúng câu.

| Luồng | Kết quả trên BE thật |
|---|---|
| Lễ tân | đăng nhập lấy `branchId` từ `GET /me`, không 403; mở ca ngoài giờ 201; tiếp nhận cấp cứu 201; hàng chờ đúng thứ tự ưu tiên; "Gán người" bị `GET /staff` 403 (BE-note đã biết) nên gán qua `POST /visits/{id}/assign` 200; "Đưa lên trước", "Hủy lượt", "Cấp cứu" 200 |
| Bác sĩ | gọi lượt; hoàn tất khi chưa chẩn đoán → `BR-KB-02` hiện đúng câu; SOAP + hẹn tái khám, cân nặng; **tiêm chủng**: gợi ý mũi 1 → ghi mũi 1 (kho 20→19) → chọn sẵn mũi 2 → "Đã hết phác đồ, gợi ý nhắc lại mũi 2" → xóa mũi 204 (VET phụ trách hiện tại); **kê đơn**: đủ tồn 201, 800 vs tồn 700 → `BR-KB-03` + khung "Giảm / Mua ngoài / Hủy", "Giảm" 201, "Mua ngoài" 201 (`isExternalPurchase`, không dòng Order); hoàn tất 200 → Order `PENDING` 450.000 ₫; bản bổ sung 201 |
| Thu ngân | thu tiền mặt thối 50.000 → 201; Order `PAID`; kho FEFO đúng (lô hạn sớm 200→190); chốt ca 200; `hung.tran` đối soát `RECONCILED` |
| Thẩm mỹ | Bắt đầu / Xong 200; thu chuyển khoản qua API 201 |
| Lưu trú | tạo 4 chuồng không gửi `branchId` 201; trùng mã `BR-LT-01`; bảo trì / hết bảo trì; đặt tại quầy 28→30/10 201, gia hạn, hủy 200; nhận / trả thú, nhật ký 404 (BE chưa có) |
| Quản trị | BRANCH_MANAGER tạo nhân viên **không có `branchId`** → 201, `branchId = 1`; Người dùng "Trang 1/2 · 22 nhân viên"; Tài khoản "1/2 · 25"; Kiểm toán "1/4 · 176"; lọc `entityType=orders` ra 3 dòng `ORDER_PAID`; lọc `action` kèm ngày ra 16 dòng, thiếu ngày thì FE chặn; `entityType` thật là tên bảng |
| Khách | đăng ký → OTP (Mailpit) → `ACTIVE` → đăng nhập; `/booking` → `POST /appointments` 201; lịch bị lễ tân hủy nơi khác: "Hủy lịch" / "Đổi giờ" nhận 409, UI hiện "Lịch hẹn đã được tiếp nhận hoặc thay đổi, vui lòng tải lại." (đúng chỗ T49 đã sửa) |
| Quét trang | 34 trang, 5 vai trò, dữ liệu thật: không `pageerror`, không `console.error`, không 4xx / 5xx mới ngoài 404 đã biết |

**Lỗi FE thuần tìm thấy và đã sửa (2):** `AdminAccountsPage` ô lọc "Vai trò" gõ cứng nhãn riêng ("Phụ tá", "Giám đốc vận hành") khác cột Vai trò → dùng `ROLE_LABELS`; `AdminNotificationTemplatesPage` mô tả hộp thoại rò chữ "undefined" khi chưa mở. Mỗi cái một test mới.

**BE-note mới từ luồng thật:** (1) `boarding-availability` báo còn chỗ cho ngày nhận **hôm nay ngoài giờ** nhưng `POST /boarding-bookings` trả 400 `BR-LT-04` — BE-2 nên cho availability trả `available:false` kèm lý do (FE cũng có thể mặc định ngày nhận = ngày mai khi ngoài giờ, chưa làm); (2) seed dev chỉ có 1 dòng phác đồ (chó 5 bệnh mũi 1), không có phác đồ mèo / vaccine mèo; đã thêm 2 dòng phác đồ mèo 4 bệnh bằng API vào DB tạm; (3) `POST /me/pets` thiếu `sex` → 400 (hợp đồng bắt buộc `sex`); (4) login không trả `branchId` — nên ghi vào hợp đồng hoặc trả thêm.

**Chưa làm trong luồng thật:** xóa mũi bởi VET phụ trách **mới** sau gán lại (visit #9 chỉ cho khi người cũ không đủ điều kiện); tiếp nhận từ lịch hẹn (cửa sổ check-in 30 phút trước giờ hẹn, đang ngoài giờ); thu thiếu tồn (BR-BH-04) và chuyển dòng sang mua ngoài (sales #11); khách trùng SĐT / email với hồ sơ tại quầy (BR-TK-19); danh sách tự làm mới sau 409 của khách; đối soát ca với số đếm khác số đã chốt.

**Dữ liệu để lại trong DB tạm `petcare_seed`** (chỉ DB này): 2 dòng phác đồ mèo; 4 chuồng (N-01, N-02, M-01, L-01); 16 nhân viên thử (`thu.nguyen@`, `qa.nv01…15@petcare.local`, CARETAKER); khách `khach.thu@petcare.local` (thú Kem; lịch 0006 `BOOKED` 29/10); lượt 14, 15, 16 `COMPLETED`, lượt 9 `CANCELLED`; Order 15, 16 `PAID`; phiếu thu PT-2610-00002/3; ca thu ngân 2 `RECONCILED`; đặt chỗ LT-2610-00001 `CANCELLED`; kho Felocell 19, Vanguard 29, Amoxicillin AMX2405 190.

## C.5 Sau khi merge `origin/dev` (28/10/2026): Care Task thật

Merge `origin/dev` (T39 của BE-1: Care Task, ST04, ST18, ST19, `FollowUpApi`, `V22`, `V23`, ADR 0079–0084; 3 commit, 116 file, **không file FE**). Kiểm trên cây đã gộp: BE `mvn test` **5320 / 5320, BUILD SUCCESS**; FE tsc / lint sạch; `V22`, `V23` áp dụng thành công trên DB `petcare_seed` đã có dữ liệu thử.
- **Care Task đã có ở BE** (care-v1 #1–4): RECEPTIONIST 200, BRANCH_MANAGER và VET **403** (đúng thiết kế: chỉ lễ tân, chi nhánh của mình). Kiểu dữ liệu FE T47 khớp DTO BE (20 trường, enum, giới hạn 2000 / 300).
- **Tạo dữ liệu bằng nghiệp vụ thật:** check-in cấp cứu Miu (khách Lê Thị Hoa, **không có email**) → gán VET → gọi → ghi bệnh án có `followUpDate` 02/11 → hoàn tất → job ST04 (`FollowUpReminderJob`, cron `30 * * * * *` đặt qua biến môi trường để chạy mỗi phút; mặc định 07–20h) sinh Care Task `FOLLOW_UP_DUE` (khách có email / tài khoản thì job gửi thông báo, không sinh task). Cửa sổ nhắc là `reminder.follow_up_days_before` (mặc định 3 ngày, [CFG] 1–14; đã tạm nâng lên 7 rồi khôi phục).
- **FE ↔ BE thật:** `/admin/care-tasks` hiện đúng hàng (hạn, khách + SĐT, loại "Tái khám", thú, ngữ cảnh, "Đang chờ"), không 4xx; "Đã gọi" → chọn kết quả + ghi chú → `POST /api/care-tasks/1/complete` 200, danh sách làm mới.
- **Chưa chạy thật:** hủy Care Task (`/cancel`), `VACCINE_DUE` / `VACCINE_OVERDUE` (ST04 tái chủng, ST18: cần mũi tiêm tới hạn / quá hạn), `PICKUP_OVERDUE` (chờ ST15, T43), hủy tự động khi thú mất / chuyển chủ (ST19).

## C.6 Cụm 3: đặt lịch → tiếp nhận (28/10/2026)

Chạy thật BE 8082 + FE 5173 trên DB `petcare_seed`: 11 ca `C3-01…C3-11` qua API và giao diện (Playwright) không có lệch FE ↔ BE cần sửa FE; không đổi code FE (vitest 15 file / 196 ca của cụm xanh). BE-note: `GET /api/staff` 403 với lễ tân (hộp gán người chờ BE-1). Chi tiết ca, dữ liệu `[TEST C3]` và kết quả: `docs/testcases/test-tay-ghep-api-theo-cum.md` mục Cụm 3; nhật ký tiến độ ở `docs/be-fe-integration.md` mục 3.

## C.7 Cụm 5: thu ngân và bán lẻ (28/10/2026)

Chạy thật BE 8082 + FE 5173 trên DB `petcare_seed`: 9 ca `C5-01…C5-09` qua API và giao diện (Playwright). **Lỗi FE tìm thấy và sửa:** quản lý chi nhánh thấy hai lối vào hồ sơ khách trong bàn lễ tân (khung "Bán lẻ" và nút "Khách hàng") nhưng BE trả 403 cho `GET /api/customers` và `POST /api/orders`; `RetailOrderPanel` nay tách theo vai trò như `CheckoutPanel`, `ReceptionWorkspacePage` ẩn nút "Khách hàng" (test mới ở `RetailOrderPanel.test.tsx` và `ReceptionWorkspacePage.test.tsx`). BE-note: sales #9, #11 404 (BE-2). Chi tiết ca, dữ liệu và kết quả: `docs/testcases/test-tay-ghep-api-theo-cum.md` mục Cụm 5; nhật ký ở `docs/be-fe-integration.md` mục 3.

## C.8 Cụm 8: nội dung công khai (28/10/2026)

Chạy thật BE 8082 + FE 5173 trên DB `petcare_seed`: 6 ca `C8-01…C8-06` qua API và giao diện (Playwright) đều đạt; **không có lệch FE ↔ BE cần sửa FE**, không đổi code FE (nên không chạy lại tsc / lint / vitest). 11 trang công khai tải được, 0 4xx / 5xx ngoài hai 404 có chủ ý (`/news/<slug lạ>`, `/shop/999999`). BE-note: `PUT` nội dung trang chỉ kiểm "là object" và ≤ 64 KB, không kiểm cấu trúc (`{"foo":1}` cho ABOUT vẫn 200, trang công khai lặng lẽ về mặc định cả khóa); tên chuyên mục phân biệt hoa / thường; thông điệp 409 dùng tên entity tiếng Anh ("Article", "Feedback"); bài đã từng xuất bản và feedback không xóa được qua API (`BR-BV-02`). Ghi nhận ngoài BE: modal chung `components/ui/modal-templates.tsx` không đóng bằng Esc và không chuyển focus vào hộp thoại; `/help` FAQ cứng; `/about` và footer dùng số liệu / địa chỉ mặc định trong mã (lệch chi nhánh thật). Dữ liệu test đã dọn sạch (0 bài, 0 chuyên mục, 0 feedback, nội dung trang `{}`, `feedback.max_per_day` = 5). Chi tiết ca, dữ liệu và kết quả: `docs/testcases/test-tay-ghep-api-theo-cum.md` mục Cụm 8; nhật ký ở `docs/be-fe-integration.md` mục 3.

## C.9 Cụm 2: nền, danh mục, chi nhánh, kho, khách (chạy thật 29/10, sửa FE 30/10/2026)

**Cách chạy.** BE thật `http://localhost:8082` trên DB tạm `petcare_seed` (Postgres 5433); FE `VITE_API_MOCK=false` ở cổng **5174** (không dùng 5173 vì không rõ chế độ), Playwright; mỗi ca ABORT nếu 0 response từ 8082. **CORS:** BE chỉ cho origin `http://localhost:5173` và `http://localhost:3000` (`CORS_ALLOWED_ORIGINS`), nên FE ở 5174 trượt preflight; Playwright mở Chrome với `--disable-web-security` (request vẫn đi thật tới 8082). Chạy tay: dùng cổng 5173. Docker Desktop không tự bật container sau khi khởi động lại máy: `docker start petcare-seed-pg petcare-mailpit petcare-redis`. Kịch bản, `*-result.json`, `evidence-*.json`, `snapshot-*.json` và ảnh ở `D:/Do-an/.c2-tmp/{branch,inventory,customer}/` (ngoài repo; ba mảng chi nhánh, kho, khách). Mỗi ca có hàng chục đến hàng trăm response từ 8082, không ca nào abort.

**Kết quả 9 ca `C2-01…C2-09`: đều đạt, mức L** (bước và mong đợi: `docs/testcases/test-tay-ghep-api-theo-cum.md` mục Cụm 2).

| Ca | Kết quả chạy thật |
|---|---|
| C2-01 mô tả rỗng | tạo sản phẩm để trống mô tả → không gửi `description`, DB NULL; xóa trắng → gửi `""`, DB `''`, mở lại ô rỗng |
| C2-02 danh mục (giao diện) | số dòng UI = API = DB (5 loại vaccine, 3 phác đồ, 7 dịch vụ); tạo / sửa / ngừng / xóa dòng thử; trùng → 400 hiện đúng câu. Bỏ vế "bàn khám gợi ý mũi 1 mèo" (là C4-11) |
| C2-03 giờ mở cửa, ngày nghỉ, dịch vụ, quota | "Ngày hiệu lực" mặc định và `min` = ngày mai ở cả 3 múi giờ trình duyệt; nhập hôm nay → trình duyệt chặn, API 400 `BR-CN-04`; xem trước ảnh hưởng đúng (1 lịch `BOOKED` mỗi ngày kiểm); thêm ngày nghỉ an toàn 08/12 (0 lịch) rồi xóa; tắt / bật "Cắt tỉa lông"; quota GROOMING 2→4→2, MEDICAL 3→4→3, quota riêng khung; FE chặn -1 và 40000 |
| C2-04 tồn theo lô | `hung.tran` và `mai.le`; lô **đã** hết hạn có badge "Có lô hết hạn"; hàng dưới tồn tối thiểu có badge (8 sản phẩm demo đều tồn tối thiểu 0 nên chưa hàng demo nào bị đánh dấu) |
| C2-05 phiếu nhập | nháp → xác nhận tăng đúng lô; trùng số lô + hạn thì cộng vào lô cũ; ngày tương lai bị trình duyệt chặn (`max`), 0 POST; nhà cung cấp ngừng hợp tác bị chặn ở mọi ngả; hủy phiếu đã nhập đúng `BR-KO-04` (+ nút "Lập phiếu điều chỉnh") |
| C2-06 điều chỉnh tồn | có lý do, tồn đổi, có dòng lịch sử; vượt tồn bị từ chối ở FE (0 POST) và ở BE; BE từ chối khi tồn đã đổi sau lúc mở form |
| C2-07 khách quầy, thú | `mai.le` tạo khách (SĐT 10 số), cảnh báo trùng `BR-KH-10` (liệt kê hồ sơ nghi trùng, nút "Mở"), thêm thú, trùng tên + loài chỉ cảnh báo, ảnh `http://` bị BE 400, tra theo SĐT / họ tên / tên thú / email đúng (8 phép tìm khớp `count(*)`), xóa thú chưa giao dịch 204 (`BR-KH-06`), thú có bệnh án không xóa được và khóa đổi loài (`BR-KH-03`). **Lệch tài liệu:** lễ tân không có đường ghi cân nặng (xem BE-note) |
| C2-08 thú mất | hai đường (lễ tân, chủ thú tự đánh dấu); đối chiếu SQL: lịch hẹn và đặt chỗ lưu trú `BOOKED` → `CANCELLED`, `cancel_source=CLINIC`, `late_cancel=false`, `booking_restrictions` không tăng; Care Task `FOLLOW_UP_DUE` `OPEN` → `CANCELLED`; thông báo EMAIL + IN_APP đã gửi. Âm tính: sửa tên, xóa, chuyển chủ, đặt lịch / lưu trú, tiếp nhận (`BR-TN-01`), cân nặng đều bị chặn; đánh dấu lần 2 → 409 |
| C2-09 hồ sơ sức khỏe | 404 "Không tìm thấy đường dẫn yêu cầu" trong tab (T28b, đã biết); các tab khác chạy; nút trên UI tên "Sức khỏe", chi tiết thú mở bằng ngăn kéo |

**Vai trò (HTTP khớp `@PreAuthorize`, không có lối vào chết).** Chi nhánh: `ha.nguyen` (SM) thấy danh sách + 3 tab, không có nút "Đặt giờ mở cửa mới" / "Thêm ngày nghỉ", checkbox dịch vụ khóa, tab Quota ẩn (mở thẳng `/quota` tự về `/hours`); `hung.tran` (BM) đủ 4 tab và nút sửa; `mai.le`, `khoi.vo`, `tai.bui`, `admin` không có menu "Chi nhánh", route ra khung 403. Danh mục `/admin/catalog`: chỉ SM; `hung.tran`, `mai.le`, `khoi.vo`, `admin` → khung 403, không nút "Thêm", API ghi 403. Kho `/admin/warehouse`: BM đủ 4 tab; lễ tân chỉ tab "Tồn kho" (xem lô, không nút tồn tối thiểu; gõ `/receipts` tự về `/stock`); SM, bác sĩ, chăm sóc → khung 403; ma trận API 16 dòng, 0 sai kỳ vọng. Khách / thú: `GET /customers` lễ tân, bác sĩ, chăm sóc 200, BM, SM, `admin`, khách 403; tạo / sửa khách, thú, chuyển chủ chỉ lễ tân (đánh dấu mất và xóa thú: thêm chủ thú); `POST /pets/{id}/weights` chỉ khách chủ; BM, SM, `admin` mở `/staff/reception`, `/admin/customers` → khung 403; BM ở quầy lễ tân không có nút "Khách hàng" (sửa ở cụm 5).

**Lỗi tìm thấy và đã sửa (30/10, kèm test, chưa commit).** Phát hiện chính: tab hồ sơ sức khỏe 404 treo 7,4 giây vì thử lại 3 lần; tìm khách quầy giữ dòng cũ.

| Mã | Loại | Lỗi | Sửa ở (`FE/src/…`) |
|---|---|---|---|
| K1 | FE | `shouldRetry` chỉ nhận `ApiError` mà lỗi thật là `AxiosError`: 4xx bị thử lại 3 lần (1 + 2 + 4 giây), tab "Hồ sơ sức khỏe" 404 treo 7,4 giây; ảnh hưởng mọi `useQuery` | `shared/constants/realtime.ts` (+ `realtime.test.ts`) |
| K2 | FE | ~13 hook gọi `shouldRetry(count, toApiError(error))`: lỗi mạng thành `ApiError` status 0 rơi nhánh `< 500`, KHÔNG BAO GIỜ retry (ngược comment) | cùng file: `statusCode > 0 && statusCode < 500` |
| K3 | FE | `QueryClient` "thắng" ở `App.tsx` không đặt `retry`, hook thiếu `retry` dùng mặc định 3 lần kể cả 4xx | `app/App.tsx` (`defaultOptions.queries.retry = shouldRetry`) + `App.queryClient.test.tsx`; sửa 4 ca test phụ thuộc hành vi cũ ở `AdminCareTasksPage.test.tsx`, `BoardingDetailPage.test.tsx` |
| K4 | FE | Panel tìm khách quầy: gõ đủ 10 số rồi xóa bớt, dòng khách cũ vẫn hiện (có thể mở nhầm hồ sơ); không gửi `size`, BE cắt im lặng ở 20 | `components/staff/reception/customers/CustomerSearchPanel.tsx` (`!waitingForPhone`; dòng nhắc "Đang hiển thị X / Y kết quả, hãy thu hẹp từ khóa") + `CustomerLookup.test.tsx` |
| K5 | FE | `useCustomerLookup` (dùng ở `CustomerLookup`, `RetailOrderPanel`, `TransferOwnerDialog`, `BookOnBehalfDialog`): `keepPreviousData` vẫn trả dữ liệu cũ khi truy vấn tắt (có thể chuyển chủ / đặt hộ nhầm); không gửi `size` | `domains/pets/hooks/useCustomerPet.ts` (`size '100'`, `placeholderData` chỉ khi `enabled`) + test; test mới `TransferOwnerDialog.test.tsx`, `BookOnBehalfDialog.test.tsx` |
| K6 | FE | Phiếu điều chỉnh tồn cho chọn 2 dòng cùng lô (FE cộng dồn, BE luôn 400 `lotsDistinct`) | `pages/admin/warehouse/AdjustmentForm.tsx` (báo "Dòng i: lô đã chọn ở dòng j", không POST) |
| K7 | FE | Phiếu nhập nháp giữ NCC đã ngừng hợp tác, lưu / xác nhận luôn 400 | `ReceiptForm.tsx` (báo nội tuyến + khóa "Lưu nháp"), `ReceiptsTab.tsx` (ghi chú + khóa "Xác nhận nhập kho"), `components/ui/modal-templates.tsx` (thêm prop `submitDisabled`, mặc định false) |
| K8 | FE | Ô chọn lô giữ tồn cũ sau khi BE từ chối (mutation chỉ làm mới cache `onSuccess`) | `shared/hooks/useInventory.ts` (`onSettled`) + `useInventory.test.tsx` (mới) |
| K9 | Mock che lỗi | Mock kho dễ dãi hơn BE: cộng dồn lô trùng; câu `BR-KO-02`, `BR-KO-06` khác BE | `shared/api/mock/inventory.mock.ts` (+ test): lô trùng → 400 `lotsDistinct`; `BR-KO-02` tạo / sửa nháp: "…đã ngừng hợp tác, không chọn được ở phiếu nhập mới", xác nhận dùng câu ngắn đúng BE; `BR-KO-06`: "Không giảm quá tồn của lô: lô <số lô> (hạn <ngày>) còn N, giảm M" |

File FE đổi: `shared/constants/realtime.ts`, `app/App.tsx`, `domains/pets/hooks/useCustomerPet.ts`, `components/staff/reception/customers/CustomerSearchPanel.tsx`, `pages/admin/warehouse/{AdjustmentForm,ReceiptForm,ReceiptsTab}.tsx`, `components/ui/modal-templates.tsx`, `shared/hooks/useInventory.ts`, `shared/api/mock/inventory.mock.ts` và các file test đi kèm (`realtime`, `App.queryClient`, `useCustomerPet`, `CustomerLookup`, `TransferOwnerDialog`, `BookOnBehalfDialog`, `useInventory`, `AdminWarehousePage`, `inventory.mock`, `AdminCareTasksPage`, `BoardingDetailPage`).

**Kiểm FE (30/10):** `tsc`, `eslint` và vitest toàn bộ: tsc sạch (exit 0); lint sạch (exit 0, --max-warnings 0); vitest toàn bộ 163 file / 3279 ca xanh, chạy 3 lần liền đều exit 0, không dính lỗi chập chờn GSAP.

**Bẫy `size=20`.** Danh sách kho, sản phẩm, phiếu nhập, điều chỉnh, chi nhánh, danh mục đều truyền `size` rõ ràng; FE hiện đúng `count(*)` (chưa vượt 20 dòng nên bẫy chưa kích hoạt; phân trang thử bằng cách ép `size` nhỏ qua `page.route`, chạy đúng). Riêng tìm khách (`useCustomerLookup`) trước đây không gửi `size`: đã sửa ở K4, K5.

**BE-note mới (không phải lỗi FE).**
- CORS mặc định chỉ 5173 và 3000.
- `POST /vaccination-protocols` nhận mũi 3 khi chưa có mũi 2 (201): không kiểm tính liên tục của số mũi.
- Câu `BR-CN-03` (ngày nghỉ trùng) dùng ngày ISO ("Ngày 2026-12-08 đã là…").
- `addHoliday` chỉ chặn quá khứ nên thêm được ngày **hôm nay** (hủy cả ngày, không hoàn tác); `deleteHoliday` chỉ xóa từ ngày mai.
- Không có DELETE cho phiên bản giờ mở cửa, phác đồ, dịch vụ (chỉ ngừng thay cho xóa): bản giờ mở cửa tương lai lỡ lưu chỉ ghi đè được cùng ngày hiệu lực.
- SM `PATCH /branches/1` với `{}` trả 200, không đổi trường nào.
- `PATCH` sản phẩm với `description:""` lưu `''` (không NULL; sản phẩm seed là NULL). Sản phẩm mới đang bán hiện ngay ở `/shop` (7 → 8) dù chưa có tồn (`inStockBranchIds:[]`).
- Hai đường cân nặng không đồng nhất: khách `POST /pets/{id}/weights` `4.555` → 400 `@Digits`, bác sĩ `POST /visits/{id}/weight` `4.555` → 201 làm tròn 4,56. Biên 0, âm, 10000 bị chặn, 9999,99 nhận. Lễ tân không có đường ghi cân nặng.
- Tra cứu tên khách không phân biệt hoa / thường nhưng phân biệt dấu; `GET /customers` không điều kiện → 400; không có trang "xem tất cả khách".
- Lỗi 400 (validation) luôn chạy trước 403 (muốn kiểm vai trò phải gửi body hợp lệ). BE giới hạn `size` tối đa 100 ở inventory, receipts, adjustments, products, customers.
- `GET /api/reports/revenue` 404 mỗi lần mở dashboard BM / SM (T44, đã biết). Đã biết: BE nhận phiếu nhập nháp ngày tương lai (PUT 200), chỉ chặn lúc xác nhận `BR-KO-03`.

**Ghi nhận, CHƯA sửa (cần quyết hoặc ngoài phạm vi).**
- **SĐT:** FE chỉ nhận `^0\d{9}$` + `maxLength=10` (ký tự thứ 11 bị cắt im lặng), BE nhận `^0\d{9,10}$` (`docs/06-module-contracts.md` G6: hồ sơ tại quầy 10–11 số). Cần chốt tài liệu 10 số hay sửa FE (cũng là dòng "SĐT hồ sơ tại quầy 11 số" ở mục C.2).
- Chưa có badge "sắp hết hạn" trên bảng tồn: chỉ lô **đã** hết hạn có badge đỏ.
- Modal chung `components/ui/modal-templates.tsx` không đóng bằng Esc (đã ghi ở C.8). `formatDate("yyyy-MM-dd")` (`lib/utils.ts`) parse thành UTC, lệch 1 ngày ở trình duyệt phía tây UTC (không ảnh hưởng người dùng VN).
- Khi xem trước ra 0 lịch ảnh hưởng, "Kiểm tra và thêm" ngày nghỉ / "Kiểm tra và lưu" giờ mở cửa ghi luôn, không hiện "0 lịch" (panel chỉ hiện khi ≥ 1): hợp lý, ghi nhận UX. SM thấy checkbox dịch vụ khóa không giải thích.
- Form tạo phiếu nhập / điều chỉnh lấy `size:100` (trần BE): quá 100 sản phẩm thì ô chọn bị cắt im lặng (chưa xảy ra). `AdjustmentForm`: `lotQuantity` chụp lúc chọn lô, kiểm vượt tồn trước khi gửi dùng số cũ cho tới khi chọn lại (BE vẫn chặn).
- 4 nơi dùng `useCustomerLookup` nay lấy tối đa 100 dòng nhưng chỉ `CustomerSearchPanel` có dòng nhắc cắt.
- `main.tsx` có `QueryClient` thứ hai với `retry: 1` hoàn toàn bị đè, vô dụng.
- Mock kho còn lệch nhỏ: câu `Lý do "Khác" bắt buộc có ghi chú` thiếu chữ "có"; thứ tự kiểm 404 và OTHER.
- Thông báo "Phòng khám đã hủy lịch hẹn…" gửi cả khi chính chủ tự đánh dấu thú mất (chỉ là lưu ý nội dung).

**Dữ liệu `[TEST C2]` còn trong DB `petcare_seed`** (KHÔNG dọn: chưa được duyệt; xóa khách / lượt khám / sổ kho khó hoàn tác; dữ liệu `[TEST C3]` và demo gốc không đụng). Khách quầy 5 (id 7–11), tài khoản khách `c2.khach1@example.com` / `Matkhau123` (id 29), thú 15 và 18 **đã mất (không hoàn tác)**, 16 và 19; lượt khám #25 + bệnh án #25 + **Order 38 (150.000 đ, `PENDING`)** làm "Chờ thu" ở thanh bên tăng 2 → 3; lịch hẹn 33, 34 và đặt chỗ lưu trú 2, 3 đã `CANCELLED`, Care Task 2 `CANCELLED`; kho: sản phẩm 9 `TESTC2-01` (ngừng bán), NCC 3 (ngừng hợp tác), phiếu nhập PN-2610-00002…00004, phiếu điều chỉnh DC-2610-00001…00004, 9 dòng sổ kho; 1 dòng audit `PRICE_CHANGED` (id 412). Chi nhánh và danh mục đã dọn hết. Script dọn chưa chạy: `D:/Do-an/.c2-tmp/customer/cleanup-dryrun.sql` (chạy thử `BEGIN … ROLLBACK`). Chi tiết: `docs/testcases/test-tay-ghep-api-theo-cum.md` mục Cụm 2.

**Kiểm khôi phục (đo lại bằng SQL 29/10).** Chi nhánh: `opening_hours` 7 dòng (chỉ bản 24/10), `holidays` 0, `slot_quotas` 0, 7 dịch vụ bật, quota MEDICAL 3 / GROOMING 2; snapshot API 6 mục khớp, chỉ `updated_at` khác ở `branch_services(1,4)` và 2 dòng `branch_quota_defaults`. Kho: tồn 8 sản phẩm demo khớp (Royal Canin 22, Pate 112, Vòng cổ 40, Amoxicillin 690, Vitamin 17 tổng / 15 khả dụng, Vaccine Dại 40, 5 bệnh 29, mèo 19); lô 1–10, NCC 1–2, phiếu nhập 1, movement 1–23 không đổi. Khách: `late_cancel=true` và `booking_restrictions` (2) của cụm 3 không đổi. Nhật ký tiến độ: `docs/be-fe-integration.md` mục 3.

### Chạy lại các ca đã sửa trên BE thật (30/10)

**Môi trường.** BE (8082) và Vite (5174) chết đêm 29→30/10 vì máy khởi động lại: log BE (`D:/Do-an/.c2-be.log`) dừng lúc 29/10 18:12 với lỗi mất kết nối Redis và Postgres, Docker Desktop mới bật lại container sáng 30/10. Dựng lại bằng lệnh ở `docs/be-fe-integration.md` mục 7 (FE ở cổng 5174, `VITE_API_MOCK=false`, `VITE_API_URL=http://localhost:8082`). Lần khởi động BE đầu build không nạp `application.yml` ("Failed to determine a suitable driver class") nên phải chạy lại; lần chạy lại lên bình thường (`curl` BE và Vite đều 200 trước khi chạy ca).

**Cách chạy.** Chỉ đọc. Playwright (Chrome, `--disable-web-security`, 1280 px), mỗi session đăng ký `page.route('**/api/**')` chặn (abort) và đếm mọi request không phải GET, trừ `POST /api/auth/login`; mỗi ca ghi lại mọi response từ `localhost:8082/api/` và ABORT nếu không thấy response thật nào. **Kết quả: 13 session, 0 request non-GET bị chặn; mọi non-GET đã gửi chỉ là `POST /api/auth/login` ×13.** DB sau khi chạy không đổi (11 khách, 18 thú, 38 Order, Order 17, 18, 38 nguyên, `stock_movements` 32). Không đụng BE, Docker, Vite. Kịch bản (`lib.mjs`, `r1.mjs`…`r6.mjs`, `r4b.mjs`), `out-r*.json`, `evidence-*.json` (từng request, status, độ trễ) và ảnh `shots/` ở `D:/Do-an/.c2-tmp/rerun/` (ngoài repo).

| Ca | Kết quả | Số đo thật (request, status, thời gian) |
|---|---|---|
| R1b `RetailOrderPanel` (khung "Bán lẻ", `mai.le`) | đạt, mức L | Ô trống lúc tải trang: 0 request `/api/customers*`. Gõ "L": `GET /api/customers?size=100&name=L` 200 (3 ứng viên). Gõ `0905111222`: `…?size=100&phone=0905111222` 200 (2). Rút còn 9 số hoặc xóa hết: 0 request, 0 ứng viên |
| R1a `CustomerLookup` hộp "Tiếp nhận khách" | đạt, mức L | Trống: 0 request. "N": `name=N` 200. "Lê": `name=L%C3%AA&size=100` 200 (2). Rút còn "0905" hoặc xóa: 0 request, 0 ứng viên |
| R1a `CustomerLookup` khung "Thu tiền" | mức S | Cần ca thu ngân đang mở (xem lưu ý) |
| R1a `CustomerSearchPanel` (nút "Khách hàng") | đạt, mức L | Chế độ SĐT: trống 0 request, "0" 0 request, `0988123456` → `phone=…&size=100` 200 (1), rút còn 9 số → 0 ứng viên. Chế độ Họ tên: "P" `name=P` 200, "Thử" 200 (1), xóa → 0 ứng viên |
| R1d `TransferOwnerDialog` | đạt, mức L | Trống 0 request, "H" 0 request, "Hoa" `name=Hoa&size=100` 200 (1 ứng viên), rút còn "H" → 0 ứng viên, xóa 0. Mở từ hồ sơ khách `0912345678` (2 thú còn sống), bấm Hủy, không xác nhận |
| R1c `BookOnBehalfDialog` | đạt, mức L | Trống 0 request, "L" 0 request, "Lê" `name=L%C3%AA&size=100` 200 (2), rút còn "L" → 0 ứng viên, xóa 0. Mở từ `/admin/appointments`, đóng bằng Esc, không chọn khách |
| R2 dòng khách cũ | đạt, mức L | Gõ `0909207001`: 1 dòng "[TEST C2] Chủ thú mất S1" (`phone=0909207001&size=100` 200). Xóa 1 số (đo sau ~60 ms, trong debounce) và 8 số (sau 1,8 s): 0 dòng, còn lời nhắc "Nhập đủ 10 số điện thoại để tra cứu.", không hiện "Không tìm thấy hồ sơ phù hợp."; gõ lại 2 số, dòng về (từ cache, 0 request) |
| R3a `/health-record` | đạt, mức L | `khoi.vo`, ngăn kéo "Sức khỏe" của 2 thú (Mochi, Bim): mỗi thú đúng **1** `GET /api/pets/{1,2}/health-record` 404, chờ thêm 16 s không có lần thứ hai. "Không tìm thấy đường dẫn yêu cầu" hiện sau **152 ms** và **348 ms** kể từ lúc bấm (trước đây ~7,4 s) |
| R3b dashboard | đạt, mức L | `hung.tran` `/admin` → `/admin/dashboard`: `GET /api/reports/revenue?from=…&to=…` 404 (T44) đúng 1 lần mỗi lượt tải trang, theo dõi 22 s không lặp (hook `useReports` đi qua `toApiError`, 404 không retry) |
| R3c trang 4xx khác | đạt, mức L | Không trang nào khác trả 4xx lặp; `GET /api/staff` 403 không xuất hiện ở trang nào đã mở |
| R4 giả 503 | đạt, mức L | `hung.tran`, `GET /api/branches/1` (trang `/admin/branches/1/hours`) giả 503 bằng `page.route`: **4 lần** (1 + 3 retry) ở 975, 2014, 4019, 8023 ms (cách nhau ~1, 2, 4 s); UI hiện "Giả lập 503 (R4)" |
| R4 lỗi mạng | đạt, mức L | Cùng URL, `route.abort('failed')`: **4 lần** ở 912, 1951, 3958, 7964 ms; UI hiện "Không kết nối được máy chủ" |
| R4 nhánh K2 (`toApiError`, status 0) | đạt, mức L | Hook `useReports` gọi `shouldRetry(count, toApiError(error))`, `GET /api/reports/revenue`: giả 503 → 4 lần (791, 1856, 3866, 7874 ms); lỗi mạng → 4 lần (718, 1772, 3780, 7783 ms). Trước sửa K2 nhánh lỗi mạng này không bao giờ retry |
| R5 lô trùng | đạt, mức L | `hung.tran`, `/admin/warehouse/adjustments`: dòng 1 Amoxicillin 250mg lô AMX2405 +1, dòng 2 cùng lô +1, bấm "Lưu phiếu điều chỉnh" → "Dòng 2: lô đã chọn ở dòng 1"; **0 POST / PUT** gửi đi, 0 request bị chặn, hộp thoại còn mở, bấm Hủy |
| R5 phiếu nhập NCC ngừng hợp tác | mức S | Không có phiếu nháp nào để thử (SQL: `stock_receipts` 4 phiếu, 2 `CONFIRMED`, 2 `CANCELLED`, 0 `DRAFT`); không tạo |
| R6 quét 4 trang chỉ xem | đạt, mức L | Không response ≥ 400 nào ngoài `reports/revenue` 404 ở dashboard lúc đăng nhập BM / SM: `/admin/branches` (`hung.tran`, tự vào `/admin/branches/1/hours`, 9 request), `/admin/warehouse` (`hung.tran`, → `/stock`, 7), `/admin/catalog` (`ha.nguyen`, → `/products`, 4), `/staff/reception` (`mai.le`, 10); không request lỗi mạng, không `pageerror` |

**Kết luận:** đạt hết, không thấy lỗi FE mới. Ô nhập trống không bắn truy vấn khách (`GET /api/customers` 400 khi ô trống: không xảy ra, 0 request); 4xx không retry (lỗi hiện sau dưới 0,4 s); 5xx và lỗi mạng retry 4 lần, nhánh `toApiError` (K2) cũng vậy; lô trùng bị chặn ở FE.

**Lưu ý.**
- **R1d, khách không có đường chuyển chủ:** `TransferOwnerDialog` chỉ gắn ở `components/staff/reception/customers/CustomerDetailDialog.tsx:121` (nút "Chuyển chủ" của lễ tân), nên đã kiểm qua `mai.le`. Khách chủ thú `c2.khach1` ở `/pets`: 0 nút "chuyển chủ", 0 chữ "chuyển chủ", 0 request `/api/customers*`.
- **Gõ 1 ký tự tên:** `RetailOrderPanel`, `CustomerLookup` và `CustomerSearchPanel` (chế độ Họ tên) gửi `name=<1 ký tự>`, BE trả 200 (không 400); `BookOnBehalfDialog` và `TransferOwnerDialog` đòi từ 2 ký tự mới tra. UX không đồng nhất, chưa sửa.
- **Cache làm lần chạy đầu thiếu request:** `staleTime` 60 s của React Query khiến lần chạy R1 đầu (cùng từ khóa ở nhiều nơi) thấy 0 request ở các nơi gõ sau (ứng viên lấy từ cache). Đã chạy lại với từ khóa riêng mỗi nơi; bảng trên là lần chạy thứ hai.
- **Ca "Thu tiền" của `CustomerLookup` chỉ mức S:** ô chỉ hiện khi ca thu ngân đang mở (`CheckoutPanel.tsx`: `hasShift && <CustomerLookup>`), mở ca là ghi nên không làm; đọc code thì cùng hook có `enabled` chặn truy vấn rỗng. Khung "Bán lẻ" không cần mở ca nên kiểm được ở mức L.

## D. Màn hình còn bị BE chặn (tiêu chí "tắt mock, mọi màn gọi API thật" **chưa đạt**)

| Màn / luồng | Bị chặn bởi | Hiện tượng với BE thật |
|---|---|---|
| `/admin/reports`, thẻ "Đã thu hôm nay" ở dashboard | module `report` (**T44**, BE-2) | trang báo "Không tìm thấy đường dẫn yêu cầu" (404); thẻ hiện "–" |
| Nhận / trả thú, nhật ký chăm sóc, hủy phiên trả thú (`/admin/boarding`) | boarding #13–22, sales #9 (**T42, T43**) | 404 ở các nút nhận / trả / nhật ký; `activeOrderId` null |
| Cài đặt thông báo của khách | care-v1 #9–10 (**D025**) | 404 |
| Tab "Hồ sơ sức khỏe" của thú | visit-v1 #21 (**T28b**) | 404 "Không tìm thấy đường dẫn yêu cầu" trong tab |
| Chuyển dòng đơn sang mua ngoài ở thu ngân | sales #11 (**BE-2 nối `PrescriptionApi`**) | 404 |
| Lễ tân gán người phụ trách lượt | `GET /api/staff` cho lễ tân (**BE-1**) | hộp thoại báo "Danh sách nhân viên chờ BE-1 mở GET /api/staff cho lễ tân" |
| UC22 đổi email / liên kết tài khoản của khách | identity #25–27 | 404 |

Không đưa các đường dẫn này vào `VITE_API_MOCK_ONLY` khi chạy BE thật (mock dùng id khác DB của BE). `FE/.env.example` đã cập nhật: `/api/reports/*` ở nhóm "mock có, BE chưa có" (không an toàn), kê đơn ghi "BE đã giao từ T38".

## E. Test và đột biến

| Nhóm | Test | Đột biến (đều bị bắt) |
|---|---|---|
| Mock báo cáo (`report.mock.test.ts`, 139 ca; ghim giờ chạy ở 4 múi giờ: UTC, Los_Angeles, Kiritimati, Ho_Chi_Minh) | vai trò × 7 endpoint, thứ tự lỗi, biên 12 tháng hai phía, phạm vi, tổng khớp, `abort-checkout` không vào thất thu, 23:59 / 00:01 giờ VN | 36 |
| Trang báo cáo (`AdminReportsPage.test` 48, `.mock.test` 12 trên mock thật, `report-format.test` 23) | không 403 theo vai trò, BM không có ô chi nhánh / không gửi `branchId` khác, kỳ sai không request, chỉ tab đang mở truy vấn, lỗi server, rỗng, không có nút Xuất | 25 |
| Dashboard + chuyển hướng (`AdminDashboardPage.test` 18, `App.redirects.test` 34, `App.no-clinic-db.test` 3, +5 sidebar / menu) | ma trận 5 route × 6 vai trò, KPI theo vai trò, giờ VN, lỗi từng thẻ, `clinic-db` không nạp | 18 |
| `branchId` từ `GET /me` (`useStaffBranch.test` 7, `AdminBranchFromMe.test` 3) | phiên thiếu `branchId` vẫn có "Đặt tại quầy" / "Tạo chuồng" / "Đặt lịch hộ"; login gộp `branchId` | 4 |
| Identity / auth / lịch hẹn (+41 ca) | phân trang, audit, BM tạo nhân viên, `visibleRoles`, `plainMessage`, 409 | 9 |
| Catalog / kho / chi nhánh (+43 ca) | PATCH mô tả, ngày phiếu nhập, ngày giờ VN ở 4 múi giờ | 6 |
| Khám / tiêm / kê đơn (+10 ca ròng) | xóa mũi, `BR-KB-03`, hết phác đồ, 409 care task | 8 |
| Sau luồng thật (+2 ca) | `AdminAccountsPage` nhãn lọc vai trò = `ROLE_LABELS`; `AdminNotificationTemplatesPage` không rò "undefined" | 0 (đỏ trước khi sửa) |

Tổng khoảng 106 đột biến, mọi cái ≥ 1 ca đỏ, đã hoàn tác (so byte với bản sao). Test cũ phải đổi vì mock đổi cho đúng BE: ca ADMIN xem nhân viên chuyển sang SUPER_MANAGER; SM / BM không còn thấy chính mình ở `GET /staff`; VET, CARETAKER nay 403; gán lại lượt (người mới xóa mũi 204, VET cũ 403); mũi / sản phẩm lạ 404 → 400; `lotNumber` / `expiryDate` `null`; câu "Phiên đăng nhập không có chi nhánh" → "Tài khoản chưa gắn chi nhánh".

## F. Còn lại / chưa kiểm

- **Chạy BE thật: đã xong luồng chính và quét trang (mục C.3, C.4).** Cách dựng lại: Docker Desktop (cần ~2,6 GB RAM, đóng game client), `docker start petcare-seed-pg petcare-mailpit` (cổng 5433 `petcare_seed` đã seed, Flyway V21; seed lại chỉ khi DB trống: `FE/scripts/seed-dev`), `cd BE && DB_PORT=5433 DB_NAME=petcare_seed DB_PASSWORD=123456 SERVER_PORT=8082 MAIL_HOST=localhost MAIL_PORT=1025 mvn -B -ntp spring-boot:run -Dmaven.test.skip=true "-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Ho_Chi_Minh -Xmx768m"` (**cổng 8082**: Docker Desktop tự bật lại `petcare-api` bản cũ của người dùng ở 8081), `cd FE && VITE_API_MOCK=false VITE_API_URL=http://localhost:8082 npx vite --port 5173`, đăng nhập `/auth/login` bằng `<tên>@petcare.local` / `Petcare@2026`. **Bẫy build BE:** `target/generated-sources/.../StaffAdminMapperImpl.java` bị ghi trùng (tiến trình java của IDE biên dịch cùng lúc) → "implicitly declared classes are a preview feature"; xóa file đó rồi `touch` `StaffAdminMapper.java` để ép biên dịch lại. **Còn lại:** luồng nhận / trả thú, Care Task, báo cáo thật chờ BE (mục D); các luồng "chưa làm" ở C.4.
- **Đã xem trình duyệt thật ở chế độ mock** (Playwright + Chrome, 1280 px, đồng hồ ghim thứ Sáu 23/10/2026 09:00, phiên đăng nhập **không có `branchId`** như BE thật trả): `/admin/reports` (SUPER_MANAGER: tab Doanh thu + Thất thu, NO_SHOW, Tồn kho, Feedback; BRANCH_MANAGER), `/admin/dashboard` (quản lý, lễ tân, quản lý chuỗi), `/admin/users`, `/admin/kennels`, `/admin/boarding` (nút "Đặt tại quầy", "Tạo chuồng" **hiện** nhờ `GET /me`), `/admin/audit` (ADMIN). **Sửa sau khi xem:** thẻ "Dịch vụ hôm nay" nằm lẻ nửa hàng ở dashboard → đặt cạnh "Mở bàn làm việc" (lưới 2 cột). **Chưa xem:** tab Lượt dịch vụ và Tái chủng của báo cáo (chỉ qua test), `Pager` (mock chỉ có ≤ 20 dòng mỗi danh sách nên chân trang ẩn; kiểm bằng jsdom), Kiểm toán có dữ liệu (mock audit **không seed**, rỗng tới khi có thao tác quản trị), các hộp thoại, thông báo `max` của ô ngày phiếu nhập.
- Mock báo cáo: doanh thu có khoản cố định, #3–5 dòng mẫu, biên 12 tháng là giả định: **đối chiếu khi BE-2 làm T44** (kể cả câu lỗi, thứ tự 403 / 400, cách đếm tháng). Ghi `report-v1` vào hợp đồng nếu BE khác.
- Dev browser có key `petcare-mock-identity` cũ (audit `entityType: 'Staff'`) lọc ra rỗng tới khi xóa key.
- **Responsive chưa làm** (quyết định 27/10, gộp ở T57).
- Dọn mã chết `clinic-db` / `useClinic` / `clinic.api` và các trang mảng cứng còn lại: T57.

## G. Kiểm chứng

Chạy từ `FE/` sau mọi thay đổi code:
- `npx tsc --noEmit`: sạch (chạy lại sau lần sửa dashboard cuối). `npm run lint` (`eslint . --max-warnings 0`): sạch. `npm run build`: thành công (27/10, chạy lại ở cuối ngày trên cây mã cuối).
- `npm run test:e2e` (Playwright, 24 ca, 27/10 lúc 22:26 giờ máy): **13 xanh, 8 bỏ qua** (`screens.spec` cần `SCREENS_DIR`), **3 đỏ ở `e2e/workspaces.spec.ts`, không do T49**: ca "counter sale with the keyboard only" tìm nút "Mở ca thu ngân" nhưng từ T35 (commit `15996be`) nút là "Mở ca thường" / "Mở ca ngoài giờ" (spec không đổi từ trước T33); ghim đồng hồ 09:00 vẫn đỏ vì nhãn. Hai ca còn lại ("Tiếp nhận" Lucky, "Bắt đầu" Rocky) **chưa điều tra**, nhiều khả năng cũng lỗi thời sau T35–T37. `theme.spec` (chờ heading "Bảng điều khiển") và các spec còn lại xanh. Sửa spec: T50 / T57.
- `npx vitest run --maxWorkers=4` — **lịch sử các lần chạy cả bộ ngày 27/10** (log ở `docs/superpowers/plans/t49-vitest-run*.log`, gitignore):

| Lần | Thời điểm | Kết quả | Ghi chú |
|---|---|---|---|
| 1 | sau A1 / A2 / B, trước đợt sửa lệch FE↔BE | 154 file / 3150 ca xanh, exit 0 | baseline |
| 2 | sau F2 / F3 / F4 | 1 đỏ / 3249 | `AdminKennelsPage.test` còn assert câu cũ "Phiên đăng nhập không có chi nhánh" → sửa theo hành vi mới (tài khoản thật sự không có chi nhánh: phiên **và** `GET /me` đều thiếu) |
| 3 | sau sửa trên | **159 file / 3249 ca xanh**, exit 0 | |
| 4 | đo lặp | 1 đỏ / 3249 | `AdminPageContentsPage.test` "Chính sách: tối đa 20 mục" (**test của T47, T49 không sửa logic**) hết 5 giây: chạy một mình 3,7 giây (bấm thêm 20 lần), chạy song song 4 luồng vượt → thêm `vi.setConfig({ testTimeout: 20_000 })` ở đầu file test đó (T49 sửa file T47 chỉ vì lý do này) |
| 5 | sau nâng timeout | **159 file / 3249 ca xanh**, exit 0 | |
| 6 | cuối ngày, sau khi sửa bố cục dashboard | **159 file / 3249 ca xanh**, exit 0 | 3 lần sạch (3, 5, 6) |
| 7 | sau luồng thật (+2 ca của agent chạy BE thật) | **159 file / 3251 ca xanh**, exit 0 | 4 lần sạch trong 7 lần đo |

Nền trước T49: 147 file / 2868 ca ở T48. Đã đo lặp 4 lần sạch; vẫn nên chạy thêm một lần trước khi commit (xem memory về lỗi chập chờn GSAP / hết giờ khi máy bận).

