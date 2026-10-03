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
| 1 | `POST /auth/register` | Đăng ký tài khoản | UC01 | BR-TK-01, 02, 03, 04, BR-KH-01 |
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
| 24 | `POST /staff/{id}/email-change/confirm` | Sửa email nhân viên — xác nhận OTP | UC08 | BR-TK-05, 06, 16 |
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
| Đăng ký tài khoản | A01 | Public | — → PENDING (Tài khoản#1); tạo hồ sơ online | — | Gửi OTP_REGISTER qua outbox. |
| Xác thực OTP đăng ký | A01 | Public | PENDING → ACTIVE (Tài khoản#2) | — | Không tự đăng nhập (A2). |
| Gửi lại OTP đăng ký | A01 | Public | — | — | Mã cũ cùng mục đích mất hiệu lực (BR-TK-05). |
| Đăng nhập | A02–A08 | Public | — | — | Ghi audit thành công và thất bại. |
| Đăng xuất | A02–A08 | Người đang đăng nhập | — | — | Hủy phiên hiện tại; nhân viên chuyển offline ngay. |
| Quên mật khẩu — gửi OTP | A02–A08 | Public | — | — | Luôn trả 202 cùng nội dung, kể cả email không tồn tại hoặc tài khoản không đủ điều kiện BR-TK-12 (không gửi mã). Gửi lại = gọi lại endpoint này (A7). |
| Đặt lại mật khẩu bằng OTP | A02–A08 | Public | — | — | Thành công: hủy mọi phiên, gỡ khóa tạm, gửi email PASSWORD_CHANGED (BR-TK-13). |
| Thông tin người đang đăng nhập | A02–A08 | Người đang đăng nhập | — | — | — |
| Đổi mật khẩu | A02–A08 | Người đang đăng nhập | — | — | Đăng xuất mọi phiên khác; gỡ `mustChangePassword`. |
| Sửa hồ sơ nhân viên của tôi | A03–A08 | Nhân viên đang đăng nhập; specialty, bio chỉ VET | — | — | — |
| Danh sách hồ sơ tại quầy có thể liên kết | A02 | Khách đang đăng nhập | — | — | Chỉ trả hồ sơ COUNTER chưa liên kết, họ tên đã che. |
| Gửi OTP tới email hồ sơ tại quầy | A02 | Khách đang đăng nhập | — | — | — |
| Xác nhận liên kết | A02 | Khách đang đăng nhập | — | — | Gắn tài khoản vào hồ sơ tại quầy, xóa hồ sơ online, email hồ sơ theo email tài khoản, ghi audit. |
| Chọn "Không phải tôi" | A02 | Khách đang đăng nhập | — | — | — |
| Danh sách nhân viên | A03, A04, A05 | ADMIN: SUPER_MANAGER · SUPER_MANAGER: A05–A08 · BRANCH_MANAGER: A06–A08 chi nhánh mình | — | — | — |
| Tạo tài khoản nhân viên | A03, A04, A05 | Theo BR-QT-01 | — → ACTIVE (Tài khoản#4) | — | Sinh mật khẩu ngẫu nhiên gửi STAFF_TEMP_PASSWORD; người tạo không thấy mật khẩu; mustChangePassword = true. |
| Chi tiết nhân viên | A03, A04, A05 | Trong phạm vi như listStaff | — | — | — |
| Đổi chức vụ | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Điều chuyển chi nhánh | A04 | Chỉ SUPER_MANAGER | — | — | — |
| Vô hiệu hóa nhân viên | A03, A04, A05 | Theo BR-QT-07; không tự vô hiệu hóa mình | ACTIVE → DISABLED (Tài khoản#5) | — | Hủy mọi phiên (BR-QT-09). |
| Kích hoạt lại nhân viên | A03, A04, A05 | Cùng phạm vi với vô hiệu hóa | DISABLED → ACTIVE (Tài khoản#6) | — | Cấp mật khẩu tạm mới, mustChangePassword = true. |
| Gửi lại mật khẩu ban đầu | A03, A04, A05 | Người có quyền tạo tài khoản đó (BR-QT-01) | — | — | Sinh mật khẩu mới, mã cũ hết hiệu lực. |
| Sửa email nhân viên — gửi OTP tới email mới | A03, A04, A05 | Cấp trên theo BR-QT-01 | — | — | — |
| Sửa email nhân viên — xác nhận OTP | A03, A04, A05 | Cấp trên theo BR-QT-01 | — | — | Ghi audit; gửi EMAIL_CHANGED_NOTICE tới email cũ và mới. |
| Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP | A06 | Lễ tân | — | — | — |
| Sửa email tài khoản khách — xác nhận OTP | A06 | Lễ tân | — | — | Ghi audit kèm phương thức xác minh; gửi EMAIL_CHANGED_NOTICE tới email cũ và mới. |
| Liên kết hồ sơ tại quầy với tài khoản | A06 | Lễ tân | — | — | Không cần OTP: lễ tân đã đối chiếu CCCD. Ghi audit. |
| Tìm tài khoản (khách và nhân viên) | A03 | Chỉ ADMIN | — | — | — |
| Khóa tài khoản | A03 | Chỉ ADMIN; không khóa chính mình | is_locked false → true (Tài khoản#7) | — | Hủy mọi phiên; Visit IN_PROGRESS đánh dấu cần gán lại; thông báo theo BR-QT-11 (khách: lễ tân các chi nhánh có việc dở — 06 Q2); ghi audit. |
| Mở khóa tài khoản | A03 | Chỉ ADMIN | is_locked true → false (Tài khoản#8) | — | status giữ nguyên giá trị trước khi khóa; ghi audit. |
| Danh sách tham số [CFG] | A03 | Chỉ ADMIN | — | — | — |
| Sửa tham số | A03 | Chỉ ADMIN | — | — | Chỉ áp dụng cho giao dịch tạo sau; ghi audit. |
| Danh sách mẫu thông báo | A03 | Chỉ ADMIN | — | — | — |
| Sửa nội dung mẫu | A03 | Chỉ ADMIN | — | — | Không có endpoint thêm / xóa mẫu. |
| Khôi phục mẫu mặc định | A03 | Chỉ ADMIN | — | — | — |
| Xem nhật ký audit | A03 | Chỉ ADMIN | — | — | Chỉ đọc; không có endpoint sửa / xóa (BR-QT-16). |
| Đội ngũ bác sĩ | A01, A02 | Public | — | — | Chỉ VET ACTIVE, không khóa. |

---

## C. Chi tiết endpoint

### Auth

- **`POST /auth/register`** — Đăng ký tài khoản. Public. Request `RegisterRequest`. Response `201` `RegistrationResponse`. Lỗi: `400` `BR-TK-01` email đã được sử dụng · `BR-TK-02` chưa xác nhận ≥ 18 tuổi / điều khoản · `BR-TK-03` mật khẩu yếu.
- **`POST /auth/register/verify`** — Xác thực OTP đăng ký. Public. Request `VerifyOtpRequest`. Response `200` `VerificationResponse`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `404` Không có tài khoản PENDING với email này.
- **`POST /auth/register/resend-otp`** — Gửi lại OTP đăng ký. Public. Request `EmailRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-07` chưa đủ 60 giây hoặc vượt 5 mã/giờ · `404` Không có tài khoản PENDING với email này.
- **`POST /auth/login`** — Đăng nhập. Public. Request `LoginRequest`. Response `200` `LoginResponse`. Lỗi: `400` `BR-TK-08` tài khoản chưa xác thực → FE chuyển màn OTP · `BR-TK-09` đang khóa tạm, message có giờ thử lại · `BR-TK-11` tài khoản bị khóa / vô hiệu hóa · `401` Sai email hoặc mật khẩu — thông điệp chung (BR-TK-10); sai lần thứ 5 trong 15 phút [CFG] kích hoạt ST01.
- **`POST /auth/logout`** — Đăng xuất. Response `204` rỗng. Lỗi: `401`.
- **`POST /auth/password/forgot`** — Quên mật khẩu — gửi OTP. Public. Request `EmailRequest`. Response `202` `OtpSentResponse`. Lỗi: `400` Email sai định dạng.
- **`POST /auth/password/reset`** — Đặt lại mật khẩu bằng OTP. Public. Request `ResetPasswordRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `BR-TK-03` mật khẩu không hợp lệ.
### Me

- **`GET /me`** — Thông tin người đang đăng nhập. Response `200` `MeResponse`. Lỗi: `401`.
- **`POST /me/password`** — Đổi mật khẩu. Request `ChangePasswordRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-14` mật khẩu hiện tại sai (tính vào bộ đếm BR-TK-09) · `BR-TK-03` mật khẩu mới không hợp lệ hoặc trùng mật khẩu cũ · `401`.
- **`PATCH /me/staff-profile`** — Sửa hồ sơ nhân viên của tôi. Request `UpdateStaffProfileRequest`. Response `200` `StaffProfile`. Lỗi: `400` `BR-TK-20` mô tả ngắn vượt 500 ký tự [CFG] · `BR-TK-15` cố sửa email · `401` · `403`.
### Profile link

- **`GET /me/link-candidates`** — Danh sách hồ sơ tại quầy có thể liên kết. Query: `phone?`. Response `200` mảng `LinkCandidate`. Lỗi: `400` `BR-TK-19` hồ sơ online đã phát sinh dữ liệu, không liên kết được · `401` · `403`.
- **`POST /me/link/otp`** — Gửi OTP tới email hồ sơ tại quầy. Request `LinkOtpRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-19` hồ sơ không có email (ra quầy) hoặc hồ sơ online đã có dữ liệu · `BR-TK-07` gửi quá nhanh / quá 5 mã/giờ · `401` · `404`.
- **`POST /me/link/confirm`** — Xác nhận liên kết. Request `LinkConfirmRequest`. Response `200` `LinkResult`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `BR-TK-19` hồ sơ online đã phát sinh dữ liệu · `401` · `404`.
- **`POST /me/link/decline`** — Chọn "Không phải tôi". Response `204` rỗng. Lỗi: `401` · `409` Hồ sơ không còn cờ chờ quyết định.
### Staff

- **`GET /staff`** — Danh sách nhân viên. Query: `branchId?`, `role?`, `status?`, `q?`, `page?`, `size?`. Response `200` trang `StaffResponse`. Lỗi: `401` · `403`.
- **`POST /staff`** — Tạo tài khoản nhân viên. Request `CreateStaffRequest`. Response `201` `StaffResponse`. Lỗi: `400` `BR-TK-01` email đã dùng · `BR-QT-03` thiếu chi nhánh hoặc chi nhánh không DRAFT/ACTIVE · `401` · `403` `BR-QT-01` chức vụ / chi nhánh ngoài quyền (ghi audit).
- **`GET /staff/{id}`** — Chi tiết nhân viên. Response `200` `StaffResponse`. Lỗi: `401` · `403` · `404`.
- **`POST /staff/{id}/change-role`** — Đổi chức vụ. Request `ChangeRoleRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-04` là BRANCH_MANAGER ACTIVE cuối cùng của chi nhánh ACTIVE · `401` · `403` · `404`.
- **`POST /staff/{id}/transfer`** — Điều chuyển chi nhánh. Request `TransferStaffRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-08` còn Visit được gán chưa xong / ca thu ngân OPEN (message liệt kê) · `BR-QT-04` BRANCH_MANAGER cuối cùng · `BR-QT-03` chi nhánh không hợp lệ · `401` · `403` · `404`.
- **`POST /staff/{id}/disable`** — Vô hiệu hóa nhân viên. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-08` còn quy trình dở dang (message liệt kê) · `BR-QT-12` đang bị khóa, cần mở khóa trước · `BR-QT-04` BRANCH_MANAGER cuối cùng · `401` · `403` `BR-QT-07` ngoài phạm vi hoặc chính mình · `404` · `409`.
- **`POST /staff/{id}/reactivate`** — Kích hoạt lại nhân viên. Request `ReactivateStaffRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-QT-10` đang bị khóa · `BR-QT-03` chi nhánh không hợp lệ · `401` · `403` · `404` · `409`.
- **`POST /staff/{id}/resend-initial-password`** — Gửi lại mật khẩu ban đầu. Response `204` rỗng. Lỗi: `401` · `403` · `404` · `409` Nhân viên đã đổi mật khẩu lần đầu.
- **`POST /staff/{id}/email-change`** — Sửa email nhân viên — gửi OTP tới email mới. Request `EmailChangeRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` `BR-TK-01` email mới đã dùng · `BR-TK-07` gửi quá nhanh · `401` · `403` · `404`.
- **`POST /staff/{id}/email-change/confirm`** — Sửa email nhân viên — xác nhận OTP. Request `OtpCodeRequest`. Response `200` `StaffResponse`. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `401` · `403` · `404`.
### Customer account

- **`POST /customers/{customerId}/account-email-change`** — Sửa email tài khoản khách / khôi phục tài khoản — gửi OTP. Request `CustomerEmailChangeRequest`. Response `200` `OtpSentResponse`. Lỗi: `400` Hồ sơ chưa gắn tài khoản · `BR-TK-01` email mới đã dùng · `BR-TK-07` gửi quá nhanh · `401` · `403` · `404`.
- **`POST /customers/{customerId}/account-email-change/confirm`** — Sửa email tài khoản khách — xác nhận OTP. Request `OtpCodeRequest`. Response `204` rỗng. Lỗi: `400` `BR-TK-05` mã sai / hết hạn · `BR-TK-06` sai quá 5 lần, mã bị hủy · `401` · `403` · `404`.
- **`POST /customers/{customerId}/link-account`** — Liên kết hồ sơ tại quầy với tài khoản. Request `CounterLinkRequest`. Response `200` `LinkResult`. Lỗi: `400` `BR-TK-19` hồ sơ không phải COUNTER / đã liên kết / hồ sơ online đã phát sinh dữ liệu · `401` · `403` · `404`.
### Accounts

- **`GET /accounts`** — Tìm tài khoản (khách và nhân viên). Query: `q?`, `role?`, `isLocked?`, `page?`, `size?`. Response `200` trang `AccountSummary`. Lỗi: `401` · `403`.
- **`POST /accounts/{id}/lock`** — Khóa tài khoản. Request `ReasonRequest`. Response `200` `LockResult`. Lỗi: `400` Thiếu lý do · khóa chính mình · `401` · `403` · `404` · `409` Đã bị khóa.
- **`POST /accounts/{id}/unlock`** — Mở khóa tài khoản. Request `ReasonRequest`. Response `200` `AccountSummary`. Lỗi: `400` · `401` · `403` · `404` · `409` Không bị khóa.
### Configuration

- **`GET /system-configs`** — Danh sách tham số [CFG]. Response `200` mảng `SystemConfig`. Lỗi: `401` · `403`.
- **`PUT /system-configs/{key}`** — Sửa tham số. Request `UpdateConfigRequest`. Response `200` `SystemConfig`. Lỗi: `400` `BR-QT-13` ngoài khoảng hợp lệ, message ghi khoảng cho phép · `401` · `403` · `404`.
- **`GET /notification-templates`** — Danh sách mẫu thông báo. Response `200` mảng `NotificationTemplate`. Lỗi: `401` · `403`.
- **`PUT /notification-templates/{code}`** — Sửa nội dung mẫu. Request `UpdateTemplateRequest`. Response `200` `NotificationTemplate`. Lỗi: `400` `BR-QT-14` dùng biến không tồn tại hoặc thiếu biến bắt buộc (message chỉ rõ biến) · `401` · `403` · `404`.
- **`POST /notification-templates/{code}/restore-default`** — Khôi phục mẫu mặc định. Response `200` `NotificationTemplate`. Lỗi: `401` · `403` · `404`.
### Audit

- **`GET /audit-logs`** — Xem nhật ký audit. Query: `actorAccountId?`, `action?`, `entityType?`, `entityId?`, `from?`, `to?`, `page?`, `size?`. Response `200` trang `AuditLogEntry`. Lỗi: `400` · `401` · `403`.
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
- **`LinkOtpRequest`** — {`customerId`: int64}
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
- **`UpdateTemplateRequest`** — {`subject?`: string, `body`: string}
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
7. Khi `mustChangePassword = true`, mọi API trừ `GET /me`, `POST /me/password`, `POST /auth/logout` trả 400 `BUSINESS_RULE_VIOLATION`, message kèm `BR-TK-17` (A4).
8. Mỗi request của nhân viên cập nhật `last_seen_at`; đăng xuất đặt về offline ngay (BR-TN-06).

---

## E. Giả định & câu hỏi mở

| ID | Giả định | Lý do |
|---|---|---|
| A1 | Access token gắn 1-1 với một phiên (`sessions`), không có refresh token; phiên hết hạn thì đăng nhập lại | Docs chỉ có bảng `sessions` có `expires_at`, `revoked_at`; không nhắc refresh |
| A2 | Xác thực OTP đăng ký không tự đăng nhập; client gọi `POST /auth/login` sau đó | Ít phát minh nhất; 03 Tài khoản#2 không có hệ quả tạo phiên |
| A3 | Đăng ký lại bằng email còn `PENDING` bị báo trùng (BR-TK-01); người dùng dùng gửi lại OTP | Email duy nhất trong tập tài khoản, `PENDING` vẫn là tài khoản |
| A4 | Chặn mọi chức năng khi chưa đổi mật khẩu lần đầu bằng 400, message kèm `BR-TK-17` | BR-TK-17 'chặn mọi chức năng khác' |
| A5 | Khách tự sửa họ tên, SĐT, ảnh ở module customer; identity chỉ sửa hồ sơ nhân viên | BR-TK-15: với khách, họ tên và SĐT là thông tin của hồ sơ khách |
| A6 | `verificationMethod` khi sửa email hộ / liên kết tại quầy chỉ có `ID_CARD_IN_PERSON` | BR-TK-16: audit chỉ ghi phương thức xác minh, docs chỉ nêu CCCD |
| A7 | Gửi lại OTP quên mật khẩu = gọi lại `POST /auth/password/forgot` (cùng quota BR-TK-07) | Tránh endpoint thứ hai lộ trạng thái tài khoản |

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Thời hạn phiên đăng nhập bao lâu, có thêm vào [CFG] không, có cần refresh token cho app không? | TBD (PO + BE) | Docs không quy định (A1) |
| Q2 | Lỗi `BR-TK-09` (khóa tạm, kèm giờ thử lại) cho biết email tồn tại, lệch với tinh thần BR-TK-10. Chấp nhận như BR-TK-09 yêu cầu? | TBD (PO), tạm theo BR-TK-09 | BR-TK-09 vs BR-TK-10 |

---

## F. OpenAPI 3.1

[`./openapi/identity-v1.yaml`](./openapi/identity-v1.yaml)
