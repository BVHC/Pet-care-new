# ADR-0009: Băm mật khẩu và mã OTP bằng BCrypt

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-06
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-03 (chính sách mật khẩu), BR-TK-04, 05, 07 (OTP), BR-QT-02 (mật khẩu tạm nhân viên).
  - `docs/05-erd.md` `accounts.password_hash` ("bcrypt/argon2"), `otp_tokens.code_hash` ("không lưu mã gốc").
  - `docs/api/identity-v1.md` mục D.1 (mật khẩu, OTP, token chỉ lưu dạng hash), A8.
  - ADR-0003 (token phiên băm SHA-256 — không đổi).
- **Triển khai:**
  - `BE/src/main/java/com/petcare/platform/security/PasswordConfig.java` (bean `PasswordEncoder`, `BCRYPT_MAX_BYTES`).
  - `BE/src/main/java/com/petcare/module/identity/service/RegistrationService.java` (`checkPasswordPolicy`), `OtpService.java` (`code_hash`).
  - Test (tiêu chí → test):
    - Mật khẩu chỉ lưu hash, khớp được: `RegistrationServiceTest.registersPendingCustomerThenProfileThenOtpThenOutboxInOrder`, `RegistrationIT.registersPendingAccountWithProfileOtpAndOutboxInOneTransaction`.
    - Giới hạn 72 byte → BR-TK-03, không 500: `RegistrationServiceTest.passwordOver72BytesIsRejectedBeforeBcrypt`, `passwordOfExactly72BytesIsAccepted`.
    - OTP chỉ lưu hash: `OtpServiceTest.issuesCodeOfConfiguredLengthStoredOnlyAsHash`.

## Bối cảnh

ERD chỉ ghi `password_hash` là "bcrypt/argon2" và `code_hash` là "không lưu mã gốc"; contract identity (mục D.1) chỉ yêu cầu lưu dạng hash. Đăng ký (UC01) là chỗ đầu tiên phải băm mật khẩu và OTP, nên cần chốt thuật toán, chỗ đặt bean và cách xử lý giới hạn của thuật toán. Spring Boot 3.5.15 dùng Spring Security 6.5.11; `BCrypt` của bản này ném `IllegalArgumentException("password cannot be more than 72 bytes")` khi đầu vào dài hơn 72 byte — không chặn trước thì request thành 500.

## Quyết định

1. **Một bean `PasswordEncoder` = `BCryptPasswordEncoder()`** (strength mặc định 10) ở `platform/security/PasswordConfig`. Mọi chỗ băm mật khẩu dùng bean này: đăng ký, đăng nhập, đổi / đặt lại mật khẩu, mật khẩu tạm nhân viên (BR-QT-02).
2. **`otp_tokens.code_hash` cũng băm bằng bean này.** Mã OTP ngắn (4–8 chữ số [CFG]); SHA-256 không salt thì dò ngược được trong vài giây nếu lộ DB, BCrypt có salt và chậm nên không.
3. **Mật khẩu dài hơn 72 byte UTF-8 vi phạm BR-TK-03** (400, message "Mật khẩu quá dài (tối đa 72 byte)"), kiểm ở service trước khi băm. Độ dài tối thiểu của BR-TK-03 vẫn đếm theo ký tự.
4. Token phiên (`sessions.token_hash`) giữ SHA-256 theo ADR-0003: `jti` là 128 bit ngẫu nhiên nên không cần thuật toán chậm.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Argon2 (`Argon2PasswordEncoder`) | Cần thêm dependency BouncyCastle; BCrypt đã có sẵn trong `spring-security-crypto` và đủ cho quy mô hệ thống |
| `DelegatingPasswordEncoder` (lưu tiền tố `{bcrypt}`) | Chỉ có ích khi đổi thuật toán sau này; chưa có nhu cầu (YAGNI). Đổi sau vẫn làm được bằng ADR mới + migration đánh dấu hash cũ |
| SHA-256 cho OTP | Không gian mã nhỏ (10⁶ với 6 số), dò ngược tức thì khi lộ DB |
| Cắt mật khẩu về 72 byte rồi băm | Hai mật khẩu khác nhau ở phần sau byte 72 sẽ cùng hash — người dùng không biết; từ chối rõ ràng tốt hơn |

## Hệ quả

- Đăng ký và các thao tác băm tốn ~100 ms CPU mỗi lần băm (strength 10); chấp nhận được với tần suất đăng ký / đăng nhập.
- Contract identity: `RegisterRequest.password` ghi "≤ 72 byte UTF-8". Các endpoint đổi / đặt lại mật khẩu sau này dùng cùng kiểm tra (`PasswordConfig.BCRYPT_MAX_BYTES`).
- [CFG] `password.min_length` tối đa 64 ký tự: 64 ký tự có dấu có thể vượt 72 byte, khi đó người dùng nhận lỗi "quá dài" — hiếm, chấp nhận.
- Các quyết định nghiệp vụ chốt cùng task đăng ký (2026-10-06), ghi ở contract identity: vượt quota BR-TK-07 lúc đăng ký thì từ chối cả đăng ký (400 BR-TK-07); BR-TK-04 "gửi thất bại báo lỗi" theo giả định A8 (201 khi đã ghi outbox, ST20 thử lại).
- `notification_outbox.payload` giữ mã OTP dạng rõ (`ma_otp`) tới khi ST20 gửi, vì worker cần mã để dựng email. ST20 phải xóa / che `ma_otp` sau khi gửi xong — nợ D003 (`docs/dept/INDEX.md`).
