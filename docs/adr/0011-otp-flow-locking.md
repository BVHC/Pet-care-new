# ADR-0011: Khóa dòng `accounts` cho mọi luồng OTP

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-07
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-05 (sinh mã mới thì mã cũ mất hiệu lực ngay), BR-TK-06 (sai tối đa 5 lần [CFG]), BR-TK-07 (cách nhau ≥ 60 s, tối đa 5 mã/giờ/email [CFG]), BR-TK-08 (ST02 xóa tài khoản `PENDING`).
  - `docs/03-state-machines.md` Tài khoản#2 (xác thực OTP), #3 (ST02).
  - `docs/convention/backend/07-transaction-management.md` §7.3 (khóa đồng thời phải có ADR trước khi cài); `04-exception-handling.md` §4.3 (lỗi khóa → 409).
  - ADR-0008 (`FOR UPDATE SKIP LOCKED` cho job), ADR-0010 (`noRollbackFor` cho mã bị từ chối). Nợ D005 (trả bởi ADR này), D002.
- **Triển khai:**
  - `BE/src/main/java/com/petcare/module/identity/repository/AccountRepository.java` (`findByEmailForUpdate`, `@Lock(PESSIMISTIC_WRITE)`; `findByEmail` không khóa bị xóa).
  - `BE/src/main/java/com/petcare/module/identity/service/RegistrationService.java` (`lockPendingAccount`, dùng ở `verifyAccount` và `resendRegistrationOtp`).
  - Test (tiêu chí → test), mỗi test đã fail khi bỏ `@Lock` (mutation 2026-10-07):
    - Hai lần gửi lại song song → đúng 1 mã mới, request thua nhận BR-TK-07: `RegistrationResendIT.concurrentResendsIssueExactlyOneCode`.
    - Gửi lại trong lúc verify đúng mã → chờ verify commit rồi 404, không sinh mã: `RegistrationResendIT.resendWaitsForConcurrentVerifyAndGetsNotFound`.
    - Hai lần nhập sai song song → `failed_attempts = 2`: `RegistrationVerificationIT.concurrentWrongCodesAreBothCounted`.
    - Request xác thực không bị chặn bởi dòng `accounts` đang khóa (có sẵn): `AuthenticationIT.lastSeenUpdateNeverBlocksRequestWhenAccountRowIsLocked`.

## Bối cảnh

Các luồng OTP đọc rồi ghi `otp_tokens` dựa trên phép đếm, DB không có constraint nào chặn:
- **resend × resend:** BR-TK-07 là phép đếm (`findTop…`, `count…`). Hai request cùng đọc trước khi bên kia commit → cùng vượt quota, cùng INSERT → 2 mã còn hiệu lực (trái BR-TK-05).
- **verify × verify (sai mã):** cùng đọc `failed_attempts = n`, cùng ghi `n + 1` → mất một lần đếm, đoán được quá BR-TK-06 (nợ D005).
- **resend × verify:** `OtpToken` không có `@DynamicUpdate`, Hibernate UPDATE mọi cột → verify ghi đè `invalidated_at` mà resend vừa đặt, mã đã bị thay "sống lại".
- **verify × ST02** (khi có): ST02 xóa tài khoản trong lúc verify chạy.

Convention 07 §7.3 yêu cầu chốt chiến lược khóa bằng ADR trước khi cài.

## Quyết định

1. **Mọi use case OTP mở đầu bằng khóa dòng `accounts` của tài khoản sở hữu luồng**, trước mọi đọc/ghi `otp_tokens`. Luồng public theo email dùng `AccountRepository.findByEmailForUpdate` (`@Lock(PESSIMISTIC_WRITE)`). Với Hibernate 6.6.53 (Spring Boot 3.5.15) trên PostgreSQL, câu này là `SELECT … FOR UPDATE` (dialect không dùng `FOR NO KEY UPDATE`).
2. **Thứ tự khóa chung: `accounts` → `otp_tokens`** (→ bảng của module khác nếu use case gọi tiếp, ví dụ `customers` ở Tài khoản#2). Luồng OTP chỉ khóa một dòng `accounts`, nên không deadlock giữa chúng.
3. **Không đặt lock timeout.** Transaction OTP ngắn (một lần BCrypt, cỡ 100 ms). Lỗi khóa của DB (deadlock, hoặc timeout nếu sau này đặt) → `PessimisticLockingFailureException` → 409 `CONCURRENCY_CONFLICT` (`GlobalExceptionHandler`, đã có). Request thua trong trường hợp thường **không** nhận 409: nó chờ khóa rồi chạy lại các kiểm tra trên dữ liệu đã commit, nên nhận đúng mã rule (BR-TK-07, 404…) — convention 06 L12.
4. **ST02 (khi cài) dùng `FOR UPDATE SKIP LOCKED` trên `accounts`** như ADR-0008: bỏ qua tài khoản đang verify/resend, xử lý ở lượt sau.
5. **Luồng sau** (UC04 quên/đặt lại mật khẩu, UC07 liên kết hồ sơ, UC08/UC22 đổi email hộ) khóa dòng `accounts` của tài khoản thực hiện luồng, theo cùng thứ tự.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| Optimistic `@Version` trên `accounts` / `otp_tokens` | Double-click và hai tab là chuyện thường → 409, client phải tự thử lại; quota BR-TK-07 đọc nhiều dòng `otp_tokens` nên version một dòng không bảo vệ được phép đếm |
| Chỉ `UPDATE otp_tokens SET failed_attempts = failed_attempts + 1 … RETURNING` | Sửa được bộ đếm (D005) nhưng không sửa quota resend và resend × verify |
| Advisory lock theo hash email | Cơ chế mới, chưa cần khi mỗi email ứng đúng một tài khoản (`uq_accounts_email`); để dành cho trường hợp ở *Hệ quả* |
| `SERIALIZABLE` | Phải bắt và thử lại lỗi serialization ở mọi nơi; hệ thống không có cơ chế retry |
| Khóa dòng `otp_tokens` | Lúc resend có thể chưa có dòng nào để khóa; quota đọc nhiều dòng |

## Hệ quả

- Nợ D005 đã trả; `verifyAccount` và `resendRegistrationOtp` cùng email chạy tuần tự.
- `FOR UPDATE` chặn `FOR KEY SHARE` của transaction khác khi INSERT dòng con có FK trỏ tới tài khoản đó (`sessions`, `otp_tokens`, `customers.account_id`). Với tài khoản `PENDING` không có ai làm vậy (BR-TK-08 cấm đăng nhập). Với luồng sau trên tài khoản `ACTIVE` (quên mật khẩu ↔ đăng nhập tạo phiên), hai bên chờ nhau tối đa một transaction ngắn, không deadlock vì cùng khóa `accounts` trước.
- SELECT thường (xác thực mỗi request) không bị chặn (MVCC); `touchLastSeen` dùng `SKIP LOCKED`.
- **Giới hạn đã biết:** quota BR-TK-07 tính theo *email nhận*, còn khóa theo *tài khoản*. Khi hai tài khoản khác nhau cùng xin OTP tới một email (đổi email sang cùng một email mới, liên kết tới cùng một hồ sơ tại quầy), quota có thể bị vượt. Không xảy ra ở đăng ký. Xử lý khi làm UC07/UC08/UC22 (advisory lock theo email, ADR mới).
- Bộ đếm sai đăng nhập BR-TK-09 (`accounts.failed_login_count`) **không** thuộc ADR này; quyết định khi làm đăng nhập (UC03).
