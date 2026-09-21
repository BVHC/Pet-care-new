# PET CARE 2.0 — ROADMAP, TIMELINE & BẢNG THEO DÕI TIẾN ĐỘ FRONTEND

> **Dự án:** Pet Care - Pet Shop Management Ecosystem  
> **Thời gian:** 6 tuần (07/09/2026 – 19/10/2026)  
> **Nhân sự FE:** 01 Frontend Engineer (phối hợp 02 Backend Engineers)  
> **Tech Stack:** React 18, Vite, TailwindCSS, TanStack Query v5, Zustand, React Router v6, GSAP, Lucide Icons, Sonner  
> **Tài liệu API gốc:** `docs/api/*-v1.md` (25 modules, 17 FSMs, 9 Roles)  
> **Ngày rà soát hiện trạng:** 21/09/2026 (Kết thúc Tuần 2: 17–21/09/2026)

---

## 📊 BẢNG TỔNG HỢP TIẾN ĐỘ THỰC TẾ (AUDIT TRẠNG THÁI 21/09/2026)

| Nhóm Phân Hệ | Tổng số màn/task | ✅ Đã hoàn thành (UI + API) | 🟡 Đã hoàn thành UI (Mock/Store) | ⏳ Chưa làm (0%) | Tỷ lệ khả dụng UI |
|---|:---:|:---:|:---:|:---:|:---:|
| **1. Khung & Xác thực (Foundation & Auth)** | 5 | 5 | 0 | 0 | **100%** |
| **2. Cổng Khách Hàng (Customer Web B2C)** | 16 | 3 | 13 | 0 | **100% (Xong toàn bộ UI)** |
| **3. Cổng Nhân Viên & Quản Trị (Back-Office B2B)**| 11 | 0 | 11 | 0 | **100% (Xong toàn bộ 22 màn UI Mock/Store)** |
| **4. Tích Hợp & Polish (Integration & Demo)** | 4 | 0 | 0 | 4 | **0%** |
| **TỔNG CỘNG** | **36** | **8** | **24** | **4** | **~89% UI Tổng Thể (32/36 màn)** |

---

## 📋 BẢNG THEO DÕI CHI TIẾT TỪNG MÀN HÌNH (ROUTE ↔ API ↔ ROLE ↔ TRẠNG THÁI)

> **Ký hiệu trạng thái:**  
> - ✅ **Hoàn thành (Done):** Đã có UI chuẩn + Đã kết nối API thật / Store hoàn chỉnh.  
> - 🟡 **Đã hoàn thành UI (UI Ready / Mock Data):** Đã dựng xong 100% giao diện, interaction, modal, responsive, đang dùng Zustand / Mock Data / localStorage, chờ backend gắn API thật.  
> - ⏳ **Chưa làm (Not Started):** Chưa tạo màn hình / Chưa dựng route.

