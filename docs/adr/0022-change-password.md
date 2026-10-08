# ADR-0022: Đổi mật khẩu — bộ đếm dùng chung với đăng nhập, từ chối khi đang khóa tạm, BCrypt ngoài transaction

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-08
- **Supersedes:** — (thực hiện mục *Hệ quả* "Đổi mật khẩu (BR-TK-14) gọi `Account.recordFailedLogin`; cách trả 400 mà vẫn giữ bộ đếm chốt khi cài phần đó" của ADR-0019)
- **Liên quan:**
  - `docs/01-business-operations.md` UC05; `docs/02-business-rules.md` BR-TK-03 (chính sách, không trùng mật khẩu hiện tại), BR-TK-09 (khóa tạm, báo giờ thử lại, email cảnh báo), BR-TK-11, BR-TK-12/13 (quên mật khẩu được khi khóa tạm, đặt lại gỡ khóa), BR-TK-14 (nhập đúng mật khẩu hiện tại, sai tính vào bộ đếm BR-TK-09, đổi xong đăng xuất phiên khác), BR-TK-17 (bắt đổi lần đầu), BR-QT-15.
  - `docs/api/identity-v1.md` `POST /me/password` (204; 400 BR-TK-03/09/11/14; 401).
  - ADR-0003 (phiên, BR-TK-17 đọc từ DB mỗi request), ADR-0009 (BCrypt, 72 byte), ADR-0010 (`noRollbackFor`), ADR-0019 (bộ đếm, khóa tạm, BCrypt ngoài transaction, audit `ACCOUNT_TEMPORARILY_LOCKED`), ADR-0020 (OSIV tắt), ADR-0021 (thứ tự khóa `accounts → sessions`).
  - Convention 04 §4.2, 06, 07 L10, 08 §8.3.
- **Triển khai:** `identity/controller/MeController.changePassword`, `identity/dto/ChangePasswordRequest`, `identity/service/{ChangePasswordService, ChangePasswordAttemptService, NewPasswordHasher, PasswordPolicy}`, `identity/exception/CurrentPasswordMismatchException`, `identity/repository/{AccountRepository.findPasswordStateById, AccountPasswordState}`, `Account.changePassword`, `IdentityAuditActions.ACCOUNT_TEMPORARILY_LOCKED`. `RegistrationService` gọi `PasswordPolicy` (tách nguyên văn, không đổi hành vi). Bằng chứng: `AccountTest`, `PasswordPolicyTest`, `NewPasswordHasherTest`, `ChangePasswordServiceTest`, `ChangePasswordAttemptServiceTest`, `ChangePasswordIT` (35 ca); `RegistrationServiceTest`, `LoginIT`, `LogoutIT` giữ xanh.

## Bối cảnh

`MustChangePasswordInterceptor` (BR-TK-17) đã chặn mọi API trừ `GET /api/me`, `POST /api/me/password`, `POST /api/auth/logout`, nhưng chưa có endpoint đổi mật khẩu — tài khoản `must_change_password = true` bị chặn vĩnh viễn. BR-TK-14 trả lỗi khi sai mật khẩu hiện tại nhưng lần sai phải được **tính** vào bộ đếm BR-TK-09 — cùng bẫy rollback của ADR-0010 / ADR-0019. Đặc tả không nói: đang khóa tạm thì đổi được không, đổi xong có gửi email / xóa bộ đếm không, thứ tự kiểm BR-TK-14 và BR-TK-03. Người dùng chốt các điểm này ngày 2026-10-08.

## Quyết định

