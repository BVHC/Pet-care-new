# PET CARE 2.0 — ROADMAP, TIMELINE & BẢNG THEO DÕI TIẾN ĐỘ FRONTEND

> **Dự án:** Pet Care - Pet Shop Management Ecosystem  
> **Thời gian:** 6 tuần (07/09/2026 – 19/10/2026)  
> **Nhân sự FE:** 01 Frontend Engineer (phối hợp 02 Backend Engineers)  
> **Tech Stack:** React 18, Vite, TailwindCSS, TanStack Query v5, Zustand, React Router v6, GSAP, Lucide Icons, Sonner  
> **Tài liệu API gốc:** `docs/api/*-v1.md` (25 modules, 17 FSMs, 9 Roles)  
> **Ngày rà soát hiện trạng:** 16/09/2026 (Tuần 1 -> chuẩn bị bước sang Tuần 2)

---

## 📊 BẢNG TỔNG HỢP TIẾN ĐỘ THỰC TẾ (AUDIT TRẠNG THÁI 16/09/2026)

| Nhóm Phân Hệ | Tổng số màn/task | ✅ Đã hoàn thành | 🟡 Đã có UI (Mock/Local) | ⏳ Chưa làm (0%) | Tỷ lệ khả dụng |
|---|:---:|:---:|:---:|:---:|:---:|
| **1. Khung & Xác thực (Foundation & Auth)** | 5 | 5 | 0 | 0 | **100%** |
| **2. Cổng Khách Hàng (Customer Web B2C)** | 8 | 2 | 6 | 0 | **65% (Đã có UI)** |
| **3. Cổng Nhân Viên & Quản Trị (Back-Office B2B)**| 11 | 0 | 0 | 11 | **0% (Chưa dựng)** |
| **4. Tích Hợp & Polish (Integration & Demo)** | 4 | 0 | 0 | 4 | **0%** |
| **TỔNG CỘNG** | **28** | **7** | **6** | **15** | **~38% UI / ~18% API Thật** |

---

## 📋 BẢNG THEO DÕI CHI TIẾT TỪNG MÀN HÌNH (ROUTE ↔ API ↔ ROLE ↔ TRẠNG THÁI)

> **Ký hiệu trạng thái:**  
> - ✅ **Hoàn thành (Done):** Đã có UI chuẩn + Đã kết nối API thật / Store hoàn chỉnh.  
> - 🟡 **Đang phát triển (UI Only):** Đã dựng xong giao diện (HTML/CSS/Interaction), đang dùng Mock Data hoặc `localStorage`, cần gắn API Backend thật.  
> - ⏳ **Chưa làm (Not Started):** Chưa tạo màn hình / Chưa dựng route.

