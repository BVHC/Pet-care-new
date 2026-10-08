# ADR-0023: Quên / đặt lại mật khẩu — không lộ tài khoản, BCrypt ngoài khóa dòng, mã đặt lại xác thực ngoài transaction

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-08
- **Supersedes:** — (bổ sung ADR-0011 mục 5 cho UC04: vẫn khóa dòng `accounts` trước mọi ghi `otp_tokens`, nhưng BCrypt chạy ngoài khóa; đăng ký giữ nguyên ADR-0011)
- **Liên quan:**
  - `docs/01-business-operations.md` UC04; `docs/02-business-rules.md` BR-TK-03 (chính sách, không trùng mật khẩu hiện tại — áp dụng cả UC04), BR-TK-04…07 (OTP), BR-TK-10 (không tiết lộ tài khoản ở quên mật khẩu), BR-TK-12 (chỉ `ACTIVE` hoặc đang khóa tạm), BR-TK-13 (đăng xuất mọi phiên, gỡ khóa tạm, email xác nhận), BR-TN-06 (đăng xuất thì offline ngay), BR-QT-15.
  - `docs/api/identity-v1.md` `POST /auth/password/forgot` (202), `POST /auth/password/reset` (204; 400 BR-TK-03/05/06), A7 (gửi lại = gọi lại forgot); `docs/api/00-method.md` §3.4 (202).
  - ADR-0005 (path public ẩn danh), 0009 (BCrypt), 0010 (`noRollbackFor`), 0011 (khóa dòng `accounts` cho luồng OTP), 0012 (outbox, luồng HIGH), 0019 (BCrypt ngoài transaction, hash giả, *Hệ quả*: đặt lại xóa bộ đếm / `locked_until` / `must_change_password`), 0020 (OSIV tắt), 0021 (thứ tự `accounts → sessions`, `last_seen_at`), 0022 (`NewPasswordHasher.MSG_SAME_AS_CURRENT`).
  - Convention 02 (MapStruct, envelope 202/204), 04, 06, 07 L10, 08.
- **Triển khai:** `identity/controller/AuthController.{forgotPassword, resetPassword}`, `identity/dto/{ForgotPasswordRequest, ResetPasswordRequest}`, `identity/service/{PasswordResetService, PasswordResetAttemptService, BcryptDecoy}`, `OtpService.{prepare, issuePrepared, findActive, settleCheckedOtp}`, `identity/mapper/PasswordResetMapper`, `identity/repository/{AccountRepository.findResetStateByEmail, AccountResetState}`, `Account.resetPassword`, `platform/model/ApiResponse.accepted`. Bằng chứng: `OtpServiceTest`, `AccountTest`, `BcryptDecoyTest`, `PasswordResetServiceTest`, `PasswordResetAttemptServiceTest`, `PasswordResetIT` (31 ca); test cũ (`RegistrationResendIT`, `RegistrationVerificationIT`, `LoginIT`, `LogoutIT`, `ChangePasswordIT`…) không sửa, vẫn xanh.

## Bối cảnh

Hai endpoint public. Contract chỉ cho forgot một mã lỗi (400 email sai định dạng) và yêu cầu "luôn 202 cùng nội dung" (BR-TK-10), trong khi `OtpService.issueOtp` ném BR-TK-07 và khóa dòng chỉ xảy ra với email có tài khoản — để lọt thì 400/409 lộ email. Theo khuôn ADR-0011, luồng OTP chạy BCrypt (băm mã, so mã) khi đang giữ khóa dòng `accounts`: chấp nhận được cho đăng ký (tài khoản `PENDING` của chính người gọi), nhưng ở đặt lại, bất kỳ ai biết email đều gửi được ~10 request song song vào một tài khoản `ACTIVE` — mỗi request xếp hàng chờ khóa và giữ một connection, đủ làm cạn pool Hikari (10) và treo cả ứng dụng. BR-TK-03 "không trùng mật khẩu hiện tại" áp dụng cho UC04 nhưng kiểm trước OTP thì biến endpoint thành nơi thử mật khẩu cho người lạ. Người dùng chốt các điểm dưới đây ngày 2026-10-08.

## Quyết định