1. **BCrypt ngoài transaction**, cùng khuôn `LoginService` / `LoginAttemptService` (ADR-0019 mục 4): `ChangePasswordService` (không `@Transactional`) đọc `(password_hash, locked_until)` bằng projection trong transaction readOnly ngắn, so BCrypt mật khẩu hiện tại và mã hóa mật khẩu mới khi không giữ connection, rồi gọi `ChangePasswordAttemptService.apply` (một transaction, khóa dòng `findByIdForUpdate` — Hibernate sinh `FOR NO KEY UPDATE`).
2. **Đang khóa tạm (`now < locked_until`) → 400 `BR-TK-09`** kèm giờ thử lại (cùng message với đăng nhập), **không chạy BCrypt, không đếm**, với cả mật khẩu đúng lẫn sai. Lý do: người cầm phiên bị đánh cắp không được đoán mật khẩu hiện tại vô hạn trong lúc khóa (đăng nhập "sai lúc khóa không đếm" sẽ thành lỗ hổng ở đây). Chủ tài khoản dùng Quên mật khẩu (BR-TK-12 cho phép khi khóa tạm; BR-TK-13 gỡ khóa). Kiểm hai lần: ở bước đọc (không tốn BCrypt) và lại dưới khóa dòng (đăng nhập song song vừa khóa).
3. **Thứ tự kiểm:** 401 (filter) → hình thức (`@NotBlank`) → BR-TK-11 (dưới khóa) → BR-TK-09 → mật khẩu hiện tại (BR-TK-14) → mật khẩu mới (BR-TK-03) → ghi. BR-TK-14 trước BR-TK-03 để **mọi** lần nhập sai mật khẩu hiện tại đều được đếm, kể cả khi mật khẩu mới cũng sai.
4. **Sai mật khẩu hiện tại → `CurrentPasswordMismatchException extends BusinessRuleViolationException`** (400 `BR-TK-14`, tiêu chí 3 convention 04 §4.2); `apply` khai báo `@Transactional(noRollbackFor = CurrentPasswordMismatchException.class)`. Exception chỉ ném trực tiếp trong `apply`, sau mọi lệnh ghi. Bộ đếm là `Account.recordFailedLogin` của đăng nhập — **dùng chung** cửa sổ, ngưỡng, thời gian khóa [CFG]. Mật khẩu hiện tại &gt; 72 byte = sai (ADR-0019 mục 7).
5. **Lần sai chạm ngưỡng:** `locked_until` chốt vào dòng; email `LOGIN_LOCKED_WARNING` cùng payload với đăng nhập (`recipientAccountId = null`, như đăng nhập); audit `ACCOUNT_TEMPORARILY_LOCKED` bằng `record` (`before`/`after` = `{failedLoginCount, lockedUntil}`, actor lấy từ principal); message 400 BR-TK-14 kèm giờ mở khóa. Phiên đang mở **không** bị hủy (`locked_until` chỉ chặn đăng nhập — ADR-0019 *Hệ quả*). Lần sai chưa chạm ngưỡng và lần thành công không audit.
6. **Mật khẩu mới:** `NewPasswordHasher` gom `PasswordPolicy.check` (BR-TK-03, message giữ nguyên của đăng ký) → `newPassword.equals(currentPassword)` → `encode`, để không đường nào gọi `encode` khi chưa qua chính sách (BCrypt ném lỗi → 500 với chuỗi &gt; 72 byte). So chuỗi là đúng cho "trùng mật khẩu hiện tại" vì chỉ chạy khi mật khẩu hiện tại đã khớp hash và cả hai ≤ 72 byte — không cần BCrypt lần thứ ba.
7. **Thành công:** `Account.changePassword(hash, now)` — hash mới, `must_change_password = false`, xóa bộ đếm sai như đăng nhập thành công (chỉ có thể xóa `locked_until` đã hết hạn, vì đang khóa thì đã bị từ chối ở mục 2) — rồi `SessionService.revokeOthers` (phiên đang dùng giữ nguyên). Thứ tự khóa `accounts → sessions` (ADR-0021): dòng `accounts` khóa ở `findByIdForUpdate`, thay đổi entity flush trước câu hủy phiên (`flushAutomatically`). **Không gửi email** (BR-TK-14 không yêu cầu; mẫu `PASSWORD_CHANGED` dành cho đặt lại). 204 không body.
8. **Mật khẩu vừa đổi song song** (hash dưới khóa khác hash đã đọc): so lại mật khẩu hiện tại với hash mới dưới khóa (như đăng nhập); nếu giờ mới đúng thì chạy `NewPasswordHasher` dưới khóa (hiếm). Không ghi đè hash vừa đổi bằng kết quả cũ.
9. **Guard BR-TK-11 dưới khóa:** tài khoản `is_locked` hoặc không `ACTIVE` lúc khóa dòng (bị khóa / vô hiệu hóa sau khi filter cho request qua) → 400 `BR-TK-11`, rollback sạch. Filter đã trả 401 cho trường hợp thông thường.
10. **Hình thức:** `currentPassword`, `newPassword` chỉ `@NotBlank` (message tiếng Việt); không `@Size` (đếm ký tự, không đếm byte). Request sai hình thức (`VALIDATION_FAILED`, `MALFORMED_REQUEST`, 415, 405) không đếm, không audit — cùng nguyên tắc với đăng nhập. Trường lạ bị bỏ qua (không đổi email — BR-TK-15).

## Phương án đã cân nhắc

| Phương án | Vì sao không chọn |
|---|---|
| Một transaction, BCrypt trong lúc giữ khóa dòng | Giữ connection + khóa ~160 ms mỗi request; ai tự đăng ký cũng spam được, cạn pool 10 connection như ADR-0019 đã phân tích |
| Đang khóa tạm vẫn cho đổi khi đúng, sai không đếm (như đăng nhập) | Người cầm phiên đoán mật khẩu hiện tại vô hạn trong lúc khóa |
| Đang khóa tạm, sai vẫn đếm và gia hạn khóa | Khác hành vi đăng nhập (ADR-0019 mục 2), không cần khi đã chặn hẳn |
| Kiểm BR-TK-03 trước BR-TK-14 | Lần sai mật khẩu hiện tại kèm mật khẩu mới yếu không được đếm — trái chữ BR-TK-14 |
| Gửi email sau khi đổi (mẫu mới, V9) | Ngoài đặc tả; cần giành số migration với BE-2 |
| Chuyển `fitsBcrypt` / `formatUnlockTime` khỏi `LoginAttemptService` | Phải sửa `LoginService` và 2 file test đăng nhập; gọi tại chỗ (cùng package) là đủ |

## Hệ quả

- Bộ đếm dùng chung: sai ở đổi mật khẩu cộng với sai ở đăng nhập trong cùng cửa sổ (`ChangePasswordIT.wrongCurrentPasswordCountsTowardLoginLock`); không thể dùng đổi mật khẩu để gỡ khóa đăng nhập còn hiệu lực (`cannotBypassLoginLockByChangingPassword`).
- `PasswordPolicy`, `NewPasswordHasher` dùng lại cho đặt lại mật khẩu (UC04) và đổi mật khẩu lần đầu của nhân viên.
- Không migration, không [CFG] mới, không handler mới (`GlobalExceptionHandler.handlePlatform` map theo `errorCode`).
- Convention 04 §4.2 (ví dụ), 07 L10 (ngoại lệ có chủ đích) cập nhật theo ADR này.