| ID | Nhóm | Màn hình / Tính năng | Route FE | Role truy cập | API Backend tương ứng (`docs/api/`) | Trạng thái | Hiện trạng code & Việc cần làm tiếp |
|:---:|---|---|---|---|---|:---:|---|
| **01** | Foundation | Setup Vite + React + TanStack Query + Tailwind | `/` | Tất cả | — | ✅ Hoàn thành | Đang chạy dev server mượt mà, cấu hình global styles, fonts, GSAP xong. |
| **02** | Foundation | Layout & Header/Footer Public | `/` | Khách / Tất cả | — | ✅ Hoàn thành | `SiteHeader.tsx` (sửa co ô tìm kiếm + fix che popup chuông thông báo), `SiteFooter.tsx`. |
| **03** | Auth | Đăng nhập hệ thống (Login) | `/auth/login` | Khách / Staff | `POST /api/v1/auth/login` | ✅ Hoàn thành | Đã kết nối `auth.api.ts`, lưu JWT, tự chuyển hướng sang OTP nếu chưa verify. |
| **04** | Auth | Đăng ký tài khoản (Register) | `/auth/register` | Khách hàng | `POST /api/v1/auth/register` | ✅ Hoàn thành | Đã kết nối API thật, validate form, gửi yêu cầu OTP sang mail/phone. |
| **05** | Auth | Xác thực OTP 6 số (Verify OTP) | `/auth/verify-otp` | Khách hàng | `POST /api/v1/auth/otp/verify` | ✅ Hoàn thành | Đếm ngược 60s, khóa sau 5 lần sai, kết nối API xác thực thật. |
| **06** | Customer | Trang chủ Hero Mascot tương tác | `/` | Khách / Tất cả | Mock data (Home) | ✅ Hoàn thành | Mascot theo dõi chuột, tương tác chọc ghẹo boop, đổi bé cưng, 3 card dịch vụ. |
| **07** | Customer | Gợi ý sản phẩm thông minh (Recommend) | `/recommend` | Khách hàng | Mock data (Products) | ✅ Hoàn thành | Lọc sản phẩm theo loài (chó/mèo), thẻ pet mini, thêm nhanh vào giỏ. |
| **08** | Customer | Khách sạn thú cưng / Tin tức / Giới thiệu | `/hotel`, `/news`, `/about` | Khách hàng | Mock data | 🟡 UI Ready | Giao diện tĩnh, bảng giá phòng khách sạn, bài viết cẩm nang (cần redesign nhẹ). |
| **09** | Customer | Danh mục sản phẩm & Chi tiết SP (Shop) | `/shop`, `/shop/:id` | Khách hàng | `GET /api/v1/catalog/products` | 🟡 UI Ready | Đã có UI lưới sản phẩm, bộ lọc giá/loài/hãng, nhận tham số search `?q=`. |
| **10** | Customer | Giỏ hàng mua sắm (Cart Page) | `/cart` | Khách hàng | `cart.store.ts` (Zustand) | 🟡 UI Ready | Đã có UI giỏ hàng, tăng giảm số lượng, tính tiền tự động; **Cần:** Gắn API kiểm tra kho. |
| **11** | Customer | Đặt hàng & Xác nhận đơn (Checkout) | `/checkout`, `/order/:id`| `CUSTOMER` | `POST /api/v1/orders`, `POST /api/v1/payments/*` | 🟡 UI Ready | Form nhận hàng, chọn phương thức thanh toán, tóm tắt đơn; **Cần:** Gọi API tạo Order & VNPAY. |
| **12** | Customer | Quản lý hồ sơ thú cưng (Pets CRUD) | `/pets` | `CUSTOMER` | `GET/POST /api/v1/pets` (`customer-pet-v1.md`) | 🟡 UI Ready | UI thêm/sửa/xóa pet, upload ảnh đại diện, tab thông tin thể trạng. |
| **13** | Customer | Sổ tiêm chủng & Lịch sử bệnh án (EMR) | `/pets` (tab Bệnh án & Tiêm) | `CUSTOMER` | `GET /api/v1/pets/{id}/vaccination-book` | 🟡 UI Ready | Đã có tab Bệnh án lâm sàng (chẩn đoán, đơn thuốc) + tab Tiêm chủng (lô vaccine, hạn tiêm nhắc). |
| **14** | Customer | Đặt lịch dịch vụ 4 bước (Booking) | `/booking` | `CUSTOMER` | `POST /api/v1/appointments/hold` | 🟡 UI Ready | Chọn Pet, chọn dịch vụ, chọn chi nhánh, chọn ngày giờ khả dụng. |
| **15** | Customer | Quản lý lịch hẹn & Hàng đợi Queue | `/appointments` | `CUSTOMER` | `GET/POST /api/v1/appointments/*` | 🟡 UI Ready | Phân loại 3 tab (Sắp tới/Xong/Hủy), thẻ số thứ tự Queue Ticket, modal Đổi lịch & Hủy lịch. |
| **16** | Customer | Gói dịch vụ trả trước (Prepaid Packages)| `/packages` | `CUSTOMER` | `GET/POST /api/v1/packages/*` (`package-v1.md`) | 🟡 UI Ready | Tab Gói của tôi (tiến trình 2/5 buổi, HSD) + Tab Mua gói mới (Combo Spa, Vaccine trọn gói). |
| **17** | Customer | Lịch sử đơn hàng & Hoàn tiền (Refund) | `/orders` | `CUSTOMER` | `GET /api/v1/orders/my`, `POST /api/v1/refunds` | 🟡 UI Ready | Danh sách đơn, mua lại, nút Hoàn tiền + Modal yêu cầu hoàn tiền đính kèm ảnh (`RULE-17-03`). |
| **18** | Customer | Hội viên & Điểm tích lũy (Loyalty) | `/membership` | `CUSTOMER` | `GET /api/v1/membership/me` (`membership-v1.md`) | 🟡 UI Ready | Hạng Bạc/Vàng/Kim Cương, thanh chi tiêu thăng hạng, đổi voucher từ điểm thưởng. |
| **19** | Customer | Quản lý ủy quyền chăm sóc (Caregivers) | `/caregivers` | `CUSTOMER` | `GET/POST /api/v1/caregivers/*` | 🟡 UI Ready | Mời Caregiver qua email, chọn pet ủy quyền, copy link mời, thu hồi quyền (`RevokeCaregiver`). |
| **20** | Customer | Kho mã ưu đãi cá nhân (Vouchers) | `/vouchers` | `CUSTOMER` | `GET /api/v1/vouchers/my` (`promotion-v1.md`) | 🟡 UI Ready | Danh sách voucher theo tab (Khả dụng/Đã dùng/Hết hạn), sao chép mã nhanh. |
| **21** | Customer | Đánh giá dịch vụ & Feedback | `/review` | `CUSTOMER` | Mock data (Reviews) | 🟡 UI Ready | Đánh giá sao Google Reviews, form gửi góp ý dịch vụ sau khi trải nghiệm. |
| **22** | Customer | Bảo mật tài khoản & Quyền riêng tư | `/security` | `CUSTOMER` | `api/consent-v1.md`, `iam-v1.md` | 🟡 UI Ready | Đổi MK, bật 2FA, phiên đăng nhập, Tải data cá nhân (JSON), Duyệt OTP bệnh án liên chi nhánh 24h, Xóa tài khoản. |
| **23** | Customer | Phương thức thanh toán / Cài đặt / Trợ giúp | `/payment`, `/settings`, `/help` | `CUSTOMER` | Local state | 🟡 UI Ready | Quản lý thẻ/ví điện tử, tùy chọn thông báo đẩy/email, hỏi đáp thường gặp FAQ. |
| **24** | Customer | Hộp thư thông báo (Inbox) | `/notifications` | `CUSTOMER` | `GET /api/v1/notifications` (`notification-v1.md`)| 🟡 UI Ready | Danh sách thông báo phân loại (Đơn hàng, Lịch hẹn, Khuyến mãi, Hệ thống), đánh dấu đã đọc. |
| **25** | Back-Office | Khung Admin Layout & Menu Phân Quyền (RBAC) | `/admin/*` | Toàn bộ 8 Staff Roles | `GET /api/v1/auth/me` | 🟡 UI Ready | Đã xong Sidebar Admin, Header Admin, Route Guard phân quyền cho 8 vai trò (`SUPER_ADMIN`, `STORE_MANAGER`, `RECEPTIONIST`, `VETERINARIAN`, `GROOMER`, `INVENTORY_STAFF`, `FINANCE_STAFF`, `ORG_ADMIN`). Cổng đăng nhập `/admin/login` cao cấp chọn 8 role 1 chạm. |
| **26** | Back-Office | Bàn Tiếp tân: Check-in & Hàng đợi Walk-in | `/admin/queue`, `/admin/appointments` | `RECEPTIONIST`, `STORE_MANAGER`, `VETERINARIAN` | `appointment-v1.md`, `queue-v1.md` | 🟡 UI Ready | Đã dựng xong UI danh sách khách hẹn hôm nay, check-in, bốc số thứ tự đón khách walk-in FIFO, gọi số vào phòng khám/spa. |
| **27** | Back-Office | Bàn Tiếp tân: Thu ngân POS & Hóa đơn | `/admin/pos`, `/admin/invoices`, `/admin/payments` | `RECEPTIONIST`, `FINANCE_STAFF`, `STORE_MANAGER` | `invoice-v1.md`, `payment-v1.md` | 🟡 UI Ready | Đã dựng xong Quầy bán lẻ POS tại quầy, quản lý giỏ hàng, xuất hóa đơn, thanh toán tiền mặt/thẻ. |
| **28** | Back-Office | Bàn Bác sĩ Thú y: Hồ sơ bệnh án EMR | `/admin/exam` | `VETERINARIAN`, `STORE_MANAGER` | `clinical-v1.md` | 🟡 UI Ready | Đã dựng xong UI danh sách thú chờ khám, form chẩn đoán lâm sàng, phác đồ điều trị, đơn thuốc. |
| **29** | Back-Office | Bàn Bác sĩ: Tiêm chủng & Quản lý lô vaccine | `/admin/vaccination`, `/admin/vaccines` | `VETERINARIAN`, `INVENTORY_STAFF`, `STORE_MANAGER` | `vaccination-v1.md` | 🟡 UI Ready | Đã dựng xong UI hồ sơ tiêm chủng, quản lý danh mục vaccine, theo dõi số lô & hạn dùng bảo quản lạnh 2°C - 8°C. |
| **30** | Back-Office | Bàn Kỹ thuật viên Grooming / Spa | `/admin/grooming` | `GROOMER`, `STORE_MANAGER`, `RECEPTIONIST` | `grooming-v1.md` | 🟡 UI Ready | Đã dựng xong Bảng Kanban grooming spa, cập nhật tiến trình tắm/tỉa, ghi chú thể trạng thú cưng. |
| **31** | Back-Office | Luồng Duyệt Hoàn Tiền (Refunds Maker-Checker)| `/admin/refunds` | `STORE_MANAGER`, `FINANCE_STAFF`| `refund-v1.md` | 🟡 UI Ready | Đã dựng xong UI tiếp nhận yêu cầu hoàn tiền, cơ chế phê duyệt/từ chối Maker-Checker. |
| **32** | Back-Office | Quản lý Kho chi nhánh & Mua hàng | `/admin/warehouse`, `/admin/purchasing` | `INVENTORY_STAFF`, `STORE_MANAGER` | `inventory-v1.md` | 🟡 UI Ready | Đã dựng xong UI quản lý kho hàng hóa, kiểm kê tồn kho và tạo đơn mua hàng nhà cung cấp. |
| **33** | Back-Office | Phân ca làm việc nhân viên (Workforce) | `/admin/workforce` | `STORE_MANAGER`, `ORG_ADMIN` | `workforce-v1.md` | 🟡 UI Ready | Đã dựng xong UI bảng xếp ca làm việc hàng tuần cho Bác sĩ, Groomer, Tiếp tân. |
| **34** | Back-Office | Báo cáo Dashboard, Kiểm toán & Quản trị | `/admin/dashboard`, `/admin/reports`, `/admin/audit`, `/admin/users`, `/admin/tenants` | `STORE_MANAGER`, `ORG_ADMIN`, `SUPER_ADMIN` | `report-v1.md` | 🟡 UI Ready | Đã dựng xong Dashboard số liệu kinh doanh, báo cáo doanh thu, nhật ký kiểm toán (Audit), quản lý tổ chức & tài khoản người dùng. |
| **35** | Integration | Rà soát lỗi E2E & Chặn Route 401/403 | Toàn hệ thống | Tất cả | 100% Backend APIs | ⏳ Chưa làm | Auto logout khi token hết hạn, thông báo lỗi validation đỏ đúng ô input. |
| **36** | Demo | Kịch bản tổng duyệt Demo trực tiếp | Toàn hệ thống | Cả nhóm (3 người) | Toàn hệ thống | ⏳ Chưa làm | Rehearsal kịch bản 4 vai: Khách đặt lịch -> Tiếp tân đón -> Bác sĩ khám -> Quản lý xem báo cáo. |