1. **Forgot luôn 202 cùng một body** (`OtpSentResponse{resendAvailableAt = now + otp.resend_interval_seconds [CFG], maskedEmail = null}`) ở mọi trường hợp: email lạ, tài khoản `PENDING` / `is_locked` / `DISABLED` (BR-TK-12: không phát mã), vi phạm BR-TK-07, lỗi khóa DB. Chỉ `ACTIVE` không `is_locked` nhận mã — kể cả đang khóa tạm (BR-TK-12). Gửi lại = gọi lại forgot (identity-v1 A7), cùng quota BR-TK-07.
2. **BR-TK-07 và `PessimisticLockingFailureException` bắt ở facade, sau khi transaction đã rollback.** `issuePrepared` là `MANDATORY` đi qua proxy: exception đi qua đó đã đánh dấu transaction rollback-only — bắt bên trong thì commit ném `UnexpectedRollbackException` (→ 500; chứng minh bằng mutation trên `PasswordResetIT.quotaViolationsStillReturn202WithoutWriting`). Quota kiểm trước mọi lệnh ghi nên không mất gì. Log `PASSWORD_RESET_OTP_THROTTLED` / WARN `PASSWORD_RESET_OTP_LOCK_FAILED`, không log email.
3. **Không BCrypt khi giữ connection hay khóa dòng** (khuôn ADR-0019 mục 4). Facade `PasswordResetService` không `@Transactional`; `PasswordResetAttemptService` giữ các transaction vài ms.
   - Forgot: `OtpService.prepare()` sinh mã và băm **trước** transaction, ở **mọi** nhánh (một lần BCrypt — thời gian phản hồi tự cân bằng, không cần hash giả); `issueResetOtp` khóa dòng (`findByEmailForUpdate`, ADR-0011) rồi `issuePrepared` (BR-TK-07 → vô hiệu mã đặt lại cũ → INSERT hash đã băm).
   - Reset: đọc không khóa (`findResetCandidate`: projection `AccountResetState` + `OtpService.findActive` → `otpId`, `codeHash`) → ngoài transaction đúng 2 lần `matches` (mã OTP; trùng mật khẩu hiện tại — chỉ so với hash thật khi mã đúng, ngược lại so với `BcryptDecoy`) và `encode` khi mã đúng và không trùng → `applyReset` dưới khóa (`findByIdForUpdate`).
