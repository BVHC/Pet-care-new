# Identity API v1 — Pet Care v16

> **Module:** TK, QT · **Owner:** BE-1. Đăng ký, OTP, đăng nhập, mật khẩu, hồ sơ nhân viên, liên kết hồ sơ khách, quản lý nhân viên, khóa/mở khóa, tham số, mẫu thông báo, audit, hồ sơ bác sĩ công khai.
> **Nguồn chân lý:** 01 UC01–UC11, UC22 (phần tài khoản); 02 BR-TK-01…20, BR-QT-01…16; 03 #1 Tài khoản; 04 §1; 05 §1 (accounts, staff_profiles, sessions, otp_tokens, audit_logs, system_configs, notification_templates).
> **Contract máy đọc:** [`./openapi/identity-v1.yaml`](./openapi/identity-v1.yaml) — sinh cùng lúc với file này nên luôn khớp.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít phát minh nhất · `TBD (Q#)` = cần quyết định (mục E). Method/path/shape là **PROPOSED** theo quy ước chung ở [`00-method.md`](./00-method.md); envelope, mã lỗi, phân trang, kiểu dữ liệu theo mục 3 của file đó.

**Đóng băng phạm vi (không có endpoint):**
- ST01 (khóa tạm do đăng nhập sai) và ST02 (dọn tài khoản `PENDING`) là tác vụ hệ thống, không có endpoint.
- Hồ sơ khách (họ tên, SĐT, ảnh) và sổ địa chỉ của khách thuộc module customer: `/me/customer-profile`, `/me/addresses` (A5).
- Gửi email/thông báo là việc của module care qua `NotificationApi`; ở đây không có endpoint gửi thư.

---

## A. Danh sách endpoint (37)

| # | Endpoint | Thao tác | Use case | Rule |
|---|---|---|---|---|
| 1 | `POST /auth/register` | Đăng ký tài khoản | UC01 | BR-TK-01, 02, 03, 04, 05, 07, BR-KH-01 |
| 2 | `POST /auth/register/verify` | Xác thực OTP đăng ký | UC02 | BR-TK-05, 06, 19 |
| 3 | `POST /auth/register/resend-otp` | Gửi lại OTP đăng ký | UC02 | BR-TK-04, 05, 07 |
| 4 | `POST /auth/login` | Đăng nhập | UC03 | BR-TK-08, 09, 10, 11, 17, BR-QT-15 |
| 5 | `POST /auth/logout` | Đăng xuất | UC03 | BR-TK-11, BR-TN-06 |
| 6 | `POST /auth/password/forgot` | Quên mật khẩu — gửi OTP | UC04 | BR-TK-04, 07, 10, 12 |
| 7 | `POST /auth/password/reset` | Đặt lại mật khẩu bằng OTP | UC04 | BR-TK-03, 05, 06, 13 |
| 8 | `GET /me` | Thông tin người đang đăng nhập | UC06 | BR-TK-17, 19 |
| 9 | `POST /me/password` | Đổi mật khẩu | UC05 | BR-TK-03, 09, 14, 17 |
| 10 | `PATCH /me/staff-profile` | Sửa hồ sơ nhân viên của tôi | UC06 | BR-TK-15, 20 |
| 11 | `GET /me/link-candidates` | Danh sách hồ sơ tại quầy có thể liên kết | UC07 | BR-TK-19, BR-KH-10 |
| 12 | `POST /me/link/otp` | Gửi OTP tới email hồ sơ tại quầy | UC07 | BR-TK-04, 07, 19 |
| 13 | `POST /me/link/confirm` | Xác nhận liên kết | UC07 | BR-TK-05, 06, 19 |
| 14 | `POST /me/link/decline` | Chọn "Không phải tôi" | UC07 | BR-TK-19 |
| 15 | `GET /staff` | Danh sách nhân viên | UC08 | BR-QT-01, 07 |
| 16 | `POST /staff` | Tạo tài khoản nhân viên | UC08 | BR-QT-01, 02, 03, BR-TK-01, 17 |
| 17 | `GET /staff/{id}` | Chi tiết nhân viên | UC08 | BR-QT-01, 07 |
| 18 | `POST /staff/{id}/change-role` | Đổi chức vụ | UC08 | BR-QT-04, 05 |
| 19 | `POST /staff/{id}/transfer` | Điều chuyển chi nhánh | UC08 | BR-QT-03, 04, 06, 08 |
| 20 | `POST /staff/{id}/disable` | Vô hiệu hóa nhân viên | UC08 | BR-QT-04, 07, 08, 09, 12, BR-TK-11 |
| 21 | `POST /staff/{id}/reactivate` | Kích hoạt lại nhân viên | UC08 | BR-QT-02, 03, 10, 12 |
| 22 | `POST /staff/{id}/resend-initial-password` | Gửi lại mật khẩu ban đầu | UC08 | BR-QT-02 |
| 23 | `POST /staff/{id}/email-change` | Sửa email nhân viên — gửi OTP tới email mới | UC08 | BR-TK-04, 07, 16, BR-QT-01 |
| 24 | `POST /staff/{id}/email-change/confirm` | Sửa email nhân viên — xác nhận OTP | UC08 | BR-TK-01, 05, 06, 16 |
| 25 | `POST /customers/{customerId}/account-email-change` | Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP | UC22 | BR-TK-01, 04, 07, 16, BR-KH-10 |
| 26 | `POST /customers/{customerId}/account-email-change/confirm` | Sửa email tài khoản khách — xác nhận OTP | UC22 | BR-TK-05, 06, 16 |
| 27 | `POST /customers/{customerId}/link-account` | Liên kết hồ sơ tại quầy với tài khoản | UC22 | BR-TK-16, 19 |
| 28 | `GET /accounts` | Tìm tài khoản (khách và nhân viên) | UC09 | BR-QT-11 |
| 29 | `POST /accounts/{id}/lock` | Khóa tài khoản | UC09 | BR-QT-04, 11, 12, BR-TN-08, BR-TK-11 |
| 30 | `POST /accounts/{id}/unlock` | Mở khóa tài khoản | UC09 | BR-QT-11, 12 |
| 31 | `GET /system-configs` | Danh sách tham số [CFG] | UC10 | BR-QT-13 |
| 32 | `PUT /system-configs/{key}` | Sửa tham số | UC10 | BR-QT-13, 15 |
| 33 | `GET /notification-templates` | Danh sách mẫu thông báo | UC10 | BR-QT-14 |
| 34 | `PUT /notification-templates/{code}` | Sửa nội dung mẫu | UC10 | BR-QT-14, 15 |
| 35 | `POST /notification-templates/{code}/restore-default` | Khôi phục mẫu mặc định | UC10 | BR-QT-14, 15 |
| 36 | `GET /audit-logs` | Xem nhật ký audit | UC11 | BR-QT-15, 16 |
| 37 | `GET /public/vets` | Đội ngũ bác sĩ | UC15 | BR-TK-20, BR-CK-01 |