---

## 📅 ROADMAP CHI TIẾT THEO 6 TUẦN (MAPPING VỚI TIMELINE CHUNG DỰ ÁN)

> **Căn cứ:** Sheet `Timeline Dự án` trong `docs/timeline_petcare2.xlsx`  
> **Nguyên tắc hệ thống:** *Monolithic • Admin First • FSM-driven*  
> **Hiện trạng ngày 21/09/2026:** Kết thúc **Tuần 2 (17–21/09)**. FE đã hoàn thành 100% UI khách hàng B2C và hoàn tất tích hợp toàn bộ phân hệ Back-Office / Admin Core (22 màn hình quản trị & staff, layout RBAC, trang đăng nhập riêng biệt) vào ứng dụng Vite duy nhất. Đạt ~89% UI toàn hệ thống (32/36 màn). Sẵn sàng cho Tuần 3: kết nối API bàn tiếp tân, bác sĩ và hoàn tất thanh toán.

```
[Tuần 1: 10–14/09]  ██████████████████████████████  (Vite + Auth + UI Customer Foundation)     ✅ ĐÃ XONG
[Tuần 2: 17–21/09]  ██████████████████████████████  (100% UI Customer + 100% UI Admin Core RBAC) ✅ ĐÃ XONG VƯỢT TIẾN ĐỘ
[Tuần 3: 24–28/09]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Bàn Tiếp Tân POS/Queue + Bàn Bác Sĩ EMR + Nối API) 🔄 TIẾP THEO
[Tuần 4: 01–05/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Invoices POS + Hoàn tiền Maker-Checker + Vaccine)
[Tuần 5: 08–12/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Grooming Spa + Kho hàng + Workforce + Dashboard)
[Tuần 6: 15–19/10]  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (E2E Integration + Mobile Responsive + Tổng duyệt Demo)
```

