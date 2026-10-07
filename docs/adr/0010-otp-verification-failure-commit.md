# ADR-0010: Nhập sai OTP vẫn lưu bộ đếm — `OtpRejectedException` + `noRollbackFor`

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-06
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-05 (OTP sai / hết hạn, dùng một lần), BR-TK-06 (sai tối đa 5 lần [CFG] thì hủy mã), BR-QT-13 (giá trị [CFG] chỉ áp dụng cho giao dịch tạo sau).
  - `docs/03-state-machines.md` Tài khoản#2 (xác thực OTP).
  - `docs/05-erd.md` `otp_tokens.failed_attempts`, `consumed_at`, `invalidated_at`.
  - `docs/convention/backend/04-exception-handling.md` §4.2 tiêu chí 3; `07-transaction-management.md` L9 (cấm `REQUIRES_NEW`), §7.3 (khóa cần ADR).
  - ADR-0009 (OTP lưu BCrypt hash). Nợ D005 (`docs/dept/`).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/identity/exception/OtpRejectedException.java`.
  - `BE/src/main/java/com/petcare/module/identity/service/OtpService.java` (`consumeOtp`), `RegistrationService.java` (`verifyAccount`), `entity/OtpToken.java` (`isExpiredAt`, `recordFailedAttempt`, `consume`).
  - Test (tiêu chí → test):
    - Lần sai trả 400 và bộ đếm được commit: `RegistrationVerificationIT.wrongCodeIsRejectedWith400AndFailedAttemptIsCommitted` (bỏ `noRollbackFor` ở `consumeOtp` → test trả 500 `UnexpectedRollbackException`, đã kiểm bằng mutation 2026-10-06).
    - Sai lần thứ max thì hủy mã, mã đúng không còn dùng được: `RegistrationVerificationIT.fifthWrongCodeCancelsCodeSoCorrectCodeNoLongerWorks`, `OtpServiceTest.fifthWrongCodeCancelsTheCode`, `maxFailedAttemptsComesFromConfig`.
    - Lỗi sau khi đã dùng mã vẫn rollback toàn bộ: `RegistrationVerificationIT.failureAfterConsumingCodeRollsBackEverythingAndCodeStaysUsable`.
    - Mọi lần từ chối đúng kiểu `OtpRejectedException`: `OtpServiceTest.assertOtpRejected`.

## Bối cảnh

BR-TK-06 yêu cầu đếm số lần nhập sai của mỗi mã OTP và hủy mã khi chạm giới hạn. Request nhập sai phải trả lỗi 400 (BR-TK-05 / 06) bằng exception, mà Spring mặc định rollback transaction khi có `RuntimeException` — lệnh tăng `failed_attempts` mất theo, nên người dùng đoán mã vô hạn lần. Chỗ đầu tiên gặp là `POST /api/auth/register/verify` (UC02); sau này còn đặt lại mật khẩu (UC04), liên kết hồ sơ (UC07), đổi email hộ (UC08, UC22).

## Quyết định

1. **Lớp riêng `identity/exception/OtpRejectedException extends BusinessRuleViolationException`** cho mọi lần từ chối mã OTP (BR-TK-05: không còn mã / hết hạn / sai; BR-TK-06: sai lần thứ max). Theo tiêu chí 3 của convention 04 §4.2 (cần cách rollback riêng). Client nhận như mọi lỗi rule: 400 `BUSINESS_RULE_VIOLATION`, message kèm mã rule.
2. **`noRollbackFor = OtpRejectedException.class` ở mọi `@Transactional` mà exception đi qua**: `OtpService.consumeOtp` (`MANDATORY`) và method use case gọi nó. Thiếu ở method trong thì interceptor của nó đánh dấu transaction rollback-only; method ngoài commit sẽ ném `UnexpectedRollbackException` → 500.
3. **Bất biến:** chỉ ném `OtpRejectedException` khi lệnh ghi duy nhất đã xảy ra trong transaction là cập nhật bộ đếm / `invalidated_at` của chính dòng OTP. Use case gọi `consumeOtp` **trước** mọi lệnh ghi khác; lỗi sau bước này là exception khác nên rollback toàn bộ, kể cả `consumed_at`.
4. **Bộ đếm thuộc từng mã, không bao giờ đặt lại về 0.** Sai lần thứ max → `invalidated_at`; mã mới (gửi lại OTP) là dòng mới với `failed_attempts = 0`, và `issueOtp` vô hiệu mã cũ. Số lần đoán tối đa = `otp.max_failed_attempts × otp.max_sends_per_window` (mặc định 5 × 5 = 25 lần/giờ/email trên 10⁶ mã).
5. Mã hết hạn từ đúng thời điểm `expires_at` (`now >= expires_at`); mã hết hạn không tính lần sai.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| `REQUIRES_NEW` ghi bộ đếm ở transaction riêng | Convention 07 L9 cấm trong nghiệp vụ; cần connection thứ hai |
| Method `@Transactional` trả kết quả (OK / WRONG / EXHAUSTED), lớp ngoài không transaction ném lỗi sau commit | Thêm một lớp và một bean cho mỗi use case OTP; với đồ án, một exception + một thuộc tính annotation đơn giản hơn |
| `noRollbackFor = BusinessRuleViolationException.class` | Quá rộng: mọi lỗi rule sau khi đã ghi dữ liệu (ví dụ ở bước hệ quả) cũng commit dở dang |
| Đặt lại `failed_attempts = 0` khi gửi lại mã | Không cần: mã mới là dòng mới; đặt lại trên dòng cũ còn mở đường đoán vô hạn |

## Hệ quả

- Mọi use case xác thực OTP sau này (UC04, UC07, UC08, UC22) gọi `OtpService.consumeOtp` đầu tiên và khai báo `@Transactional(noRollbackFor = OtpRejectedException.class)`.
- **Không khóa đồng thời** (người dùng chốt 2026-10-06): hai request sai mã song song có thể mất một lần tăng bộ đếm (lost update), nên đoán được quá max lần — nợ D005, trả bằng ADR khóa (convention 07 §7.3).
- **BR-QT-13 chưa trọn:** `otp_tokens` không có cột chốt `max_failed_attempts`, nên đổi `otp.max_failed_attempts` [CFG] áp dụng ngay cho mã đang còn hiệu lực (khác `expires_at`, đã chốt lúc sinh). Mã sống tối đa 15 phút ([CFG] `otp.ttl_minutes` max) nên ảnh hưởng nhỏ; sửa cần đổi ERD.
- Sai max lần ở đăng ký: người dùng phải chờ API gửi lại OTP (nợ D002) hoặc ST02 xóa tài khoản `PENDING`.