| ID | Nhóm | Màn hình / Tính năng | Route FE | Role truy cập | API Backend tương ứng (`docs/api/`) | Trạng thái | Hiện trạng code & Việc cần làm tiếp |
|:---:|---|---|---|---|---|:---:|---|
| **01** | Foundation | Setup Vite + React + TanStack Query + Tailwind | `/` | Tất cả | — | ✅ Hoàn thành | Đang chạy dev server mượt mà, cấu hình global styles & fonts xong. |
| **02** | Foundation | Layout & Header/Footer Public | `/` | Khách / Tất cả | — | ✅ Hoàn thành | `PublicLayout.tsx`, Navbar, Footer, Mega menu dịch vụ đã chạy. |
| **03** | Auth | Đăng nhập hệ thống (Login) | `/auth/login` | Khách / Staff | `POST /api/v1/auth/login` | ✅ Hoàn thành | Đã kết nối `auth.api.ts`, lưu JWT, tự chuyển hướng sang OTP nếu chưa verify. |
| **04** | Auth | Đăng ký tài khoản (Register) | `/auth/register` | Khách hàng | `POST /api/v1/auth/register` | ✅ Hoàn thành | Đã kết nối API thật, validate form, gửi yêu cầu OTP sang mail/phone. |
| **05** | Auth | Xác thực OTP 6 số (Verify OTP) | `/auth/verify-otp` | Khách hàng | `POST /api/v1/auth/otp/verify` | ✅ Hoàn thành | Đếm ngược 60s, khóa sau 5 lần sai, kết nối API xác thực thật. |
| **06** | Customer | Trang chủ Hero Mascot & Đánh giá | `/` | Khách / Tất cả | Mock data (Home) | ✅ Hoàn thành | Hero mèo mắt liếc 2.5D, 3 thẻ dịch vụ bo góc, audit chuẩn kích thước màn hình. |
| **07** | Customer | Gợi ý sản phẩm thông minh (AI / Recommend) | `/recommend` | Khách hàng | Mock data (Products) | ✅ Hoàn thành | Lọc sản phẩm theo loài (chó/mèo), thẻ pet mini, thêm nhanh vào giỏ. |
| **08** | Customer | Khách sạn thú cưng / Tin tức / Giới thiệu | `/hotel`, `/news`, `/about` | Khách hàng | Mock data | ✅ Hoàn thành | Giao diện tĩnh, bảng giá phòng khách sạn, bài viết cẩm nang chăm sóc bé. |
| **09** | Customer | Danh mục sản phẩm & Chi tiết SP (Shop) | `/shop`, `/shop/:id` | Khách hàng | `GET /api/v1/catalog/products` | 🟡 UI Only | Đã có UI lưới sản phẩm, bộ lọc, xem chi tiết; **Cần:** Gọi API Catalog thật của BE. |
| **10** | Customer | Giỏ hàng mua sắm (Cart Page/Drawer) | `/cart` | Khách hàng | Local state | 🟡 UI Only | Đã có UI giỏ hàng, tăng giảm số lượng; **Cần:** Gắn API validate tồn kho & giá chi nhánh. |
| **11** | Customer | Đặt hàng & Cổng thanh toán VNPAY | `/checkout`, `/order/:id`| `CUSTOMER` | `POST /api/v1/orders`, `POST /api/v1/payments/*` | 🟡 UI Only | Đã có form thông tin nhận hàng; **Cần:** Gọi API tạo Order + Redirect cổng VNPAY sandbox. |
| **12** | Customer | Quản lý hồ sơ thú cưng (Pets CRUD) | `/pets` | `CUSTOMER` | `GET/POST /api/v1/pets` (`customer-pet-v1.md`) | 🟡 UI Only | Đã có UI thêm/sửa/xóa pet bằng `localStorage`; **Cần:** Thay bằng hook gọi API BE thật. |
| **13** | Customer | Đặt lịch dịch vụ 4 bước (Booking) | `/booking` | `CUSTOMER` | `POST /api/v1/appointments/hold` (`appointment-v1.md`)| 🟡 UI Only | Đã có UI chọn ngày, giờ, dịch vụ; **Cần:** Kết nối FSM giữ slot 15 phút, chọn chi nhánh. |
| **14** | Customer | Sổ tiêm chủng & Lịch sử bệnh án của Pet | `/pets/:id/medical` | `CUSTOMER` | `GET /api/v1/pets/{id}/vaccination-book` | ⏳ Chưa làm | Cần dựng tab hiển thị mũi tiêm đã tiêm (xanh) và mũi nhắc lại (vàng). |
| **15** | Customer | Lịch sử đơn hàng & Trạng thái lịch hẹn | `/orders`, `/account` | `CUSTOMER` | `GET /api/v1/orders/my`, `GET /api/v1/appointments/my` | 🟡 UI Only | Đã có trang Account cơ bản; **Cần:** Hiển thị stepper tiến độ đơn hàng/lịch hẹn thật. |
| **16** | Back-Office | Khung Admin Layout & Menu Phân Quyền (RBAC) | `/admin/*` | Toàn bộ 8 Staff Roles | `GET /api/v1/auth/me` | ⏳ Chưa làm | Cần dựng Sidebar admin, Header admin, Route Guard chặn quyền truy cập. |
| **17** | Back-Office | Bàn Quản lý Lịch hẹn (Calendar View) | `/admin/appointments` | `RECEPTIONIST`, `STORE_MANAGER` | `GET/POST /api/v1/appointments/*` | ⏳ Chưa làm | Xem lịch theo ngày/thợ, nút Check-in khách đến, nút Đổi giờ, Hủy lịch. |
| **18** | Back-Office | Hàng đợi đón khách vãng lai (Walk-in FIFO) | `/admin/queue` | `RECEPTIONIST` | `POST /api/v1/queue/tickets/*` (`queue-v1.md`) | ⏳ Chưa làm | Màn hình cấp số thứ tự A001..A999, màn hình TV gọi số vào phòng khám/spa. |
| **19** | Back-Office | Bàn làm việc Bác sĩ Thú y (Clinical EMR) | `/admin/clinical` | `VETERINARIAN` | `POST /api/v1/clinical/records/*` (`clinical-v1.md`)| ⏳ Chưa làm | Form khám, bệnh án điện tử, chọn thuốc kê đơn, khóa bệnh án sau 24h. |
| **20** | Back-Office | Quản lý Lô Vaccine & Thực hiện tiêm | `/admin/vaccinations` | `VETERINARIAN` | `POST /api/v1/vaccinations/record` | ⏳ Chưa làm | Quét barcode lô vaccine, nhập ngày tiêm, tự tính lịch tiêm nhắc lại. |
| **21** | Back-Office | Bàn làm việc KTV Grooming Spa | `/admin/grooming` | `GROOMER` | `POST /api/v1/grooming/tasks/*` (`grooming-v1.md`)| ⏳ Chưa làm | Danh sách thú cần tắm/tỉa, cập nhật trạng thái FSM, báo phụ phí lông rối (D-02). |
| **22** | Back-Office | Thu ngân tại quầy & Xuất hóa đơn (POS) | `/admin/pos`, `/admin/invoices`| `RECEPTIONIST` | `POST /api/v1/invoices/*` (`invoice-v1.md`) | ⏳ Chưa làm | Tạo hóa đơn bán lẻ, in bill nhiệt K80, thanh toán tiền mặt/QR code. |
| **23** | Back-Office | Luồng Duyệt Hoàn Tiền (Refunds Maker-Checker)| `/admin/refunds` | `RECEPTIONIST` (Maker), `MANAGER` (Checker) | `POST /api/v1/refunds/*` (`refund-v1.md`) | ⏳ Chưa làm | Tiếp tân lập phiếu hoàn -> Quản lý duyệt/từ chối kèm lý do. |
| **24** | Back-Office | Quản lý Kho chi nhánh & Chuyển kho | `/admin/inventory` | `INVENTORY_STAFF`, `STORE_MANAGER` | `GET/POST /api/v1/inventory/*` (`inventory-v1.md`) | ⏳ Chưa làm | Kiểm kê thực tế/giữ chỗ/khả dụng, nhập phiếu điều chỉnh, chuyển kho. |
| **25** | Back-Office | Phân ca làm việc nhân viên (Workforce) | `/admin/workforce` | `STORE_MANAGER` | `GET/POST /api/v1/workforce/*` (`workforce-v1.md`) | ⏳ Chưa làm | Bảng lịch trực ca tuần, phân công bác sĩ/tiếp tân, duyệt đổi ca trực. |
| **26** | Back-Office | Báo cáo Dashboard & Đối soát doanh thu | `/admin/dashboard`, `/reports` | `STORE_MANAGER`, `ORG_ADMIN` | `GET /api/v1/reports/*` (`report-v1.md`) | ⏳ Chưa làm | Biểu đồ doanh thu ngày/tháng, tỷ trọng dịch vụ vs hàng hóa, đối soát NetRevenue. |
| **27** | Integration | Rà soát lỗi E2E & Chặn Route 401/403 | Toàn hệ thống | Tất cả | 100% Backend APIs | ⏳ Chưa làm | Auto logout khi token hết hạn, thông báo lỗi validation đỏ đúng ô input. |
| **28** | Demo | Kịch bản tổng duyệt Demo trực tiếp | Toàn hệ thống | Cả nhóm (3 người) | Toàn hệ thống | ⏳ Chưa làm | Rehearsal kịch bản 4 vai: Khách đặt lịch -> Tiếp tân đón -> Bác sĩ khám -> Quản lý xem báo cáo. |