---

### TUẦN 1 (10–14/09/2026): NỀN TẢNG & AUTHENTICATION (ĐÃ XONG ✅)
- [x] **ID 15 [W1 - Foundation]:** Setup Vite + React + TailwindCSS + Routing + Auth Context + Layout dùng chung.
- [x] **ID 16 [W1 - Auth]:** Màn hình Đăng nhập (`/auth/login`), Đăng ký (`/auth/register`), Nhập OTP (`/auth/verify-otp`) + tích hợp JWT.
- [x] **API Auth:** Đã nối Axios client với BE (`POST /api/auth/login`, `register`, `otp/verify`).
- [x] **Customer Foundation:** Đã dựng xong Trang chủ Hero Mascot GSAP, Recommend, Hotel, Shop mockup.

---

### TUẦN 2 (17–21/09/2026): CORE DOMAIN — PETS, COMMERCE & ADMIN CORE (ĐÃ XONG VƯỢT TIẾN ĐỘ ✅)
- [x] **ID 25 [W2 - Pets & Caregivers]:**
  - Đã dựng xong 100% UI Quản lý thú cưng (`/pets`) kèm tab Bệnh án EMR và Sổ tiêm chủng.
  - Đã dựng xong 100% UI Quản lý ủy quyền (`/caregivers`) (mời người chăm sóc, copy link, thu hồi).
  - *Việc tiếp theo:* Nối API backend thật (`/api/pets`, `/api/pets/{id}/caregiver-invitations` - BE đã có sẵn).