4. **`OtpService.settleCheckedOtp(…, otpId, matched)`** ghi kết quả so mã dưới khóa của caller, không BCrypt: không còn mã, **mã mới nhất khác `otpId`** (vừa có mã mới từ một lần forgot khác) hoặc hết hạn → `OtpRejectedException` BR-TK-05 **không đếm** (lần so thuộc mã cũ, không phạt mã mới); sai → tăng `failed_attempts`, chạm `otp.max_failed_attempts` [CFG] thì hủy mã (BR-TK-05/06), ném sau khi ghi, commit nhờ `noRollbackFor` ở `settleCheckedOtp` và `applyReset` (ADR-0010); đúng → `consumed_at`. Hai lần sai song song cùng đọc một `otpId` vẫn được đếm đủ vì cả hai ghi tuần tự dưới khóa dòng. `issueOtp` / `consumeOtp` của đăng ký không đổi thứ tự (`RegistrationResendIT` dựa vào `encode` chạy sau khi vô hiệu mã cũ).
5. **Không lộ tài khoản ở reset (BR-TK-10):** email lạ, tài khoản không đủ điều kiện (đọc lại dưới khóa: vừa bị khóa / vô hiệu hóa / xóa), không có mã, mã sai, mã hết hạn → cùng 400 "OTP không đúng hoặc đã hết hạn (BR-TK-05)". Mọi nhánh chạy cùng số lần BCrypt (`BcryptDecoy`, hash giả tạo lúc khởi động như `LoginService.dummyHash`).
6. **Thứ tự kiểm ở reset:** hình thức (`@Valid`) → BR-TK-03 chính sách (`PasswordPolicy`, không tiêu lượt nhập mã) → tài khoản / mã (BR-TK-05) → OTP (BR-TK-05/06) → BR-TK-03 trùng mật khẩu hiện tại (**sau khi mã đúng** — người lạ không dùng được endpoint để thử mật khẩu; trùng → 400, rollback cả `consumed_at`, mã dùng lại được với mật khẩu khác).
7. **Thành công (`Account.resetPassword`):** hash mới, `must_change_password = false` (ADR-0019 *Hệ quả*), bộ đếm sai = 0, `locked_until = NULL` kể cả khi còn hạn (BR-TK-13), **`last_seen_at = NULL`** (BR-TK-13 đăng xuất mọi phiên + BR-TN-06 offline ngay, như đăng xuất — ADR-0021), rồi `SessionService.revokeAll`, rồi outbox `PASSWORD_CHANGED` (`thoi_diem` = `HH:mm dd/MM/yyyy` giờ Việt Nam, luồng NORMAL). Thứ tự khóa `accounts → otp_tokens → sessions` (ADR-0021); request đang chạy với token cũ không ghi lại `last_seen_at` được (`touchLastSeen` `SKIP LOCKED` + `EXISTS` phiên chưa hủy, ADR-0021 mục 3). Không đổi `status` (không phải chuyển trạng thái SM #1). 204 không body.
8. **Hash vừa đổi song song** (hash dưới khóa khác hash đã đọc — đổi mật khẩu từ phiên khác): so lại "trùng mật khẩu hiện tại" và mã hóa dưới khóa. Ngoại lệ hiếm duy nhất có BCrypt dưới khóa, như ADR-0019 mục 4 / ADR-0022 mục 8.
9. **Không audit** (BR-QT-15 không liệt kê; nhất quán ADR-0022). Người dùng nhận email `PASSWORD_CHANGED`.
10. **Mapping / envelope:** response map bằng MapStruct (`PasswordResetMapper.toOtpSentResponse(Instant, String)`, không từ `IssuedOtp` vì nhánh không gửi mã không có giá trị đó); `ApiResponse.accepted` (`code = 202`). Record đi qua ranh giới transaction (`AccountResetState`, `PreparedOtp`, `ActiveOtp`, `ResetCandidate`, `ResetAttempt`) che hash / mã / mật khẩu trong `toString`.
11. **Ghi chú SQL:** `@Lock(PESSIMISTIC_WRITE)` trên PostgreSQL với Hibernate 6 sinh `FOR NO KEY UPDATE` (không phải `FOR UPDATE` như ADR-0011 mục 1 ghi): không xung đột với `KEY SHARE` của INSERT có FK (đăng nhập tạo phiên không chờ), vẫn tuần tự với mọi `findBy…ForUpdate` khác. Mọi câu của luồng dùng index có sẵn (`uq_accounts_email`, `ix_otp_tokens_target_email_created_at`, `ix_otp_tokens_account_id`, `ix_sessions_active_account`); [CFG] đọc từ bộ nhớ. Không migration.

## Phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Khuôn ADR-0011 nguyên vẹn (`consumeOtp`, BCrypt dưới khóa) | ~160–200 ms giữ khóa + connection mỗi request trên endpoint public: ~10 request song song vào một email làm cạn pool |
| Khuôn ADR-0011 + `SET LOCAL lock_timeout` | Khi bị dội vẫn giữ tới timeout mỗi connection; người dùng thật nhận 409 |
| Trả 400 BR-TK-07 khi forgot vượt quota | Email lạ không bao giờ nhận lỗi này → lộ email có tài khoản (BR-TK-10); trái contract |
| Kiểm "trùng mật khẩu hiện tại" trước OTP | Người lạ thử được mật khẩu của tài khoản qua endpoint |
| Bỏ kiểm "trùng mật khẩu hiện tại" ở reset | Lệch BR-TK-03 (áp dụng UC04) |
| Mã bị thay giữa bước đọc và bước khóa → so lại dưới khóa với mã mới | BCrypt dưới khóa và phạt mã mới vì một lần so thuộc mã cũ |
| Audit `PASSWORD_RESET` | Ngoài BR-QT-15; người dùng chọn không audit |
| Gộp `LoginService.dummyHash` vào `BcryptDecoy` | Phải sửa luồng đăng nhập đã xanh; để sau |

## Hệ quả

- Đặt lại chạy được cho cả khách và nhân viên ngay bây giờ: không gọi `CustomerApi` / `CustomerQueryApi` (placeholder D001, D010).
- Forgot thêm tối đa 5 thư HIGH / giờ / email (`OTP_PASSWORD_RESET` đã trong `DeliveryLane.HIGH_TEMPLATES`); payload `ma_otp` bị xóa sau khi gửi (ADR-0012).
- Endpoint public tốn BCrypt mỗi request (forgot 1, reset 2–3) và gửi được email tới nạn nhân → nợ D011 (rate limit theo IP) mở rộng sang hai endpoint này. `otp_tokens` của tài khoản `ACTIVE` không bao giờ bị xóa → nợ D012.
- Luồng OTP sau (UC07 liên kết hồ sơ, UC08/UC22 đổi email) trên tài khoản `ACTIVE` nên dùng `prepare` / `issuePrepared` / `findActive` / `settleCheckedOtp` thay vì `issueOtp` / `consumeOtp`.
- Convention 02 (envelope 202/204) và 07 L10 (ngoại lệ có chủ đích) cập nhật theo ADR này.