---

## 📅 ROADMAP CHI TIẾT THEO 6 TUẦN LÀM VIỆC

```
[Tuần 1: 10–14/09]  ██████████░░░░░░░░░░░░░░░░░░  (Setup Vite + Auth + UI Customer cơ bản)  ✅ ĐÃ XONG
[Tuần 2: 17–21/09]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Gắn API Pets/Shop + Core Booking FSM + Admin Layout)
[Tuần 3: 24–28/09]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Checkout VNPAY + Màn Bác sĩ EMR + Bàn Tiếp tân)
[Tuần 4: 01–05/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Invoices POS + Hoàn tiền Maker-Checker + Vaccine)
[Tuần 5: 08–12/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Walk-in Queue + Groomer + Workforce + Dashboard)
[Tuần 6: 15–19/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (E2E Integration + Mobile Responsive + Tổng duyệt Demo)
```

---

### TUẦN 1 (10–14/09/2026): NỀN TẢNG & AUTHENTICATION (ĐÃ XONG ✅)
- [x] **Setup dự án:** React 18 + Vite + TailwindCSS + Zustand + TanStack Query.
- [x] **Auth:** Màn hình Đăng nhập (`/auth/login`), Đăng ký (`/auth/register`), Nhập OTP (`/auth/verify-otp`).
- [x] **API Auth thật:** Đã nối Axios client với BE (`POST /api/v1/auth/login`, `register`, `otp/verify`).
- [x] **Customer UI:** Đã dựng xong Trang chủ Hero Mascot GSAP, Recommend, Hotel, Shop mockup.