---

## B. Ma trận thiết kế

| Thao tác | Actor | Quyền / phạm vi | Chuyển trạng thái | Idempotent | Ghi chú |
|---|---|---|---|---|---|
| Đăng ký tài khoản | A01 | Public | — → PENDING (Tài khoản#1); tạo hồ sơ online | — | Gửi OTP_REGISTER qua outbox (A8). |
| Xác thực OTP đăng ký | A01 | Public | PENDING → ACTIVE (Tài khoản#2) | — | Không tự đăng nhập (A2). |
| Gửi lại OTP đăng ký | A01 | Public | — | — | Mã cũ cùng mục đích mất hiệu lực (BR-TK-05). |
| Đăng nhập | A02–A08 | Public | — | — | Ghi audit thành công và thất bại (ADR-0019). `account.mustChangePassword = true` vẫn đăng nhập được (BR-TK-17). Request sai hình thức (400 `VALIDATION_FAILED` / `MALFORMED_REQUEST`) không tính là một lần đăng nhập sai. |
| Đăng xuất | A02–A08 | Người đang đăng nhập | — | — | Hủy phiên hiện tại; nhân viên chuyển offline ngay (ADR-0021). Body không được đọc. Gọi lại sau khi thành công → 401. Request cùng token đang chạy vẫn hoàn tất (§3.1 của `00-method`). Không ghi audit (ADR-0019). |
| Quên mật khẩu — gửi OTP | A02–A08 | Public | — | — | Luôn trả 202 cùng nội dung (BR-TK-10), kể cả email không tồn tại, tài khoản không đủ điều kiện BR-TK-12 (`PENDING`, bị khóa, `DISABLED` — không gửi mã), đã chạm quota BR-TK-07 (không gửi mã, `resendAvailableAt` vẫn = now + 60 giây [CFG]). Tài khoản đang khóa tạm BR-TK-09 vẫn nhận mã. Gửi lại = gọi lại endpoint này (A7). ADR-0023. |
| Đặt lại mật khẩu bằng OTP | A02–A08 | Public | — | — | Thứ tự kiểm: hình thức → chính sách mật khẩu BR-TK-03 → mã BR-TK-05/06 → trùng mật khẩu hiện tại BR-TK-03. Thành công: hủy mọi phiên, gỡ khóa tạm, gỡ bắt đổi mật khẩu lần đầu BR-TK-17, chuyển offline (BR-TN-06), gửi email PASSWORD_CHANGED (BR-TK-13); không audit. ADR-0023. |
| Thông tin người đang đăng nhập | A02–A08 | Người đang đăng nhập | — | — | — |
| Đổi mật khẩu | A02–A08 | Người đang đăng nhập | — | — | Đăng xuất mọi phiên khác, phiên đang dùng giữ nguyên; gỡ `mustChangePassword`; xóa bộ đếm đăng nhập sai. Không gửi email. Request sai hình thức (400 `VALIDATION_FAILED` / `MALFORMED_REQUEST`) không tính là một lần nhập sai (ADR-0022). |
| Sửa hồ sơ nhân viên của tôi | A03–A08 | Nhân viên đang đăng nhập; specialty, bio chỉ VET | — | — | null = giữ nguyên; chuỗi rỗng = xóa (`avatarUrl`, `specialty`, `bio`). Thứ tự kiểm: hình thức → BR-TK-15 → BR-TK-11 → 403 → BR-TK-20 (chỉ khi gửi `bio`). Field khác (`role`, `branchId`…) bị bỏ qua. Không audit (ADR-0026). |
| Danh sách hồ sơ tại quầy có thể liên kết | A02 | Khách đang đăng nhập | — | — | Chỉ trả hồ sơ COUNTER chưa liên kết, họ tên đã che; không có thì mảng rỗng (ADR-0027). |
| Gửi OTP tới email hồ sơ tại quầy | A02 | Khách đang đăng nhập | — | — | Thứ tự kiểm: BR-TK-11 → 404 → BR-TK-19 → BR-TK-07. Mã gửi tới email ghi trong hồ sơ tại quầy (BR-TK-04); gọi lại = gửi lại (BR-TK-07, mã mới hủy mã liên kết cũ của tài khoản). `maskedEmail` là email đó đã che (ADR-0027). |
| Xác nhận liên kết | A02 | Khách đang đăng nhập | — | — | Gắn tài khoản vào hồ sơ tại quầy, xóa hồ sơ online cùng sổ địa chỉ của nó, email hồ sơ theo email tài khoản, SĐT giữ nguyên, ghi audit `CUSTOMER_PROFILE_LINKED` (ADR-0027). Sau lỗi mạng, đọc lại `GET /me` để biết đã liên kết chưa. |
| Chọn "Không phải tôi" | A02 | Khách đang đăng nhập | — | — | Không audit (ADR-0027). |
| Danh sách nhân viên | A03, A04, A05 | ADMIN: SUPER_MANAGER · SUPER_MANAGER: A05–A08 · BRANCH_MANAGER: A06–A08 chi nhánh mình | — | — | Lọc `role` ngoài phạm vi xem → trang rỗng; ADMIN gửi `branchId` → rỗng (SUPER_MANAGER không thuộc chi nhánh). Không gửi `status` = ACTIVE và DISABLED. `q` không phân biệt hoa thường. ADR-0030. |
| Tạo tài khoản nhân viên | A03, A04, A05 | Theo BR-QT-01 | — → ACTIVE (Tài khoản#4) | — | Sinh mật khẩu ngẫu nhiên gửi STAFF_TEMP_PASSWORD; người tạo không thấy mật khẩu; mustChangePassword = true. BRANCH_MANAGER không gửi `branchId` = chi nhánh của mình. Sau lỗi mạng, đọc lại `GET /staff?q=<email>` để biết đã tạo chưa (gửi lại nhận `BR-TK-01`). ADR-0030. |
| Chi tiết nhân viên | A03, A04, A05 | Trong phạm vi như listStaff | — | — | — |
| Đổi chức vụ | A04 | Chỉ SUPER_MANAGER | — | — | Trùng chức vụ hiện tại → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. VET đổi sang chức vụ khác thì specialty, bio bị xóa. Không hủy phiên: request kế tiếp của nhân viên dùng chức vụ mới. ADR-0031. |
| Điều chuyển chi nhánh | A04 | Chỉ SUPER_MANAGER | — | — | Đã ở chi nhánh đích → 200 không đổi gì. Được cả khi nhân viên DISABLED / bị khóa. Không hủy phiên: request kế tiếp của nhân viên dùng chi nhánh mới. ADR-0031, ADR-0032. |
| Vô hiệu hóa nhân viên | A03, A04, A05 | Theo BR-QT-07; không tự vô hiệu hóa mình | ACTIVE → DISABLED (Tài khoản#5) | — | Không body. Hủy mọi phiên, nhân viên chuyển offline ngay (BR-QT-09, BR-TK-11, BR-TN-06); không gửi email; dữ liệu lịch sử giữ nguyên. SUPER_MANAGER không kiểm BR-QT-08. Tạm thời A05–A08 trả 500 tới khi visit / sales có bản thật (D016, 21/10). Bấm lại sau lỗi mạng nhận 409 → đọc lại `GET /staff/{id}`. ADR-0033. |
| Kích hoạt lại nhân viên | A03, A04, A05 | Cùng phạm vi với vô hiệu hóa | DISABLED → ACTIVE (Tài khoản#6) | — | Body không bắt buộc: thiếu / `{}` / `branchId` null = giữ chi nhánh hiện tại. Sinh mật khẩu tạm mới gửi STAFF_TEMP_PASSWORD (response không có mật khẩu), mustChangePassword = true, xóa bộ đếm đăng nhập sai và khóa tạm, hủy mọi phiên. Bấm lại sau lỗi mạng nhận 409 (không gửi thêm mật khẩu) → đọc lại `GET /staff/{id}`. ADR-0033, ADR-0032. |
| Gửi lại mật khẩu ban đầu | A03, A04, A05 | Người có quyền tạo tài khoản đó (BR-QT-01) | — | — | Sinh mật khẩu mới, mã cũ hết hiệu lực. Chỉ khi ACTIVE và chưa đổi mật khẩu lần đầu (đang bị khóa vẫn được). Hủy mọi phiên, xóa bộ đếm đăng nhập sai và khóa tạm, nhân viên chuyển offline; ghi audit. ADR-0030. |
| Sửa email nhân viên — gửi OTP tới email mới | A03, A04, A05 | Cấp trên theo BR-QT-01 | — | — | Email mới được chuẩn hóa chữ thường. Mã gửi tới email mới (BR-TK-04), `maskedEmail` = null. Được với mọi trạng thái (DISABLED, đang bị khóa). Mỗi nhân viên tối đa một mã đổi email còn hiệu lực: gửi mã mới (kể cả tới email khác) hủy mã cũ của nhân viên đó; tài khoản khác xin mã tới cùng email không hủy được mã này. Không ghi audit ở bước này. Gửi lại = gọi lại endpoint. ADR-0034. |
| Sửa email nhân viên — xác nhận OTP | A03, A04, A05 | Cấp trên theo BR-QT-01 | — | — | Email mới lấy từ mã đang mở của nhân viên. Đổi email, gửi EMAIL_CHANGED_NOTICE tới email cũ và mới, ghi audit `STAFF_EMAIL_CHANGED` (before/after email, `verificationMethod = EMAIL_CODE`). Trạng thái, khóa, mật khẩu không đổi; không hủy phiên. Bấm lại sau lỗi mạng nhận `BR-TK-05` → đọc lại `GET /staff/{id}`. Nhân viên chưa đổi mật khẩu lần đầu (`mustChangePassword`) thì gọi tiếp `POST /staff/{id}/resend-initial-password` để mật khẩu tạm đi tới email mới. ADR-0034. |
| Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP | A06 | Lễ tân | — | — | — |
| Sửa email tài khoản khách — xác nhận OTP | A06 | Lễ tân | — | — | Ghi audit kèm phương thức xác minh; gửi EMAIL_CHANGED_NOTICE tới email cũ và mới. |
| Liên kết hồ sơ tại quầy với tài khoản | A06 | Lễ tân | — | — | Không cần OTP: lễ tân đã đối chiếu CCCD. Ghi audit. |
| Tìm tài khoản (khách và nhân viên) | A03 | Chỉ ADMIN | — | — | — |
| Khóa tài khoản | A03 | Chỉ ADMIN; không khóa chính mình | is_locked false → true (Tài khoản#7) | — | Mọi role, mọi trạng thái (kể cả PENDING, DISABLED) trừ chính mình; status giữ nguyên (BR-QT-12). Hủy mọi phiên; pendingWork sắp theo thứ tự enum rồi entityId (Visit IN_PROGRESS → needsReassign); phát AccountLockedEvent; thông báo trong app theo BR-QT-11 (nhân viên A06–A08: BRANCH_MANAGER chi nhánh, không còn thì SUPER_MANAGER; BRANCH_MANAGER: SUPER_MANAGER, quản lý cuối của chi nhánh ACTIVE → LAST_BRANCH_MANAGER_LOCKED_APP; khách: lễ tân các chi nhánh có việc dở — 06 Q2); ghi audit kèm lý do (docs/adr/0035). |
| Mở khóa tài khoản | A03 | Chỉ ADMIN | is_locked true → false (Tài khoản#8) | — | status giữ nguyên giá trị trước khi khóa (BR-QT-12); giữ lý do khóa gần nhất; không mở phiên, không thông báo; ghi audit kèm lý do (docs/adr/0035). |
| Danh sách tham số [CFG] | A03 | Chỉ ADMIN | — | — | Mọi tham số (52), sắp theo key; minValue / maxValue là khoảng tĩnh, khoảng thu hẹp theo ràng buộc chéo chỉ có trong message lỗi của #32 (docs/adr/0036). |
| Sửa tham số | A03 | Chỉ ADMIN | — | — | Thứ tự kiểm: hình thức → quyền → key lạ 404 → kiểu / min–max / ràng buộc chéo (400 BR-QT-13, message ghi khoảng cho phép thực tế; sai kiểu cũng BR-QT-13) → trùng giá trị hiện tại (sau chuẩn hóa, ví dụ 05 = 5) → 200 không ghi, không audit. Ràng buộc chéo: visit.late_priority_minutes < appointment.no_show_after_minutes, boarding.min_nights ≤ boarding.max_nights. value được chuẩn hóa (INT bỏ số 0 đầu, TIME dạng HH:mm, DECIMAL không dạng mũ). Có hiệu lực cho mọi giao dịch tạo sau khi response trả về; ghi audit SYSTEM_CONFIG_UPDATED (docs/adr/0036). |
| Danh sách mẫu thông báo | A03 | Chỉ ADMIN | — | — | Mọi mẫu, sắp theo code; subject / body là bản đang dùng; isDefault = cả hai trùng từng ký tự với bản mặc định (docs/adr/0037). |
| Sửa nội dung mẫu | A03 | Chỉ ADMIN | — | — | Thứ tự kiểm: hình thức → quyền → code lạ 404 → biến (BR-QT-14). Ngữ pháp chặt: dấu ngoặc nhọn chỉ dùng để viết {ten} với ten thuộc allowedVars (phân biệt hoa thường); biến bắt buộc tính trên subject ∪ body. Lưu nguyên văn (không cắt khoảng trắng, không đổi xuống dòng). Trùng nội dung hiện tại → 200, không ghi nhưng vẫn audit (before = after). Có hiệu lực từ lần gửi đọc mẫu sau khi response trả về, kể cả thông báo đã xếp hàng. Audit NOTIFICATION_TEMPLATE_UPDATED. Không có endpoint thêm / xóa mẫu (docs/adr/0037). |
| Khôi phục mẫu mặc định | A03 | Chỉ ADMIN | — | — | Chép bản mặc định sang bản đang dùng. Đã là mặc định → 200, không ghi nhưng vẫn audit (before = after). Audit NOTIFICATION_TEMPLATE_RESTORED (docs/adr/0037). |
| Xem nhật ký audit | A03 | Chỉ ADMIN | — | — | Chỉ đọc; không có endpoint sửa / xóa (BR-QT-16): method khác → 405. Xem không ghi audit. Mọi dòng của mọi người (kể cả hệ thống, actor null). Sắp `createdAt` giảm dần rồi `id` giảm dần; khoảng [from, to] tính cả hai mốc; giá trị lọc không tồn tại → trang rỗng; action / entityType rỗng = không lọc; size ngoài 1–100 bị kẹp, page âm = 0. `beforeData` / `afterData` là JSON object đúng như lúc ghi (thứ tự khóa không cố định), null khi không có. `totalElements` khớp `content` trong cùng một response (docs/adr/0038). |
| Đội ngũ bác sĩ | A01, A02 | Public | — | — | Chỉ VET ACTIVE, không khóa (khóa tạm vẫn hiện), thuộc chi nhánh ACTIVE; `branchId` không phải chi nhánh ACTIVE → mảng rỗng. Sắp theo họ tên (ADR-0026). |

---

## C. Chi tiết endpoint

### Auth

- **`POST /auth/register`** — Đăng ký tài khoản. Public. Request `RegisterRequest`. Response `201` `RegistrationResponse`. Lỗi: `400` `BR-TK-01` email đã được sử dụng · `BR-TK-02` chưa xác nhận ≥ 18 tuổi / điều khoản · `BR-TK-03` mật khẩu yếu hoặc quá 72 byte · `BR-TK-07` email đã nhận quá 5 mã OTP/giờ [CFG] · `409` `CONCURRENCY_CONFLICT` hai request đăng ký cùng email cùng lúc.
- **`POST /auth/register/verify`** — Xác thực OTP đăng ký. Public. Request `VerifyOtpRequest`. Response `200` `VerificationResponse`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `404` Không có tài khoản PENDING với email này.
- **`POST /auth/register/resend-otp`** — Gửi lại OTP đăng ký. Public. Request `EmailRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-07` chưa đủ 60 giây hoặc vượt 5 mã/giờ · `404` Không có tài khoản PENDING với email này.
- **`POST /auth/login`** — Đăng nhập. Public. Request `LoginRequest`. Response `200` `LoginResponse`. Lỗi: `400` Chỉ khi mật khẩu đúng (ADR-0019): `BR-TK-08` tài khoản chưa xác thực → FE chuyển màn OTP · `BR-TK-09` đang khóa tạm, message có giờ thử lại (`HH:mm dd/MM/yyyy`) · `BR-TK-11` tài khoản bị khóa / vô hiệu hóa · `401` Sai email hoặc mật khẩu — thông điệp chung "Email hoặc mật khẩu không đúng" (BR-TK-10); sai lần thứ 5 trong 15 phút [CFG] kích hoạt ST01 (khóa tạm + email cảnh báo); sai trong lúc khóa không đếm.
- **`POST /auth/logout`** — Đăng xuất. Response `204` rỗng. Lỗi: `401`.
- **`POST /auth/password/forgot`** — Quên mật khẩu — gửi OTP. Public. Request `EmailRequest`. Response `202` `OtpSentResponse`. Lỗi: `400` `VALIDATION_FAILED` email trống / sai định dạng / quá 255 ký tự.
- **`POST /auth/password/reset`** — Đặt lại mật khẩu bằng OTP. Public. Request `ResetPasswordRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy — cũng là lỗi cho email không có tài khoản, tài khoản không đủ điều kiện BR-TK-12 hoặc chưa có mã (BR-TK-10) · `BR-TK-03` mật khẩu mới không hợp lệ (kiểm trước mã, không tiêu lượt nhập) hoặc trùng mật khẩu hiện tại (chỉ kiểm khi mã đúng; mã vẫn dùng lại được).
### Me

- **`GET /me`** — Thông tin người đang đăng nhập. Response `200` `MeResponse`. Lỗi: `401`.
- **`POST /me/password`** — Đổi mật khẩu. Request `ChangePasswordRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-14` mật khẩu hiện tại sai (tính vào bộ đếm BR-TK-09; lần chạm ngưỡng khóa tạm đăng nhập, message kèm giờ mở khóa) · `BR-TK-03` mật khẩu mới không hợp lệ hoặc trùng mật khẩu cũ (chỉ kiểm khi mật khẩu hiện tại đúng) · `BR-TK-09` đang khóa tạm, message có giờ thử lại · `BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa · `401`.
- **`PATCH /me/staff-profile`** — Sửa hồ sơ nhân viên của tôi. Request `UpdateStaffProfileRequest`. Response `200` `StaffProfile`. Lỗi: `400` `BR-TK-15` body có `email` (email không tự sửa được) · `BR-TK-11` tài khoản vừa bị khóa / vô hiệu hóa · `BR-TK-20` mô tả ngắn vượt 500 ký tự [CFG] · `BR-TK-17` chưa đổi mật khẩu lần đầu · `VALIDATION_FAILED` họ tên / SĐT rỗng, SĐT khác 10 số, `avatarUrl` không phải `https://` · `401` · `403` `ACCESS_DENIED` tài khoản khách · `ACCESS_DENIED_SCOPE_MISMATCH` không phải VET mà gửi `specialty` / `bio`.
### Profile link

- **`GET /me/link-candidates`** — Danh sách hồ sơ tại quầy có thể liên kết. Query: `phone?`. Response `200` mảng `LinkCandidate`. Lỗi: `400` `BR-TK-19` hồ sơ online đã phát sinh dữ liệu, hoặc tài khoản đã liên kết hồ sơ tại quầy · `VALIDATION_FAILED` `phone` sai định dạng · `401` · `403`.
- **`POST /me/link/otp`** — Gửi OTP tới email hồ sơ tại quầy. Request `LinkOtpRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-19` hồ sơ không có email (ra quầy), hồ sơ online đã có dữ liệu hoặc tài khoản đã liên kết · `BR-TK-11` tài khoản vừa bị khóa · `BR-TK-07` gửi quá nhanh / quá 5 mã/giờ · `401` · `403` · `404` Hồ sơ không tồn tại hoặc không phải ứng viên của SĐT (một thông báo chung).
- **`POST /me/link/confirm`** — Xác nhận liên kết. Request `LinkConfirmRequest`. Response `200` `LinkResult`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy (sai mã vẫn được tính) · `BR-TK-19` hồ sơ online đã phát sinh dữ liệu / tài khoản đã liên kết / hồ sơ tại quầy vừa được liên kết hoặc đổi email — mã vẫn dùng lại được · `BR-TK-11` tài khoản vừa bị khóa · `401` · `403` · `404` Hồ sơ không tồn tại.
- **`POST /me/link/decline`** — Chọn "Không phải tôi". Response `204` rỗng. Lỗi: `400` `BR-TK-11` tài khoản vừa bị khóa · `401` · `403` · `409` `INVALID_STATE_TRANSITION` hồ sơ không còn cờ chờ quyết định.
### Staff

- **`GET /staff`** — Danh sách nhân viên. Query: `branchId?`, `role?`, `status?`, `q?`, `page?`, `size?`. Response `200` trang `StaffResponse`. Lỗi: `400` tham số sai kiểu · `branchId` ≤ 0 · `401` · `403` BRANCH_MANAGER gửi `branchId` của chi nhánh khác (không ghi audit).
- **`POST /staff`** — Tạo tài khoản nhân viên. Request `CreateStaffRequest`. Response `201` `StaffResponse`. Lỗi: `400` `BR-TK-01` email đã dùng · `BR-QT-03` thiếu chi nhánh, chi nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId` · `401` · `403` `BR-QT-01` chức vụ / chi nhánh ngoài quyền (ghi audit) · `409` hai request cùng email chạy song song.
- **`GET /staff/{id}`** — Chi tiết nhân viên. Response `200` `StaffResponse`. Lỗi: `400` · `401` · `403` ngoài phạm vi xem (không ghi audit) · `404` không có, hoặc là tài khoản khách (cùng message).
- **`POST /staff/{id}/change-role`** — Đổi chức vụ. Request `ChangeRoleRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-04` là BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE · `401` · `403` người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit) · `404` không có, hoặc là tài khoản khách · `409` chi nhánh của nhân viên vừa bị đổi bởi thao tác khác.
- **`POST /staff/{id}/transfer`** — Điều chuyển chi nhánh. Request `TransferStaffRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN (message liệt kê) · `BR-QT-04` BRANCH_MANAGER cuối cùng · `BR-QT-03` chi nhánh không hợp lệ · `branchId` số thực → `MALFORMED_REQUEST` · `401` · `403` người bị tác động ngoài A05–A08 hoặc chính mình (ghi audit) · `404` không có, hoặc là tài khoản khách · `409` chi nhánh của nhân viên vừa bị đổi bởi thao tác khác.
- **`POST /staff/{id}/disable`** — Vô hiệu hóa nhân viên. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-12` đang bị khóa, cần mở khóa trước · `BR-QT-04` BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE · `BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN (message liệt kê) · `id` sai kiểu · `401` · `403` ngoài phạm vi BR-QT-07 hoặc chính mình (ghi audit, message chung) · `404` không có, hoặc là tài khoản khách · `409` đã DISABLED (`INVALID_STATE_TRANSITION`) · chi nhánh của nhân viên vừa bị đổi bởi thao tác khác (`CONCURRENCY_CONFLICT`).
- **`POST /staff/{id}/reactivate`** — Kích hoạt lại nhân viên. Request `ReactivateStaffRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-10` đang bị khóa, cần mở khóa trước · `BR-QT-03` chi nhánh không tồn tại, hoặc SUPER_MANAGER kèm `branchId` · `branchId` ≤ 0 (`VALIDATION_FAILED`) / số thực (`MALFORMED_REQUEST`) · `401` · `403` ngoài phạm vi BR-QT-10 (như vô hiệu hóa), chính mình, hoặc BRANCH_MANAGER gửi chi nhánh khác chi nhánh mình (ghi audit, message chung) · `404` không có, hoặc là tài khoản khách · `409` đang ACTIVE (`INVALID_STATE_TRANSITION`).
- **`POST /staff/{id}/resend-initial-password`** — Gửi lại mật khẩu ban đầu. Response `204` rỗng. Lỗi: `401` · `403` `BR-QT-01` ngoài phân cấp hoặc chính mình (ghi audit) · `404` không có, hoặc là tài khoản khách · `409` Nhân viên đã đổi mật khẩu lần đầu, hoặc tài khoản đang DISABLED.
- **`POST /staff/{id}/email-change`** — Sửa email nhân viên — gửi OTP tới email mới. Request `EmailChangeRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-01` email mới đã dùng (kể cả tài khoản PENDING) hoặc trùng email hiện tại · `BR-TK-07` gửi lại trong 60 giây / quá 5 mã/giờ [CFG] tới email đó · `VALIDATION_FAILED` `newEmail` trống / sai định dạng / quá 255 ký tự · `401` · `403` ngoài phân cấp BR-QT-01 hoặc chính mình (ghi audit, message chung) · `404` không có, hoặc là tài khoản khách (cùng message).
- **`POST /staff/{id}/email-change/confirm`** — Sửa email nhân viên — xác nhận OTP. Request `OtpCodeRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy (sai mã vẫn được tính) · không còn mã đang mở → `BR-TK-05` · `BR-TK-01` email mới vừa được tài khoản khác dùng (mã vẫn dùng lại được) · `VALIDATION_FAILED` `code` trống / không phải 1–8 chữ số · `401` · `403` ngoài phân cấp BR-QT-01 hoặc chính mình — kiểm trước khi tra mã và lại dưới khóa (ghi audit, message chung) · `404` không có, hoặc là tài khoản khách (cùng message) · `409` `CONCURRENCY_CONFLICT` hai lần xác nhận khác nhân viên cùng một email mới chạy song song.
### Customer account

- **`POST /customers/{customerId}/account-email-change`** — Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP. Request `CustomerEmailChangeRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` Hồ sơ chưa gắn tài khoản · `BR-TK-01` email mới đã dùng · `BR-TK-07` gửi quá nhanh · `401` · `403` · `404`.
- **`POST /customers/{customerId}/account-email-change/confirm`** — Sửa email tài khoản khách — xác nhận OTP. Request `OtpCodeRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `401` · `403` · `404`.
- **`POST /customers/{customerId}/link-account`** — Liên kết hồ sơ tại quầy với tài khoản. Request `CounterLinkRequest`. Response `200` `LinkResult`. Lỗi: `400` `BR-TK-19` hồ sơ không phải COUNTER / đã liên kết / hồ sơ online đã phát sinh dữ liệu · `401` · `403` · `404`.
### Accounts

- **`GET /accounts`** — Tìm tài khoản (khách và nhân viên). Query: `q?`, `role?`, `isLocked?`, `page?`, `size?`. Response `200` trang `AccountSummary`. Lỗi: `401` · `403`.
- **`POST /accounts/{id}/lock`** — Khóa tài khoản. Request `ReasonRequest`. Response `200` `LockResult`. Lỗi: `400` Thiếu lý do (`VALIDATION_FAILED`) · `BR-QT-11` khóa chính mình · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` đã bị khóa · `CONCURRENCY_CONFLICT` chức vụ / chi nhánh của người bị khóa vừa đổi.
- **`POST /accounts/{id}/unlock`** — Mở khóa tài khoản. Request `ReasonRequest`. Response `200` `AccountSummary`. Lỗi: `400` Thiếu lý do (`VALIDATION_FAILED`) · `401` · `403` · `404` · `409` `INVALID_STATE_TRANSITION` không bị khóa.
### Configuration

- **`GET /system-configs`** — Danh sách tham số [CFG]. Response `200` mảng `SystemConfig`. Lỗi: `401` · `403`.
- **`PUT /system-configs/{key}`** — Sửa tham số. Request `UpdateConfigRequest`. Response `200` `SystemConfig`. Lỗi: `400` `BR-QT-13` ngoài khoảng hợp lệ, message ghi khoảng cho phép · `401` · `403` · `404`.
- **`GET /notification-templates`** — Danh sách mẫu thông báo. Response `200` mảng `NotificationTemplate`. Lỗi: `401` · `403`.
- **`PUT /notification-templates/{code}`** — Sửa nội dung mẫu. Request `UpdateTemplateRequest`. Response `200` `NotificationTemplate`. Lỗi: `400` `VALIDATION_FAILED` thiếu / rỗng subject hoặc body, subject quá 255 · `BR-QT-14` dùng biến không tồn tại, dấu { } lẻ hoặc thiếu biến bắt buộc (message nêu đủ mọi biến lỗi) · `401` · `403` · `404`.
- **`POST /notification-templates/{code}/restore-default`** — Khôi phục mẫu mặc định. Response `200` `NotificationTemplate`. Lỗi: `401` · `403` · `404`.
### Audit

- **`GET /audit-logs`** — Xem nhật ký audit. Query: `actorAccountId?`, `action?`, `entityType?`, `entityId?`, `from?`, `to?`, `page?`, `size?`. Response `200` trang `AuditLogEntry`. Lỗi: `400` `MALFORMED_REQUEST` tham số sai kiểu (from / to thiếu offset, id không phải số nguyên) · `VALIDATION_FAILED` action / entityType quá 50 ký tự, `to` trước `from`, `entityId` không kèm `entityType`, lọc `action` không kèm người / đối tượng mà thiếu from / to hoặc cách nhau quá 31 ngày (message nêu đủ lỗi, theo tên tham số) · `401` · `403` Không phải ADMIN (không ghi audit).
### Public

- **`GET /public/vets`** — Đội ngũ bác sĩ. Public. Query: `branchId?`. Response `200` mảng `PublicVet`. Lỗi: `400`.

### Schema

Trường có `?` là không bắt buộc / có thể null. Kiểu `email`, `date`, `datetime`… theo mục 3 của `00-method.md`.

- **`Role`** — enum: `CUSTOMER`, `ADMIN`, `SUPER_MANAGER`, `BRANCH_MANAGER`, `RECEPTIONIST`, `VET`, `CARETAKER`. Role cố định (04 §1)
- **`AccountStatus`** — enum: `PENDING`, `ACTIVE`, `DISABLED`. 03 #1; khóa là cờ isLocked riêng
- **`RegisterRequest`** — {`email`: email, `password`: string, `fullName`: string, `phone?`: string, `isAdult`: boolean, `termsAccepted`: boolean}
- **`RegistrationResponse`** — {`accountId`: int64, `email`: email, `status`: PENDING, `otpResendAvailableAt`: date-time}
- **`VerifyOtpRequest`** — {`email`: email, `code`: string}
- **`VerificationResponse`** — {`accountId`: int64, `status`: ACTIVE, `linkDecisionPending`: boolean}
- **`EmailRequest`** — {`email`: email}
- **`OtpSentResponse`** — {`resendAvailableAt`: date-time, `maskedEmail?`: string}
- **`LoginRequest`** — {`email`: email, `password`: string}
- **`AccountSummary`** — {`id`: int64, `email`: email, `role`: Role, `status`: AccountStatus, `isLocked`: boolean, `mustChangePassword`: boolean}
- **`LoginResponse`** — {`accessToken`: string, `expiresAt`: date-time, `account`: AccountSummary, `linkDecisionPending`: boolean}
- **`ResetPasswordRequest`** — {`email`: email, `code`: string, `newPassword`: string}
- **`ChangePasswordRequest`** — {`currentPassword`: string, `newPassword`: string}
- **`StaffProfile`** — {`accountId`: int64, `fullName`: string, `avatarUrl?`: string, `phone`: string, `branchId?`: int64, `specialty?`: string, `bio?`: string}
- **`MeResponse`** — {`account`: AccountSummary, `staffProfile?`: StaffProfile, `customerId?`: int64, `linkDecisionPending`: boolean}
- **`UpdateStaffProfileRequest`** — {`fullName?`: string, `avatarUrl?`: string, `phone?`: string, `specialty?`: string, `bio?`: string}
- **`LinkCandidate`** — {`customerId`: int64, `maskedFullName`: string, `hasEmail`: boolean}
- **`LinkOtpRequest`** — {`customerId`: int64, `phone?`: string}
- **`LinkConfirmRequest`** — {`customerId`: int64, `code`: string}
- **`LinkResult`** — {`customerId`: int64}
- **`StaffResponse`** — {`accountId`: int64, `email`: email, `phone`: string, `fullName`: string, `role`: Role, `status`: AccountStatus, `isLocked`: boolean, `branchId?`: int64, `specialty?`: string, `bio?`: string, `mustChangePassword`: boolean, `online`: boolean}
- **`CreateStaffRequest`** — {`email`: email, `phone`: string, `fullName`: string, `role`: SUPER_MANAGER|BRANCH_MANAGER|RECEPTIONIST|VET|CARETAKER, `branchId?`: int64}
- **`ChangeRoleRequest`** — {`role`: BRANCH_MANAGER|RECEPTIONIST|VET|CARETAKER}
- **`TransferStaffRequest`** — {`branchId`: int64}
- **`ReactivateStaffRequest`** — {`branchId?`: int64}
- **`EmailChangeRequest`** — {`newEmail`: email}
- **`OtpCodeRequest`** — {`code`: string}
- **`CustomerEmailChangeRequest`** — {`newEmail`: email, `verificationMethod`: ID_CARD_IN_PERSON}
- **`CounterLinkRequest`** — {`accountEmail`: email, `verificationMethod`: ID_CARD_IN_PERSON}
- **`ReasonRequest`** — {`reason`: string}
- **`PendingWorkItem`** — {`type`: VISIT_IN_PROGRESS|VISIT_WAITING|CASHIER_SHIFT_OPEN|APPOINTMENT_BOOKED|BOARDING_ACTIVE|ORDER_UNPAID, `entityId`: int64, `branchId`: int64, `needsReassign`: boolean}
- **`LockResult`** — {`account`: AccountSummary, `pendingWork`: [PendingWorkItem]}
- **`SystemConfig`** — {`key`: string, `value`: string, `valueType`: INT|DECIMAL|BOOL|TIME, `minValue?`: string, `maxValue?`: string, `unit?`: string, `description`: string}
- **`UpdateConfigRequest`** — {`value`: string}
- **`NotificationTemplate`** — {`code`: string, `channel`: EMAIL|IN_APP, `subject?`: string, `body`: string, `allowedVars`: [string], `requiredVars`: [string], `isDefault`: boolean}
- **`UpdateTemplateRequest`** — {`subject`: string, `body`: string}
- **`AuditLogEntry`** — {`id`: int64, `actorAccountId?`: int64, `actorEmail?`: string, `action`: string, `entityType?`: string, `entityId?`: int64, `beforeData?`: object, `afterData?`: object, `reason?`: string, `ipAddress?`: string, `createdAt`: date-time}
- **`PublicVet`** — {`accountId`: int64, `fullName`: string, `avatarUrl?`: string, `specialty?`: string, `bio?`: string, `branchId`: int64, `branchName`: string}

---

## D. Bảo mật & độ tin cậy

1. Mật khẩu, mã OTP và token phiên chỉ lưu dạng hash (`password_hash`, `otp_tokens.code_hash`, `sessions.token_hash`).
2. Mỗi request kiểm phiên còn hạn và chưa bị hủy; khóa, vô hiệu hóa, đổi/đặt lại mật khẩu hủy phiên có hiệu lực ngay ở request kế tiếp (BR-TK-11, 13, 14).
3. Đăng nhập và quên mật khẩu trả thông điệp chung, không lộ email có tồn tại (BR-TK-10). Riêng đăng ký được báo email trùng (BR-TK-01).
4. Giới hạn OTP kiểm ở server: 60 giây giữa hai lần gửi, 5 mã/giờ/email, 5 lần nhập sai/mã (BR-TK-06, 07).
5. Ghi audit: đăng nhập thành công và thất bại, mọi thao tác Staff / Accounts / Configuration, sửa email hộ, liên kết hồ sơ (BR-QT-15, BR-TK-16, 19).
6. Phân cấp: request ngoài phạm vi BR-QT-01 / BR-QT-07 trả 403 và ghi audit. BRANCH_MANAGER chỉ thấy và thao tác nhân viên chi nhánh mình.
7. Khi `mustChangePassword = true`, mọi API trừ `GET /me`, `POST /me/password`, `POST /auth/logout` trả 400 `BUSINESS_RULE_VIOLATION`, message kèm `BR-TK-17` (A4). Path public (§3.1 của `00-method`: `/auth/register…`, `/auth/login`, `/auth/password/…`, `/public/…`) không bị chặn vì luôn xử lý như chưa đăng nhập, token gửi kèm bị bỏ qua (ADR-0005).
8. Request của nhân viên cập nhật `last_seen_at` (tối đa một lần mỗi 60 giây, ADR-0003); đăng xuất đặt về offline ngay (BR-TN-06).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Access token gắn 1-1 với một phiên (`sessions`), không có refresh token; phiên hết hạn thì đăng nhập lại | Docs chỉ có bảng `sessions` có `expires_at`, `revoked_at`; không nhắc refresh |
| A2 | Xác thực OTP đăng ký không tự đăng nhập; client gọi `POST /auth/login` sau đó | Ít phát minh nhất; 03 Tài khoản#2 không có hệ quả tạo phiên |
| A3 | Đăng ký lại bằng email còn `PENDING` bị báo trùng (BR-TK-01); người dùng dùng gửi lại OTP | Email duy nhất trong tập tài khoản, `PENDING` vẫn là tài khoản |
| A4 | Chặn mọi chức năng khi chưa đổi mật khẩu lần đầu bằng 400, message kèm `BR-TK-17`; path public (đăng ký, đăng nhập, quên / đặt lại mật khẩu, `/public/…`) không bị chặn vì luôn xử lý như chưa đăng nhập, kể cả khi client gửi token (ADR-0005) | BR-TK-17 'chặn mọi chức năng khác' |
| A5 | Khách tự sửa họ tên, SĐT, ảnh ở module customer; identity chỉ sửa hồ sơ nhân viên | BR-TK-15: với khách, họ tên và SĐT là thông tin của hồ sơ khách |
| A6 | `verificationMethod` khi sửa email hộ / liên kết tại quầy chỉ có `ID_CARD_IN_PERSON` | BR-TK-16: audit chỉ ghi phương thức xác minh, docs chỉ nêu CCCD |
| A7 | Gửi lại OTP quên mật khẩu = gọi lại `POST /auth/password/forgot` (cùng quota BR-TK-07) | Tránh endpoint thứ hai lộ trạng thái tài khoản |
| A8 | BR-TK-04 'gửi thất bại thì báo lỗi': đăng ký trả 201 khi đã ghi `notification_outbox`; gửi thất bại do ST20 thử lại, người dùng gửi lại bằng `POST /auth/register/resend-otp` | 06-module-contracts §1: `NotificationApi.enqueue` chỉ ghi outbox; convention 07 §7.2 cấm gửi trực tiếp trong use case |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Thời hạn phiên đăng nhập bao lâu, có thêm vào [CFG] không, có cần refresh token cho app không? | Đã chốt (ADR-0003): hạn tuyệt đối `session.ttl_hours` [CFG], mặc định 12 giờ (1–72), không gia hạn, không refresh token; `exp` của token = `expiresAt` | Docs không quy định (A1) |
| Q2 | Lỗi `BR-TK-09` (khóa tạm, kèm giờ thử lại) cho biết email tồn tại, lệch với tinh thần BR-TK-10. Chấp nhận như BR-TK-09 yêu cầu? | Đã chốt (ADR-0019): BR-TK-08/09/11 chỉ trả khi mật khẩu đúng; mật khẩu sai luôn 401 chung, nên người không biết mật khẩu không phân biệt được email có tồn tại, đang khóa hay chưa xác thực | BR-TK-09 vs BR-TK-10 |

---

## F. OpenAPI 3.1

[`./openapi/identity-v1.yaml`](./openapi/identity-v1.yaml)
