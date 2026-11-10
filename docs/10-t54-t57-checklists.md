# Pet Care Ecosystem — Checklist duyệt đặc tả tầng 3 (T54) và danh mục màn hình FE (T57)

> **T54 đã được Hiến (nhóm trưởng) duyệt 5/11/2026.** Phần 1 giữ lại làm bản ghi: ai đọc gì, câu hỏi đã quyết thế nào, xung đột đã xử lý ra sao. BE-1 (Mạnh), BE-2 (Dũng) đọc khi bắt đầu task tương ứng và muốn đổi thì qua ADR. Phần 2 (T57) vẫn ở mức **danh mục màn hình, route, quyền và trang cũ cần xử lý**; chưa viết dòng code FE nào, chưa xóa trang nào.
> **Đính chính 2026-11-09 ([ADR-0125](./adr/0125-drop-surgery-module.md)):** phẫu thuật (UC50, FSM #23, `surgery-v1`, T61) đã bỏ sau khi T54 duyệt; các dòng nhắc phẫu thuật bên dưới là bản ghi lịch sử, không còn hiệu lực.
> **Đính chính 2026-11-08 ([ADR-0122](./adr/0122-drop-complaint-feedback-stateless-retention.md)):** module khiếu nại (UC85, UC86, ST16, ST17, FSM #26, `complaint-v1`) đã bỏ sau khi T54 duyệt; các dòng nhắc khiếu nại bên dưới là bản ghi lịch sử của T54, không còn hiệu lực.

---

## Phần 1. T54 — rà soát chéo và duyệt

### 1.1 Ai đọc gì

| Người | Đọc | Vì sao |
|---|---|---|
| **Mạnh (BE-1)** | [`09`](./09-tang3-be1-spec.md); Nhân sự ở `02` §7, `03` #11–#12, [`workforce-v1`](./api/workforce-v1.md); mục BE-1 trong `07` §11 (I-2, I-6, I-9, I-10) | Chủ của ~~phẫu thuật~~ (bỏ, ADR-0125), nội trú, ~~khiếu nại~~ (bỏ, ADR-0122), ST07, Nhân sự |
| **Dũng (BE-2)** | [`08`](./08-tang3-be2-spec.md); mục BE-2 trong `07` §11 (I-1, I-3, I-4, I-5, I-7, I-8) và `09` (phần "Thay đổi cần BE-2 xác nhận") | Chủ của thương mại điện tử, thanh toán, trả hàng, kho mở rộng, chi nhánh, gộp khách, bài viết |
| **Hiến (FE-1)** | [`07`](./07-ai-subsystem.md), [`adr/0109`](./adr/0109-ai-llm-embedding-vector-store.md); §2 của file này | Chủ AI; FE phải bám hợp đồng |
| **Cả nhóm** | §1.3 (câu hỏi cần quyết) và §1.4 (xung đột) | Quyết một lần cho cả ba file |

### 1.2 Phủ phạm vi — mỗi UC / ST có đủ chưa

Đã kiểm: mỗi mục dưới đây có quy tắc, máy trạng thái (nơi có chuyển trạng thái), dữ liệu và endpoint trong generator; `check-contracts` PASS **24 file, 344 operation** (231 GĐ1 + 113 tầng 3); lint OpenAPI hợp lệ cho 15 file nháp. **Chưa** có người nào ngoài tác giả đọc.

| Nhóm | UC / ST | Nơi viết | FSM | Module API |
|---|---|---|---|---|
| Nhân sự | UC34–38 | `02` §7, `03`, `04`, `05` | #11, #12 | `workforce` (14) |
| AI | UC90–94 · ST22, ST23 | `07` | #13 | `ai` (13) |
| BE-2 | UC32 | `08` §A | — | `fees` (6) |
| | UC61, 62, 64, 65 · ST09, 10, 12 | `08` §B | #14 | `shop` (19) |
| | UC63 · ST11, 14 | `08` §C | #15 | `payment` (6) |
| | UC68, 69 · ST21 | `08` §D | #16, #17 | `returns` (9) |
| | UC77–79 | `08` §E | #18, #19 | `stockops` (13) |
| | UC13 · UC27 · UC80, UC82 | `08` §F | #20, #21 | `branchlife` (4), `custmerge` (2), `articlex` (14) |
| | ~~UC50~~ (bỏ, ADR-0125) | `09` §B | ~~#23~~ | ~~`surgery` (8)~~ |
| | UC55, UC56 | `09` §C | #24, #25 | `inpatient` (10) |
| | UC85, UC86 · ST16, ST17 | `09` §D | #26 | `complaint` (12) |
| | ST07 | `09` §E | — | `materialuse` (3) |

**Đủ 22 UC tầng 3** (UC13, 27, 32, 34–38, 55, 56, 61–65, 68, 69, 77–79, 80, 82) **và 8 ST** (ST07, 09–12, 14, 21, 24) của `INDEX.md`, cộng 5 UC và 2 ST của AI.

### 1.3 Câu hỏi cần quyết (gộp từ `07`, `08`, `09` và các hợp đồng)

| # | Câu hỏi | Ai quyết | Nguồn | Chặn |
|---|---|---|---|---|
| 1 | **Chọn cổng thanh toán** (VNPay / ZaloPay / MoMo) và đăng ký sandbox | Nhóm | `08` §C Q1 | T65 |
| 2 | **Tên bác sĩ thú y** rà soát tri thức AI (quy trình hai lớp đã chốt) | Nhóm | `07` Q2 | T56, T77 |
| 3 | Xác nhận hạ tầng pgvector ở T55 (đổi ảnh compose, Testcontainers, V34) | Hiến, Mạnh, Dũng | `07` §17.1, ADR-0109 D3 | T55, T77 |
| 4 | `services.medical_type` thêm `SURGERY`, `INPATIENT`; `orders.source` thêm `ONLINE` | Dũng | `09` §C; `08` §B | T69 (`SURGERY` bỏ ở ADR-0125) |
| 5 | Package `inpatient` và `complaint` thuộc BE-1 (đề xuất) hay `boarding` / `content` của BE-2 | Mạnh, Dũng | `09` đầu file | T69, T70 |
| 6 | Hoàn tiền sau `PAID` không đổi Order; báo cáo doanh thu trừ hoàn tiền và phí vận chuyển thế nào | Dũng | `08` §B, §G | T73, báo cáo |
| 7 | `stock_reservations` đổi định nghĩa "tồn khả dụng" (BR-KO-01) ở mọi nơi tính tồn | Dũng | `08` §G | T63 |
| 8 | Quy mô đóng chi nhánh: Care Task `OPEN` chuyển đi hay hủy | Mạnh | `08` §F.1, branchlife Q1 | T75 |
| 9 | Phạm vi gộp khách của BRANCH_MANAGER (toàn chuỗi hay chi nhánh mình) | Cả nhóm | custmerge Q1 | T75 |
| 11 | Khách xem được tình trạng nội trú và trạng thái hoàn tiền không | PO / Hiến | `09` §C Q4; `08` §D Q4 | T69, T73 |
| 12 | Tuổi `SENIOR` theo loài (tạm chó 7, mèo 10) | Người có chuyên môn | `07` Q3 | T78 |
| 13 | Ý định cá nhân của chatbot: thêm lưu trú, tái khám ở GĐ2? | Nhóm | `07` Q7 | T77 |

**Đã chốt (5/11/2026, Hiến):** LLM tạm Gemini gói free, không trả phí khi demo, xem lại trước khi chạy thật (`07` BR-AI-25); kho vector pgvector; quy trình tri thức hai lớp.

### 1.3b Kết quả: các câu hỏi đã chốt (Hiến duyệt 5/11/2026)

| # | Quyết định |
|---|---|
| 1 Cổng thanh toán | **VNPay** (đề xuất); hướng dẫn đăng ký sandbox ở [`payment-gateway-setup.md`](./payment-gateway-setup.md). T65 chờ Hiến có sandbox |
| 2 Tên bác sĩ thú y | **Còn mở**: chỉ chặn việc xuất bản tri thức AI, không chặn cài đặt |
| 3 Hạ tầng pgvector | **Để T55**, làm trong phiên riêng có chạy IT (xem prompt `T55a`) |
| 4 `medical_type`, `ONLINE` | Chốt theo đề xuất; BE-2 xác nhận khi làm T55 |
| 5 Package `inpatient`, `complaint` | **BE-1** |
| 6 Hoàn tiền và báo cáo doanh thu | Báo cáo trừ hoàn tiền, phí vận chuyển báo riêng |
| 7 Tồn khả dụng trừ phần giữ | Bắt buộc |
| 8 Care Task khi đóng chi nhánh | Chuyển sang chi nhánh chọn, không chọn thì hủy kèm lý do |
| 9 Phạm vi gộp khách | Mọi hồ sơ (toàn chuỗi), audit đầy đủ |
| 11 Khách xem nội trú, hoàn tiền | Nội trú chưa; trạng thái hoàn tiền: có (thêm `refundStatus` vào `OnlineOrder` ở T73) |
| 12 Tuổi `SENIOR` | Giữ tạm chó 7, mèo 10, cấu hình được |
| 13 Ý định cá nhân của chatbot | Hai ý định (lịch hẹn, lịch tiêm); lưu trú, tái khám để sau |

### 1.4 Xung đột và số cần thống nhất

- **Chủ sở hữu:** khiếu nại (DG) thuộc `content` của BE-2 theo `06` §2 nhưng T70 giao BE-1; nội trú (LT) thuộc `boarding` của BE-2 nhưng T69 giao BE-1 (đề cương 2.4.4 ghi "LT (nội trú) – Mạnh"). Đề xuất: package mới của BE-1.
- **Số mã BR** là số tạm (`08` và `09` đầu file): grep lại `02` trước khi chép vì BE-1, BE-2 có thể vừa dùng số đó. Số lớn nhất đã kiểm 5/11: BR-SP-07, BR-BH-06, BR-TG-05, BR-KO-07, BR-CN-05, BR-KH-10, BR-BV-04, BR-LH-12, BR-KB-06, BR-LT-12, BR-DG-04.
- **Số FSM:** #11–#12 NS, #13 AI, #14–#21 BE-2, #22–#26 BE-1.
- **ADR kế tiếp 0110** (0109 là AI). **Flyway kế V34** (V33 đã dùng): **đã chia ở T54 (5/11/2026):** Flyway V34–V35 cho AI + extension `vector` (T55a, FE-1), V36–V41 BE-1, V42–V49 BE-2; ADR 0110 cho T55a, 0111–0115 BE-1, 0116–0120 BE-2. Hết dải thì hỏi người kia, không lấn. Các nhóm migration cần: NS; AI (+ extension `vector`); shop + payment + returns; stockops; branchlife + custmerge + articlex; surgery + inpatient + complaint + materialuse; seed `ConfigKey`. Đề xuất chia dải số (BE-1 và BE-2 mỗi bên một dải) để không trùng như sự cố ADR-0088; **chưa chốt**.
- **Path:** đã grep không trùng `docs/api/openapi/*.yaml`; đơn online ở `/me/online-orders`, `/online-orders`.

### 1.5 Tiêu chí duyệt (theo `time.md` T54) và kết quả

Tiêu chí: một mục được coi là duyệt khi: (1) người sở hữu module đã đọc và đồng ý hoặc sửa; (2) số mã BR / FSM đã chốt và chép sang `02`–`06`; (3) câu hỏi mở ở §1.3 liên quan đã có trả lời; (4) interface chéo có chủ và hạn giao ở `06` §6.

### 1.6 Liên kết sang `02`–`06` (đã làm)

Bảng chi tiết ở cuối [`07`](./07-ai-subsystem.md) §17.1, [`08`](./08-tang3-be2-spec.md) §G, [`09`](./09-tang3-be1-spec.md) §G. Tóm lại: BR → `02`, FSM #13–#26 → `03`, model → `04` §12.x, bảng và cột → `05` §14.x, interface và sự kiện → `06` §3, §4, §6, `ConfigKey` mới → T55.

### 1.7 Cấu hình [CFG] mới cần thêm vào `ConfigKey` (T55)

`ai.*` (14 khóa, `07` §4.6); `shop.payment_window_hours`, `shop.auto_confirm_days`, `shop.max_redelivery`, `payment.txn_ttl_minutes`, `payment.refund_max_attempts`, `returns.window_days`, `content.comment_per_hour` (`08` §G); `complaint.max_open`, `complaint.reopen_days`, `complaint.max_reopen`, `complaint.sla_open_hours`, `complaint.sla_resolve_hours`, `complaint.auto_close_days` (`09` §G); `workforce.request_lead_hours`, `workforce.absence_report_max_days` (`06` §6). Không thêm khóa cho `reminder.vaccine_days_before` (đã có).

---

## Phần 2. T57 — danh mục màn hình, route và trang cũ

> **Chưa làm code FE** (làm ở T57, phiên riêng). Lý do: cả bộ FE có hơn 3 700 ca test và việc gỡ trang cần Hiến duyệt; hợp đồng đã được duyệt ở T54 (5/11) nên mock làm được. Danh mục dưới đây để T57 bắt đầu nhanh. Chỉ 1280 px (quyết định 27/10). Mock trả envelope thành công `{data, message, code}`; lỗi 6 trường như `docs/api/00-method.md` §3.5.

### 2.1 Màn hình cho 22 UC tầng 3 và 5 UC AI

| UC | Màn hình / route đề xuất | Vai trò (7 role) | Hợp đồng | Trang cũ liên quan |
|---|---|---|---|---|
| UC13 | Tab "Tạm ngừng / đóng cửa" trong `/admin/branches/:branchId/:tab` | SUPER_MANAGER | `branchlife` | — |
| UC27 | Chức năng "Gộp hồ sơ" ở màn tra cứu khách (xem trước → xác nhận) | BRANCH_MANAGER | `custmerge` | `/admin/customers` (hiện VET, CARETAKER) — kiểm lại chỗ đặt |
| UC32 | Tab "Phí & định mức" ở `/admin/catalog` | SUPER_MANAGER | `fees` | `/admin/catalog` |
| UC34, 37, 38 | `/admin/workforce` (ca, phân ca, duyệt, vắng đột xuất) | BRANCH_MANAGER | `workforce` | `/admin/workforce` (mock cũ) |
| UC35, 36 | `/admin/my-schedule` (lịch của tôi, gửi / rút yêu cầu) | RECEPTIONIST, VET, CARETAKER | `workforce` | — mới |
| ~~UC50~~ | ~~Khung phẫu thuật trong lượt khám (VET); danh sách chờ cam kết (lễ tân)~~ — bỏ ở ADR-0125, FE gỡ (D063) | — | — | — |
| UC55, 56 | `/admin/inpatient` (danh sách, chi tiết, y lệnh, theo dõi, xuất viện) | VET, CARETAKER, RECEPTIONIST (xem) | `inpatient` | — mới |
| UC61 | `/cart` | CUSTOMER | `shop` | `/cart` (mock cũ) |
| UC62 | `/checkout` (địa chỉ, chi nhánh, báo giá) | CUSTOMER | `shop` | `/checkout` |
| UC63 | Trang kết quả thanh toán (quay về từ cổng) | CUSTOMER | `payment` | `/payment`, `/order/:id` |
| UC64 | `/orders`, chi tiết đơn | CUSTOMER | `shop` | `/orders`, `/order/:id` |
| UC65 | `/admin/orders` (xử lý, bàn giao, giao thất bại) | RECEPTIONIST | `shop` | `/admin/orders` (mock cũ) |
| UC68, 69 | `/admin/refunds` đổi thành "Trả hàng & hoàn tiền" | RECEPTIONIST (tạo, xác nhận), BRANCH_MANAGER (duyệt) | `returns` | `/admin/refunds` (mock cũ) |
| UC77–79 | Thêm tab "Chuyển kho", "Kiểm kê", "Truy vết lô" ở `/admin/warehouse/:tab` | BRANCH_MANAGER; VET (truy vết) | `stockops` | `/admin/warehouse` |
| UC80 | `/admin/my-articles` (soạn, gửi duyệt) | VET | `articlex` | — mới |
| UC81 (mở rộng) | Hàng chờ duyệt ở `/admin/articles/:tab`; ẩn bình luận | SUPER_MANAGER | `articlex` | `/admin/articles` |
| UC82 | Khối bình luận ở `/news/:slug` | CUSTOMER (đăng), ẩn danh (xem) | `articlex` | `/news/:slug` |
| ~~UC85~~ | ~~`/complaints`; tạo thay khách~~ — bỏ (ADR-0122) | — | — | — |
| ~~UC86~~ | ~~`/admin/incidents` đổi thành "Khiếu nại"~~ — bỏ (ADR-0122) | — | — | — |
| UC90, 91 | Khung chat (hai chế độ: khách vãng lai gọi endpoint công khai, đã đăng nhập gọi `/me`) | mọi người; CUSTOMER (cá nhân) | `ai` | `InteractiveMascotCompanion` |
| UC92 | Khu vực gợi ý theo thú | CUSTOMER | `ai` | `/recommend` |
| UC93, 94 | Tab "Tài liệu tri thức", "Hồ sơ gợi ý" ở `/admin/ai` | SUPER_MANAGER | `ai` | `/admin/ai` |

Quy tắc route: chặn theo 7 role (CUSTOMER, RECEPTIONIST, VET, CARETAKER, BRANCH_MANAGER, SUPER_MANAGER, ADMIN) bằng `staffOnly(...)` / guard khách sẵn có; chức năng ngoài quyền bị **ẩn**, không chỉ báo lỗi.

### 2.2 Trang cũ cần xử lý (đề xuất, **chưa xóa gì**)

Danh sách lấy từ `staff-menu.ts` và `App.tsx` (5/11/2026); các trang đã ẩn khỏi menu ở T49 (`/admin/queue`, `/admin/exam`, `/admin/vaccination`, `/admin/grooming`, `/admin/invoices`) đã chuyển hướng theo vai trò.

| Nhóm | Trang | Đề xuất |
|---|---|---|
| **Làm lại trên hợp đồng mới** | `/cart`, `/checkout`, `/payment`, `/order/:id`, `/orders`, `/admin/orders`, `/admin/refunds`, `/admin/workforce`, `/admin/incidents`, `/admin/ai`, `/recommend`, khung chat mascot | Giữ khung, thay mock theo hợp đồng nháp |
| **Ngoài phạm vi (menu đã ghi "ngoài phạm vi")** | `/admin/promotions`, `/admin/membership`, `/membership`, `/vouchers`, `/packages`, `/caregivers`, `/favorites` | Ứng viên gỡ; **cần Hiến xác nhận từng trang**. Kế hoạch cũ có Loyalty (`/membership`) và Caregiver (`/caregivers`) đã lỗi thời theo v16; `/vouchers`, `/packages`, `/favorites`, `/admin/promotions` chưa kiểm có UC nào đỡ không (menu ghi "ngoài phạm vi" cho `/admin/promotions`, `/admin/membership`) |
| **Mã chết** | `clinic-db.ts`, `clinic.api.ts`, `useClinic.ts` (`docs/testcases/T37-fe.md` §B.6) | Gỡ cùng test, **sau** T57 vì mock cũ có thể còn trỏ vào |
| **Giữ** | `/admin/payments` (ca thu ngân, UC72), các trang GĐ1 khác | Không đụng |

**Đã làm 7/11/2026 (Hiến duyệt):** gỡ cả bảy trang "Ngoài phạm vi" và mã chết `clinic-db.ts`, `clinic.api.ts`, `useClinic.ts`, kèm `cart.store.ts` và `recommend.mock.ts` (không ai import). Kiểm UC trước khi gỡ: `docs/01`, `02`, `04` không có UC nào dùng voucher, khuyến mãi, hội viên, yêu thích, người chăm sóc, gói dịch vụ. `/shop/:id` giữ. Chưa gỡ vì ngoài danh sách: `AdminPurchasingPage.tsx`, `AdminVaccinesPage.tsx`, `shared/stores/notifications.store.ts` (không ai import). Cùng ngày đã gỡ các class CSS chết của `RecommendPage.module.css` (`filterBar`, `segment`, `sortSelect`, `cardAction`, `cardRating`, `cardOldPrice`, `skeleton` và lớp con). URL đã gỡ và mọi URL lạ ra trang 404 (`pages/NotFoundPage.tsx`, route `*` trong khung công khai, thêm 8/11/2026 theo duyệt của Hiến).

### 2.3 Việc FE-1 làm khi hợp đồng được duyệt (T57 → T66–T68, T79–T82)

1. Khung route và guard cho các route mới ở §2.1 (7 role, 1280 px).
2. Mock bám đúng `docs/api/<module>-v1.yaml` (cùng khuôn `*.mock.ts` hiện có: hai lớp envelope, mã lỗi, thứ tự kiểm 401 → 400 → 403 → 404 → 409).
3. Thiết kế màn hình theo `FE/direction-approved.md`; hợp đồng AI có thêm thông báo "câu hỏi gửi tới Google" (BR-AI-25).
4. Gỡ trang cũ theo danh sách đã duyệt ở §2.2, kèm test.
5. Chạy `npm` trong `FE/`, **không** chạy ở `D:/Do-an` (đã gây mất `gsap` / `react` trước đây).