- [x] **ID 26 [W2 - Products & Cart]:**
  - Đã dựng xong 100% UI Danh mục sản phẩm (`/shop`), Chi tiết SP (`/shop/:id`), Giỏ hàng (`/cart`), Checkout (`/checkout`).
  - Đã căn chỉnh header tìm kiếm `?q=` và fix popover thông báo không bị che.
- [x] **Tiện ích Customer mở rộng (Vượt tiến độ):**
  - Dựng xong Gói dịch vụ trả trước (`/packages`), Lịch hẹn (`/appointments`), Đơn hàng & Hoàn tiền (`/orders`), Hội viên (`/membership`), Voucher (`/vouchers`), Bảo mật & Quyền riêng tư (`/security`), Đánh giá (`/review`).
- [x] **FE-T2-06 [Admin Core - ĐÃ HOÀN TẤT VƯỢT TIẾN ĐỘ]:** Dựng khung **Admin Layout & Phân quyền RBAC** (`/admin/*`):
  - Hợp nhất toàn bộ 22 màn hình Back-Office vào project Vite duy nhất (`FE/src/pages/admin/*`), dọn dẹp sạch sẽ project Next.js cũ.
  - Xây dựng Sidebar Admin co giãn, gom nhóm menu khoa học, phân quyền động theo 8 vai trò nhân viên.
  - Hoàn thiện cổng đăng nhập chuyên nghiệp `/admin/login` (thiết kế Linear/Awwwards-style với switch role 1 chạm test nhanh).
  - Route Guard & Session Store (`admin-session.store.ts`) bảo vệ route chặt chẽ.