---

### TUẦN 2 (17–21/09/2026): GẮN API SHOP/PETS & DỰNG BOOKING FSM + ADMIN LAYOUT
- [ ] **FE-T2-01 [Customer]:** Gắn API thật cho **Quản lý thú cưng** (`GET/POST /api/v1/pets`). Bỏ lưu `localStorage`, đồng bộ danh sách pet về database PostgreSQL.
- [ ] **FE-T2-02 [Customer]:** Gắn API thật cho **Cửa hàng** (`GET /api/v1/catalog/products` và `/catalog/services`). Hiển thị giá thật theo Store được chọn.
- [ ] **FE-T2-03 [Customer]:** Xây dựng luồng **Đặt lịch hẹn 4 bước chuẩn FSM** (`/booking`):
  - Bước 1: Chọn Pet đã đăng ký.
  - Bước 2: Chọn dịch vụ (Khám tổng quát / Spa tỉa lông / Tiêm phòng).
  - Bước 3: Gọi API `GET /api/v1/appointments/available-slots` chọn giờ khả dụng -> `POST /api/v1/appointments/hold` giữ slot 15 phút (có đồng hồ đếm ngược).
  - Bước 4: Chốt lịch hẹn (`POST /api/v1/appointments`).
- [ ] **FE-T2-04 [Admin Core]:** Dựng khung **Admin Layout & Sidebar** (`/admin/*`):
  - Sidebar co giãn (Collapsible), hiển thị menu động theo vai trò (Role).
  - Hợp phần `ProtectedRoute`: Người dùng có quyền mới được vào route tương ứng.

---

### TUẦN 3 (24–28/09/2026): THANH TOÁN VNPAY & MÀN HÌNH BÁC SĨ (CLINICAL EMR)
- [ ] **FE-T3-01 [Customer]:** Hoàn thiện luồng **Checkout giỏ hàng & Thanh toán VNPAY**:
  - `POST /api/v1/orders` tạo đơn hàng từ Cart.
  - `POST /api/v1/payments/create-url` -> Chuyển hướng sang VNPAY Sandbox -> Nhận callback trang `/order/:id` thông báo thành công.
- [ ] **FE-T3-02 [Admin - Bác sĩ]:** Dựng **Bàn làm việc Bác sĩ Thú y** (`/admin/clinical`):
  - Danh sách thú cưng đang chờ trong phòng khám hôm nay.
  - Xem lịch sử bệnh án cũ (`GET /api/v1/clinical/pets/{petId}/history`).
  - Form chẩn đoán mới (`POST /api/v1/clinical/records`): Nhập thân nhiệt, cân nặng, triệu chứng, kết luận bệnh.
  - Kê đơn thuốc từ danh mục Master (`POST /api/v1/clinical/records/{id}/prescriptions`).
  - Nút **Khóa bệnh án** (Finalize): Khóa bất biến sau 24h.
- [ ] **FE-T3-03 [Admin - Tiếp tân]:** Màn hình **Lịch hẹn chi nhánh** (`/admin/appointments`):
  - Xem lịch hẹn hôm nay dạng Calendar hoặc Table.
  - Nút bấm tiếp đón: **Check-in** (chuyển trạng thái `CHECKED_IN`, đẩy thú cưng vào hàng chờ bác sĩ/groomer).

---

### TUẦN 4 (01–05/10/2026): THU NGÂN POS, HOÀN TIỀN MAKER-CHECKER & SỔ TIÊM
- [ ] **FE-T4-01 [Admin - Tiếp tân]:** Màn hình **Thu ngân tại quầy & Hóa đơn** (`/admin/pos`, `/admin/invoices`):
  - Lập hóa đơn từ lịch hẹn đã hoàn thành hoặc khách mua lẻ tại quầy.
  - Thu tiền mặt / Quét mã QR -> Đánh dấu hóa đơn `PAID`.
  - Hỗ trợ in hóa đơn nhiệt (Print preview chuẩn khổ giấy K80).
- [ ] **FE-T4-02 [Admin - Quản lý]:** Luồng **Hoàn tiền đa cấp (Maker-Checker)** (`/admin/refunds`):
  - Tiếp tân bấm tạo yêu cầu hoàn tiền (Maker) kèm chứng từ, lý do.
  - Quản lý cửa hàng (Checker) vào danh sách duyệt hoặc từ chối (`POST /api/v1/refunds/{id}/approve`).
