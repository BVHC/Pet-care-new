# ADR-0034: Sửa hộ email nhân viên (T14 phần 4)

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-12
- **Liên quan:**
  - identity-v1 #23 `POST /staff/{id}/email-change`, #24 `POST /staff/{id}/email-change/confirm` (UC08).
  - BR-TK-01 (02 L17), 04 (L20), 05 (L21), 06 (L22), 07 (L23), 15 (L31), 16 (L32); BR-QT-01 (L44), 15 (L58); 03 §1 (đổi email không phải chuyển trạng thái).
  - ADR-0009, 0010 (sai mã vẫn commit bộ đếm), 0011 (khóa `accounts` cho luồng OTP), 0012 (outbox), 0023 (BCrypt ngoài khóa), 0025 (OTP gắn chủ thể, hủy mã theo tài khoản, checklist bộ đếm), 0029 (policy, 403 audit sau rollback, thứ tự kiểm, thứ tự khóa), 0030 (`StaffResponse` một đường map). Nợ D015 (mở rộng).
- **Triển khai:** `identity/controller/StaffController` (#23, #24, `@PreAuthorize` ADMIN / SUPER_MANAGER / BRANCH_MANAGER); `identity/dto/{EmailChangeRequest, OtpCodeRequest}`; `StaffAdminService.{startEmailChange, confirmEmailChange}`; bean mới `StaffEmailChangeService.{issueEmailChangeOtp, findEmailChangeCode, applyEmailChange}`; `OtpService.findActiveForAccount` + record `AccountBoundOtp`; `OtpPurpose.invalidatesPerAccount()`; `OtpTokenRepository.findTopByAccountIdAndPurpose…`; `Account.changeEmail`; `StaffAdminMapper.toOtpSentResponse`; `QtAuditActions.STAFF_EMAIL_CHANGED`. Không migration: `CHANGE_EMAIL` có trong CHECK V1, index `ix_otp_tokens_account_id` (V5), mẫu `OTP_EMAIL_CHANGE` / `EMAIL_CHANGED_NOTICE` (V11). Test: `StaffEmailChangeServiceTest`, `StaffAdminServiceTest`, `OtpServiceTest`, `StaffAccountEntityTest`, `StaffAdminMapperTest`, `StaffEmailChangeRequestValidationTest`, `module/identity/StaffEmailChangeIT`.

## Bối cảnh

BR-TK-16 nói ai được sửa hộ email nhân viên (cấp trên theo BR-QT-01), mã OTP gửi tới **email mới**, chưa xác thực thì giữ email cũ, mỗi lần sửa ghi audit và thông báo tới cả email cũ lẫn mới. Body #24 chỉ có `code`. Spec và contract không nói: nhân viên ở trạng thái nào thì sửa được; có hủy phiên không; mã cũ bị hủy theo email hay theo tài khoản; bước gửi mã có audit không; email mới trùng email hiện tại thì sao; audit có ghi phương thức xác minh không. Người dùng chốt Q1–Q7 ngày 2026-10-12.

## Quyết định

1. **Q1 — mọi trạng thái:** `ACTIVE`, `DISABLED`, đang bị khóa đều sửa được (khuôn ADR-0031 Q1). Đổi email chỉ đổi cột `email`; `status`, `is_locked`, mật khẩu, bộ đếm, `last_seen_at`, chi nhánh giữ nguyên (`Account.changeEmail`). Tài khoản `PENDING` không đổi được (lỗi lập trình).
2. **Q2 — không hủy phiên:** JWT chỉ mang id tài khoản (ADR-0003), phiên cũ vẫn hợp lệ, `GET /me` trả email mới.
3. **Q3 — mã gắn `(account_id = nhân viên, target_email = email mới)`, hủy mã cũ theo tài khoản:** `OtpPurpose.invalidatesPerAccount()` đúng với `LINK_PROFILE` và `CHANGE_EMAIL`; `issuePrepared` chọn cách hủy theo method này (thay điều kiện `customerId != null` — tương đương với LINK_PROFILE nhờ guard ADR-0025 mục 3). Mỗi nhân viên tối đa một mã đổi email còn sống; tài khoản khác xin mã tới cùng email không hủy được mã này; mọi lệnh ghi lên mã của nhân viên nằm dưới khóa `accounts` của nhân viên đó. #24 tra mã chỉ bằng tài khoản (`findActiveForAccount`, chỉ nhận `CHANGE_EMAIL`), email mới đọc từ chính dòng mã. `issueOtp` (đăng ký) không đổi.
4. **Q4 — audit chỉ khi xác nhận:** `STAFF_EMAIL_CHANGED`, `entity = accounts/<id>`, before `{email}`, after `{email, verificationMethod}`. #23 không đổi dữ liệu nên không audit; 403 phân cấp ở cả hai bước vẫn audit `STAFF_ACTION_DENIED` sau rollback (ADR-0029 mục 2).
5. **Q5 — trùng email hiện tại → 400 BR-TK-01** "Email mới trùng email hiện tại" (spec không nói; giả định), không gửi mã, không tiêu quota.
6. **Q6 — `verificationMethod = EMAIL_CODE`** trong audit (BR-TK-16 "audit chỉ ghi phương thức xác minh"; dùng chung hằng với UC07).
7. **#23 (facade, rồi một transaction):** `OtpService.prepare()` (BCrypt) ngoài transaction → `findByIdForUpdate(accounts)` (404 không có / khách) → đọc `staff_profiles` → policy `CHANGE_EMAIL` (403) → chuẩn hóa `strip().toLowerCase(ROOT)` → Q5 → `existsByEmail` (BR-TK-01, tính cả `PENDING`) → `issuePrepared` (BR-TK-07 trước mọi lệnh ghi; hủy mã của nhân viên; INSERT) → outbox `OTP_EMAIL_CHANGE {ma_otp, thoi_han_phut}` tới email mới. Response `OtpSentResponse` với `maskedEmail = null`. Mọi lỗi ném trước lệnh ghi đầu tiên.
8. **#24 (facade, ba bước):**
   1. `findEmailChangeCode` (readOnly): projection `findStaffRow` (404) → policy (403) **trước** khi tra mã, để người ngoài phân cấp không biết nhân viên có mã đang mở → mã còn mở. Không có → facade ném 400 BR-TK-05 sau khi transaction đọc kết thúc.
   2. BCrypt `matches` ngoài transaction.
   3. `applyEmailChange` (`noRollbackFor = OtpRejectedException`): khóa `accounts` → hồ sơ → **policy kiểm lại dưới khóa** (nhân viên có thể vừa bị điều chuyển) → `settleCheckedOtp` là lệnh ghi đầu tiên (sai → bộ đếm commit; mã mới hơn / hết hạn → BR-TK-05 không đếm) → `existsByEmail` dưới khóa (BR-TK-01 → rollback cả `consumed_at`, mã dùng lại được) → `changeEmail` → `EMAIL_CHANGED_NOTICE {email_cu, email_moi, thoi_diem}` tới email cũ rồi email mới (`thoi_diem` `HH:mm dd/MM/yyyy` giờ VN) → audit → `flush()` (vi phạm `uq_accounts_email` → 409 tại chỗ) → đọc lại projection `StaffDetailRow` → `StaffResponse` (≡ `GET /staff/{id}`).
9. **Khóa:** `accounts(X) → otp_tokens(X)`; không khóa `branches` (đổi email không đổi số BRANCH_MANAGER, ADR-0029 mục 5). Không có BCrypt hay SMTP trong lúc giữ khóa. Email đích chưa thuộc ai nên không có dòng để khóa: `existsByEmail` dưới khóa cho lỗi BR-TK-01 đẹp, `uq_accounts_email` là chốt cuối (409).
10. **Bean mới `StaffEmailChangeService`** thay vì thêm vào `StaffAccountService`: ba file unit test cũ tạo `StaffAccountService` bằng `new`, không đụng constructor thì không phải sửa (giảm phạm vi ảnh hưởng).

## Lý do và phương án bị loại

- **Hủy mã theo email (như REGISTER):** tài khoản Y nhắm cùng email sẽ hủy được mã của X và ghi lên dòng mã của X mà không giữ khóa X; mã cũ của X tới email khác vẫn sống → #24 không tra được mã duy nhất chỉ bằng tài khoản. Loại (Q3).
- **Body #24 thêm `newEmail`:** contract đã chốt `OtpCodeRequest {code}`; email mới đã nằm trong dòng mã.
- **Kiểm phân cấp sau khi tra mã ở #24:** người ngoài phân cấp phân biệt được 400 BR-TK-05 (không có mã) với 403 → dò được nhân viên đang đổi email. Loại.
- **Không kiểm lại policy dưới khóa:** BRANCH_MANAGER vừa mất quyền (nhân viên bị điều chuyển) vẫn tiêu được bộ đếm / đổi được email. Loại.
- **Hủy phiên / gửi lại mật khẩu ban đầu tự động:** spec không yêu cầu (Q2); gửi lại mật khẩu ban đầu là thao tác riêng (#22), ghi vào notes hợp đồng #24.

## Hệ quả

- Email là định danh (BR-TK-01): sau khi đổi, đăng nhập / quên mật khẩu bằng email cũ như email lạ (401 / 202 không gửi mã), email mới dùng được ngay với mật khẩu cũ; `GET /staff?q=` tìm theo email mới; email cũ được giải phóng cho người khác dùng.
- Mã đặt lại mật khẩu đã gửi tới email cũ vô dụng: `applyReset` settle theo email đọc dưới khóa (= email mới).
- Thư đã xếp hàng trước khi đổi vẫn tới email cũ (người nhận chốt lúc ghi outbox, 06 §1). Audit cũ giữ `actor_email` cũ (BR-QT-16).
- Nhân viên chưa đổi mật khẩu lần đầu: mật khẩu tạm cũ (đã gửi tới email cũ) vẫn dùng được cho tới khi gọi #22 — notes hợp đồng #24 hướng dẫn FE.
- **R14 — chấp nhận:** đăng nhập bằng email cũ đang chạy dở khi việc đổi email commit có thể thành công đúng một lần (login đọc credential theo email trước khi khóa theo id, không so lại email dưới khóa). Mật khẩu vẫn đúng và phiên cũ cũng không bị hủy (Q2), nên không mở thêm quyền.
- **Q7 — nợ (mở rộng D015):** quota BR-TK-07 tính theo email đích và câu đếm không khóa: (1) quản lý gửi mã tới email của người khác tiêu quota của email đó; (2) hai #23 cho hai nhân viên khác nhau tới cùng email chạy song song có thể cùng vượt mốc 60 giây / 5 mã. Chỉ người đã đăng nhập có quyền gây ra; hậu quả là vài email thừa. Cách trả: advisory lock theo email trong `OtpService` (ADR riêng).
- #25, #26 (sửa email tài khoản khách tại quầy) dùng lại `OtpCodeRequest`, `findActiveForAccount`, `Account.changeEmail`, hai mẫu V11.

## TBD

- #25 / #26: đồng bộ `customers.email` của hồ sơ gắn tài khoản khi đổi email tài khoản khách (BR-KH-01 "email lấy theo tài khoản") — chốt khi làm UC22.