---

### TUẦN 3 (24–28/09/2026): COMMERCE P1 — BÀN TIẾP TÂN & BÀN BÁC SĨ (TRỌNG TÂM TIẾP THEO 🔄)
- [ ] **ID 35 [W3 - Appointments & Queue]:** Đặt lịch 4 bước + tích hợp slot realtime với Backend.
- [ ] **ID 36 [W3 - Orders & Payment]:** Checkout giỏ hàng + kết nối Cổng thanh toán VNPAY sandbox thật.
- [ ] **FE-T3-01 [Staff - Tiếp tân]:** Bàn Tiếp tân / Lễ tân (`/admin/queue`, `/admin/appointments`, `/admin/pos`):
  - Danh sách khách hẹn hôm nay theo ca trực + Nút bấm tiếp đón **Check-in**.
  - Bốc số thứ tự đón khách vãng lai (Walk-in FIFO) + Màn hình gọi số TV.
  - Thu ngân tại quầy (POS): Bán lẻ, thanh toán tiền mặt/QR, xuất hóa đơn K80.
- [ ] **FE-T3-02 [Staff - Bác sĩ]:** Bàn làm việc Bác sĩ Thú y (Clinical EMR) (`/admin/exam`, `/admin/vaccines`):
  - Hàng đợi bệnh nhân chờ khám từ tiếp tân chuyển vào.
  - Hồ sơ bệnh án điện tử: Nhập triệu chứng, chẩn đoán bệnh, lập phác đồ, kê đơn thuốc.
  - Quét mã vạch lô vaccine, ghi nhận mũi tiêm, tự tính lịch hẹn tiêm nhắc.

---

### TUẦN 4 (01–05/10/2026): COMMERCE P2 — HOÀN TIỀN MAKER-CHECKER, KHO HÀNG & GROOMER
- [ ] **ID 45 [W4 - Clinical]:** Hoàn thiện tích hợp API EMR viewer + form khám / kê đơn với BE-2.
- [ ] **ID 46 [W4 - Finance]:** Hoàn thiện tích hợp Invoices + Refunds + Vouchers:
  - Màn hình duyệt hoàn tiền Maker-Checker cho Quản lý (`/admin/refunds`).
  - Áp mã giảm giá voucher và trừ điểm hội viên từ BE thật.