- [ ] **FE-T4-03 [Bác sĩ & Khách hàng]:** **Quản lý Tiêm chủng (Vaccination)**:
  - Bác sĩ (`/admin/vaccinations`): Chọn lô vaccine (quét barcode/nhập mã), ghi nhận mũi tiêm, tự động tính ngày hẹn mũi nhắc lại.
  - Khách hàng (`/pets/:id`): Xem **Sổ tiêm chủng điện tử** trực quan (Timeline các mũi đã tiêm màu xanh, mũi cần nhắc lại màu vàng).
- [ ] **FE-T4-04 [Customer & Admin]:** Áp dụng **Mã giảm giá (Voucher) & Hạng thành viên (Loyalty)**:
  - Ô nhập voucher trong giỏ hàng (`GET /api/v1/vouchers/validate?code=...`).
  - Hiển thị hạng thành viên (Bạc, Vàng, Kim Cương) và trừ điểm tích lũy.

---

### TUẦN 5 (08–12/10/2026): HÀNG ĐỢI WALK-IN, GROOMING SPA, PHÂN CA & BÁO CÁO
- [ ] **FE-T5-01 [Staff - Tiếp tân]:** Màn hình **Hàng đợi đón tiếp Walk-in (FIFO)** (`/admin/queue`):
  - Kiosk cấp phiếu số thứ tự cho khách không đặt trước (A001, A002...).
  - Màn hình gọi số hiển thị TV: "Mời số A005 vào phòng dịch vụ".
- [ ] **FE-T5-02 [Staff - KTV Grooming]:** Màn hình **Bàn làm việc Groomer Spa** (`/admin/grooming`):
  - Danh sách pet chờ tắm/tỉa.
  - Bấm "Bắt đầu làm" (`IN_PROGRESS`).
  - Báo phụ phí nếu phát hiện lông rối nặng hoặc ve rận (Rule D-02).
  - Bấm "Hoàn thành" (`COMPLETED`), gửi thông báo tới khách hàng.
- [ ] **FE-T5-03 [Quản lý chi nhánh]:** Màn hình **Phân ca làm việc (Workforce)** (`/admin/workforce`):
  - Bảng xếp ca trực theo tuần cho Bác sĩ, Groomer, Tiếp tân.
  - Duyệt yêu cầu đổi ca trực giữa 2 nhân viên.
- [ ] **FE-T5-04 [Quản lý & Admin]:** **Báo cáo thống kê Dashboard** (`/admin/dashboard`, `/admin/reports`):
  - Thống kê doanh thu ngày/tuần/tháng (Biểu đồ cột / đường Recharts).
  - Cơ cấu doanh thu: Tách riêng mảng Dịch vụ Spa, Khám thú y và Bán lẻ sản phẩm.
  - Đối soát tiền mặt vs Cổng thanh toán VNPAY.

---

### TUẦN 6 (15–19/10/2026): TÍCH HỢP E2E, MOBILE RESPONSIVE & TỔNG DUYỆT DEMO
- [ ] **FE-T6-01:** Rà soát toàn bộ các lỗi hiển thị console, chuẩn hóa Toast thông báo (`sonner`).
- [ ] **FE-T6-02:** Xử lý triệt để bảo mật FE: Auto logout khi token hết hạn (`401`), chặn vào trang Admin trái phép (`403`).
- [ ] **FE-T6-03:** Tối ưu hóa giao diện trên Mobile (375px - 430px) cho toàn bộ cổng Khách hàng.
- [ ] **FE-T6-04:** Kịch bản tổng duyệt Demo trực tiếp (Rehearsal Live Demo) 4 vai tròn trịa trước hội đồng bảo vệ.

---

## 💡 HƯỚNG DẪN DÙNG FILE NÀY ĐỂ THEO DÕI

1. **Hàng ngày khi hoàn thành 1 việc:** Mở file này, đổi dấu `[ ]` thành `[x]` và sửa cột trạng thái từ `🟡` / `⏳` thành `✅ Hoàn thành`.
2. **Khi trao đổi với Backend:** Đối chiếu cột **API Backend tương ứng**, nhắc BE cung cấp endpoint đúng định dạng payload đã quy định tại thư mục `docs/api/`.
3. **Khi báo cáo tuần cho Giảng viên / Leader:** Chụp hoặc trích xuất bảng **Tiến độ tổng hợp** ở đầu trang để báo cáo tỷ lệ hoàn thành trực quan.