- [ ] **FE-T4-02 [Staff - Groomer]:** Bàn làm việc KTV Grooming Spa (`/admin/grooming`).
- [ ] **FE-T4-03 [Admin - Kho]:** Quản lý tồn kho thực tế/khả dụng, nhập/xuất/chuyển kho (`/admin/warehouse`, `/admin/purchasing`).

---

### TUẦN 5 (08–12/10/2026): FE INTEGRATION — WORKFORCE & BÁO CÁO DOANH THU
- [ ] **ID 53 [W5 - Queue & Vaccine]:** Bàn tiếp tân bốc số Walk-in FIFO + Bác sĩ quét mã lọ vaccine FEFO.
- [ ] **ID 54 [W5 - Admin Suite]:** Grooming + Workforce (Phân ca trực nhân viên) + Báo cáo Dashboard doanh thu.
- [ ] **ID 55 [W5 - Polish]:** Notifications SSE realtime + responsive/mobile polish.

---

### TUẦN 6 (15–19/10/2026): TỔNG DUYỆT POLISH & DEMO (MILESTONE M4)
- [ ] **ID 60 [W6 - Final Polish]:** Tích hợp hoàn thiện toàn bộ các module, xử lý token 401/403.
- [ ] **ID 61 [W6 - Rehearsal Demo]:** Tổng duyệt kịch bản Demo Live 4 vai (Khách hàng -> Tiếp tân -> Bác sĩ -> Quản lý) trước hội đồng bảo vệ (18–19/10).

---

## 🔑 DANH SÁCH TÀI KHOẢN SEED TEST ĐỂ TRUY CẬP CÁC PHÂN HỆ

| Role | Email đăng nhập | Mật khẩu | Phân hệ truy cập |
|---|---|---|---|
| **CUSTOMER** | `customer@petcare.vn` | `Petcare@123` | Cổng Khách hàng B2C (`/`, `/shop`, `/pets`, `/booking`, v.v.) |
| **RECEPTIONIST** | `reception@petcare.vn` | `Petcare@123` | Bàn Lễ tân, Check-in, Bốc số FIFO, Thu ngân POS (`/admin/pos`, `/admin/queue`) |
| **VETERINARIAN** | `vet@petcare.vn` | `Petcare@123` | Bàn Bác sĩ Thú y, Bệnh án EMR, Tiêm chủng (`/admin/exam`, `/admin/vaccines`) |
| **GROOMER** | `groomer@petcare.vn` | `Petcare@123` | Bàn Kỹ thuật viên Grooming Spa (`/admin/grooming`) |
| **INVENTORY_STAFF** | `inventory@petcare.vn` | `Petcare@123` | Quản lý kho, nhập hàng, kiểm kê tồn kho (`/admin/warehouse`, `/admin/purchasing`) |
| **FINANCE_STAFF** | `finance@petcare.vn` | `Petcare@123` | Quản lý thu chi, thanh toán, duyệt hoàn tiền Maker-Checker (`/admin/payments`, `/admin/refunds`) |
| **STORE_MANAGER** | `manager@petcare.vn` | `Petcare@123` | Duyệt Hoàn tiền Maker-Checker, Quản lý ca trực, Báo cáo chi nhánh (`/admin/*`) |
| **ORG_ADMIN** | `orgadmin@petcare.vn` | `Petcare@123` | Quản lý toàn chuỗi chi nhánh, phòng ban, cấu hình vận hành |
| **SUPER_ADMIN** | `admin@petcare.vn` | `Petcare@123` | Toàn quyền cấu hình Chuỗi, Chi nhánh, Người dùng & Quyền hạn (`/admin/*`) |

> 💡 **Cách test nhanh các vai trò Staff/Admin:**  
> Truy cập `http://localhost:5173/admin/login` -> Click vào badge vai trò muốn test (ví dụ: *Bác sĩ*, *Lễ tân*, *Kỹ thuật viên Spa*...) -> Form tự điền thông tin -> Bấm **Đăng nhập vào hệ thống** để vào Dashboard tương ứng với menu được phân quyền tự động.


