# ADR-0019: Đăng nhập sai — đếm, khóa tạm (ST01), không lộ tài khoản, audit

- **Trạng thái:** Accepted (2026-10-08, task 07 phần 1 cài xong, `LoginIT` + `mvn clean verify` xanh)
- **Ngày:** 2026-10-08
- **Liên quan:**
  - `docs/02-business-rules.md` BR-TK-08 (tài khoản `PENDING`), BR-TK-09 (khóa tạm 15 phút sau 5 lần sai trong 15 phút [CFG]), BR-TK-10 (không tiết lộ tài khoản), BR-TK-11 (`LOCKED`/`DISABLED`), BR-TK-13 (đặt lại mật khẩu gỡ khóa tạm), BR-TK-14 (sai mật khẩu hiện tại tính vào bộ đếm BR-TK-09), BR-QT-13 (giá trị [CFG] chỉ áp dụng cho giao dịch tạo sau), BR-QT-15 (audit đăng nhập thành công và thất bại).
  - `docs/03-state-machines.md` Tài khoản (L34, L38–40: `locked_until` là trường phụ, không phải trạng thái; `PENDING`/`DISABLED`/`is_locked` không đăng nhập được).
  - `docs/api/identity-v1.md` `POST /auth/login` (L111): 400 BR-TK-08/09/11, 401 thông điệp chung.
  - Convention 04 §4.2 (L19–28), 07 §7.1 (L7, L9), 08 L39–47 và §8.3.
  - **Supersedes ADR-0001 mục 3** (phần ví dụ `LOGIN_FAILED` của `recordIndependently`): `LOGIN_FAILED` ở nhánh 401 ghi bằng `record`; nhánh 400 vẫn `recordIndependently` nhưng gọi sau khi transaction đăng nhập đã rollback (mục 6).
  - ADR-0001 (audit), 0002 (IP client), 0003 (phiên; *Hệ quả* "đăng nhập tự kiểm tra PENDING, `locked_until`, `is_locked`/DISABLED"), 0005 (path public ẩn danh), 0009 (BCrypt), 0010 (`noRollbackFor`), 0011 (khóa dòng `accounts`), **0020** (tắt OSIV — điều kiện để mục 4 đúng).
- **Triển khai (task 07):**
  - Phần 0 (2026-10-08): `V8__seed_notification_templates_login_password.sql` (mẫu `LOGIN_LOCKED_WARNING`) + `SchemaMigrationIT.seededNotificationTemplatesAreRestorableAndConsistent`, `customer/service/CustomerQueryApiPlaceholder` (nợ D010), convention 08 §8.3.
  - Phần 1 (2026-10-08): `identity/controller/AuthController.login`; `identity/service/LoginService` (không transaction: đọc, BCrypt, ghi audit nhánh 400 sau rollback), `identity/service/LoginAttemptService` (transaction: khóa dòng, guard, bộ đếm, phiên, audit, outbox), `identity/service/IdentityAuditActions`; `identity/exception/{InvalidCredentialsException, LoginRejectedException}`; `Account.{recordFailedLogin, recordSuccessfulLogin, isTemporarilyLocked}`; `AccountRepository.{findCredentialByEmail, findByIdForUpdate}` + `repository/AccountCredential`; `dto/{LoginRequest, LoginResponse, AccountSummary}`, `mapper/LoginMapper`. Test: `AccountTest`, `LoginServiceTest`, `LoginAttemptServiceTest`, `module/identity/LoginIT`.
  - Phần 3 (chưa làm): `ACCOUNT_TEMPORARILY_LOCKED` khi BR-TK-14 kích hoạt khóa.

## Bối cảnh

Đăng nhập (UC03) phải đếm lần sai và khóa tạm (BR-TK-09, ST01) trong khi trả 401 — mà Spring rollback transaction khi có `RuntimeException`, nên bộ đếm sẽ mất (cùng vấn đề ADR-0010 gặp với OTP). Cùng lúc phải không cho biết email có tồn tại (BR-TK-10), dù hợp đồng HTTP yêu cầu báo riêng BR-TK-08/09/11. Hai request sai song song không được mất lần đếm, nhưng phép so BCrypt mất ~80 ms nên không thể giữ khóa dòng (và connection) trong lúc so mà không làm request cùng email xếp hàng và cạn pool (10 connection). Audit đăng nhập thất bại là bắt buộc (BR-QT-15), mà đây lại là endpoint public dễ bị dò hàng loạt. Đặc tả không nói: lần sai trong lúc đang khóa có tính không, đăng nhập đúng nhưng bị chặn có reset bộ đếm không.

## Quyết định

1. **Thứ tự kiểm tra.** Email `strip().toLowerCase(ROOT)` (như `RegistrationService`):
   1. Đọc **không khóa** `(id, password_hash)` theo email bằng projection, không nạp entity (mục 4).
   2. Không có tài khoản → so BCrypt với hash giả (mục 5) → 401.
   3. Có → so BCrypt → khóa dòng `accounts` theo `id` (`FOR UPDATE`) → đọc trạng thái dưới khóa:
      - Không còn dòng (ST02 vừa xóa) → như email lạ, 401.
      - `locked_until > now`: mật khẩu đúng → 400 **BR-TK-09** kèm giờ thử lại; mật khẩu sai → 401, **không đếm**.
      - Mật khẩu sai → cộng bộ đếm (mục 2) → 401.
      - Mật khẩu đúng: `PENDING` → 400 **BR-TK-08**; `is_locked` → 400 **BR-TK-11**; `DISABLED` → 400 **BR-TK-11**; còn lại → reset bộ đếm, mở phiên (`SessionService.open`), audit `LOGIN_SUCCEEDED`. Nhánh 400 ném **trước** mọi lệnh ghi.
   - Mọi 401 cùng thông điệp "Email hoặc mật khẩu không đúng" (BR-TK-10). BR-TK-08/09/11 chỉ trả khi mật khẩu đúng (người dùng chốt 2026-10-08), nên người không biết mật khẩu không phân biệt được email có tồn tại, đang khóa hay chưa xác thực. Tổ hợp `PENDING` + đang khóa tạm → BR-TK-09.
   - Không phải guard: `must_change_password` (BR-TK-17 — phải đăng nhập được mới đổi được; `account.mustChangePassword` trả trong response), `link_decision_pending` (BR-TK-19 — trả `linkDecisionPending`, khách đọc qua `CustomerQueryApi`), chi nhánh `DRAFT` (BR-QT-03).

2. **Bộ đếm** trên các cột có sẵn `failed_login_count`, `first_failed_login_at`, `locked_until`; cửa sổ cố định tính từ lần sai đầu:
   - `first_failed_login_at` null hoặc `now − first ≥ login.failed_window_minutes` [CFG] → `count = 1`, `first = now`; ngược lại `count + 1`.
   - `count ≥ login.max_failed_attempts` [CFG] → `locked_until = now + login.lock_minutes` [CFG] (chốt vào dòng, BR-QT-13), `count = 0`, `first = null`, `NotificationApi.enqueue(LOGIN_LOCKED_WARNING)` tới email tài khoản (không truyền `recipientAccountId` vì tài khoản có thể `PENDING`), payload `so_lan_sai`, `thoi_diem_mo_khoa`. Mỗi lần khóa đúng một email.
   - Khóa còn hiệu lực khi `now < locked_until`; đúng mốc `locked_until` là đã hết khóa.
   - Lần sai **trong lúc đang khóa không đếm**: không ai kéo dài khóa vô hạn được; số lần đoán tối đa ≈ `max_failed_attempts` mỗi (`failed_window_minutes` + `lock_minutes`) cho một email.
   - Đếm như nhau cho mọi `status` và cả khi `is_locked`, để phản hồi khi sai mật khẩu không khác nhau.
   - Chỉ đăng nhập **thành công** mới reset bộ đếm (và xóa `locked_until` đã hết hạn). Mật khẩu đúng nhưng bị BR-TK-08/09/11 không ghi gì.
   - Giờ mở khóa hiển thị (message BR-TK-09 và email) **làm tròn lên phút**, `HH:mm dd/MM/yyyy` giờ Việt Nam, để người dùng không thử lại sớm hơn giờ mở thật.
   - Logic đặt ở entity: `Account.recordFailedLogin(now, window, maxAttempts, lockDuration)` trả về "vừa khóa", để BR-TK-14 (đổi mật khẩu) dùng lại.

3. **Lưu bộ đếm dù trả 401** — cùng cách ADR-0010: lớp `identity/exception/InvalidCredentialsException extends PlatformException` với `ErrorCode.UNAUTHENTICATED`; method có transaction khai báo `@Transactional(noRollbackFor = InvalidCredentialsException.class)`.
   - Tiêu chí convention 04 §4.2: **2** (HTTP 401, khác cả 5 exception chuẩn) và **3** (không được rollback). Kế thừa thẳng `PlatformException` vì không lớp chuẩn nào mang 401; `GlobalExceptionHandler.handlePlatform` map theo `errorCode()` nên không sửa platform, không thêm handler.
   - Bất biến: chỉ ném trực tiếp trong `LoginAttemptService` (method có `noRollbackFor`), sau khi các lệnh ghi của nhánh đó (bộ đếm / `locked_until`, outbox cảnh báo, audit) đã xong. BR-TK-08/09/11 là `LoginRejectedException` (kế thừa `BusinessRuleViolationException`), không nằm trong `noRollbackFor`: rollback sạch vì chưa ghi gì.

4. **BCrypt ngoài transaction, khóa ngắn sau BCrypt.** Use case tách hai bean (gọi qua proxy, không self-invocation):
   - `LoginService` — **không** `@Transactional`: TX-1 `findCredential` (`readOnly`, một SELECT projection) → **so BCrypt khi không giữ connection** → TX-2 `rejectUnknownEmail` hoặc TX-3 `login`.
   - `LoginAttemptService.login` — TX-3: `SELECT … FOR UPDATE` theo `id`, đọc lại trạng thái dưới khóa (không dùng dữ liệu của TX-1), ghi bộ đếm, mở phiên, audit, outbox. Khóa giữ vài câu SQL.
   - Đúng nhờ **ADR-0020** (tắt OSIV): Spring đặt `DELAYED_ACQUISITION_AND_HOLD`, nên khi OSIV bật, connection mượn ở TX-1 bị giữ tới hết request — kể cả lúc so BCrypt (`LoginIT.bcryptRunsWithoutHoldingConnection` đỏ khi OSIV bật). Bị dội request thì tốn CPU nhưng không cạn pool.
   - Bước đọc dùng projection (`AccountCredential`, JPQL constructor expression), không nạp entity: nếu entity đã nằm trong persistence context, truy vấn `@Lock` sau đó trả lại đúng instance cũ mà không ghi đè trạng thái → đọc bộ đếm cũ, mất lần đếm.
   - Hash đọc dưới khóa khác hash đã so (đặt lại / đổi mật khẩu chạy song song): so lại BCrypt với hash mới, dưới khóa (hiếm).
   - Chỉ khóa `accounts` (cộng INSERT `sessions`, `notification_outbox`, `audit_logs`), khớp thứ tự ADR-0011 (`accounts` → `otp_tokens`). Hai lần sai song song vẫn được đếm đủ vì cả hai cập nhật dưới khóa.
   - Lệch có chủ đích với convention 07 L7 ("transaction bao trọn use case"): mọi kiểm tra rule dẫn tới ghi vẫn nằm trọn trong TX-3; ngoài transaction chỉ có đọc và BCrypt (convention 07 §7.1 ghi ngoại lệ này).

5. **Hash giả** tính một lần lúc khởi động bằng chính bean `PasswordEncoder` (`encode` một chuỗi ngẫu nhiên) → cùng cost (ADR-0009), thời gian phản hồi email lạ xấp xỉ email có thật. Mọi nhánh chạy đúng một lần BCrypt.

6. **Audit** (BR-QT-15; người dùng chốt 2026-10-08):
   - **Cơ chế:** nhánh **401** (email lạ, sai mật khẩu, sai lúc đang khóa) → `AuditRecorder.record` trong cùng transaction — transaction vẫn commit nhờ `noRollbackFor`, không tốn connection thứ hai. Nhánh **400 BR-TK-08/09/11** (rollback; chỉ tới được khi biết đúng mật khẩu nên không spam được) → `recordIndependently`, **gọi ở `LoginService` sau khi TX-3 đã rollback** (khóa đã nhả, connection đã trả): `LoginAttemptService` ném `LoginRejectedException` mang sẵn `AuditEntry`. Gọi ngay trong TX-3 thì `REQUIRES_NEW` cần connection thứ hai trong lúc vẫn giữ connection 1 + khóa dòng: ≥ 10 request đúng mật khẩu cùng vào một tài khoản bị chặn kẹt pool 30 s (`connection-timeout`). `LoginIT.rejected400AuditIsWrittenAfterLoginTransactionEnded` (trigger tạm kiểm không còn phiên "idle in transaction" lúc ghi audit) chứng minh.
   - **Email lạ vẫn ghi** `LOGIN_FAILED`: `actor(null, email đã chuẩn hóa)`, không có `entity`, `reason = UNKNOWN_EMAIL`; IP tự lấy từ request (ADR-0002). Client không nhận thêm gì (401 giống hệt); chỉ ADMIN xem audit (BR-QT-16, UC11).
   - **`LOGIN_FAILED` của tài khoản có thật:** `actor(id, email)`, `entity("accounts", id)`, `reason` ∈ `BAD_CREDENTIALS`, `TEMPORARILY_LOCKED`, `PENDING`, `LOCKED`, `DISABLED`; `before`/`after` = `{failedLoginCount, lockedUntil}` (BR-QT-15 "giá trị trước và sau"). Mã ở `identity/service/IdentityAuditActions`.
   - **Khóa tạm không có mã riêng:** lần sai chạm ngưỡng hiện trong `LOGIN_FAILED` với `after.lockedUntil` khác null.
   - **Thành công:** `LOGIN_SUCCEEDED` (convention 08: `<ĐỐI_TƯỢNG>_<QUÁ_KHỨ>`), `record`, `actor(id, email)` (path public không có principal — ADR-0005), `entity("accounts", id)`, `after = {sessionId}`; lần này reset bộ đếm thì `before` = bộ đếm cũ và `after` thêm `failedLoginCount`, `lockedUntil`.
   - **Ngoài BR-QT-15 (người dùng chọn):** lần sai mật khẩu hiện tại ở đổi mật khẩu (BR-TK-14) **kích hoạt khóa tạm** → `ACCOUNT_TEMPORARILY_LOCKED`, `record`, `entity("accounts", id)`, `before`/`after` = `{failedLoginCount, lockedUntil}` — khóa sinh từ đổi mật khẩu không có dòng `LOGIN_FAILED` nào. Lần sai BR-TK-14 không kích hoạt khóa, đăng xuất, quên / đặt lại / đổi mật khẩu: không audit (đặc tả không yêu cầu).
   - Khóa snapshot tránh từ cấm của recorder (`password`, `otp`, `token`…): dùng `failedLoginCount`, `lockedUntil`, `sessionId`.

7. **Mật khẩu nhập vào > 72 byte = sai mật khẩu** (người dùng chốt 2026-10-08). Bytecode spring-security-crypto 6.5.11: `BCryptPasswordEncoder.matches` → `BCrypt.checkpw` → `hashpw(…, forCheck = true)` bỏ qua kiểm 72 byte và chỉ so 72 byte đầu, nên "mật khẩu 72 byte + đuôi bất kỳ" sẽ khớp (không ném lỗi như `encode`). `LoginService` không so với hash thật mà so với hash giả (giữ thời gian phản hồi) rồi đi nhánh sai mật khẩu: 401, cộng bộ đếm, audit `BAD_CREDENTIALS`. Không chặn bằng `@Size` vì annotation đếm ký tự chứ không đếm byte. Không tài khoản nào có mật khẩu > 72 byte (đăng ký chặn bằng BR-TK-03, ADR-0009).

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| `FOR UPDATE` cả luồng như ADR-0011 | So BCrypt trong lúc giữ khóa: request cùng email xếp hàng ~80 ms mỗi cái; bị dội thì giữ nhiều connection |
| Một method `@Transactional` bao cả BCrypt | Mỗi lần đăng nhập giữ 1 connection ~80 ms; ~10 request đồng thời cạn pool, mọi API khác chờ tới `connection-timeout` |
| Cộng bộ đếm bằng một câu `UPDATE … RETURNING` không khóa | Đúng về đồng thời nhưng đưa luật cửa sổ / ngưỡng vào SQL, không dùng lại được cho BR-TK-14 và khó test bằng unit |
| `BadCredentialsException` của Spring | Handler trả thông điệp cố định "Chưa đăng nhập hoặc phiên đăng nhập đã hết hạn" |
| Thêm base exception thứ 6 ở platform | Sửa platform và convention 04 cho một use case; lớp riêng ở identity đủ |
| Ghi bộ đếm bằng `REQUIRES_NEW` | Convention 07 L9 cấm trong nghiệp vụ |
| `recordIndependently` cho nhánh 400 ngay trong transaction đang giữ khóa | Cần connection thứ hai khi vẫn giữ connection 1 + khóa dòng: kẹt pool khi nhiều request đúng mật khẩu cùng vào một tài khoản bị chặn |
| Báo BR-TK-09/08/11 cả khi sai mật khẩu | Lộ email có tồn tại và trạng thái tài khoản (BR-TK-10) |
| Đếm cả lần sai lúc đang khóa | Kẻ dò kéo dài khóa của người khác vô hạn |
| Cửa sổ trượt bằng bảng log lần sai | Cần bảng mới ngoài erd; cột có sẵn chỉ hỗ trợ cửa sổ cố định |
| `recordIndependently` cho mọi `LOGIN_FAILED` (bảng convention 08 cũ) | Mỗi lần sai chiếm 2 connection cùng lúc; pool 10 → ~5 request dò song song là cạn pool, audit chờ `connection-timeout` rồi mất (ADR-0001 *Hệ quả*), mọi API khác cũng chờ |
| Không audit email lạ / chỉ log | Lệch BR-QT-15 ("đăng nhập thất bại" không loại trừ), mất dấu vết IP dò hàng loạt email |
| Để BCrypt so 72 byte đầu như mặc định | Chuỗi khác mật khẩu vẫn đăng nhập được |
| `@Size` cho mật khẩu ở `LoginRequest` | Đếm ký tự, không đếm byte (tiếng Việt 2–3 byte/ký tự); vẫn phải kiểm byte ở service |

## Hệ quả

- Đổi mật khẩu (BR-TK-14) gọi `Account.recordFailedLogin`; cách trả 400 mà vẫn giữ bộ đếm chốt khi cài phần đó.
- Đặt lại mật khẩu reset bộ đếm, xóa `locked_until` (BR-TK-13) và `must_change_password` (người dùng chốt 2026-10-08).
- `locked_until` không làm mất phiên đang mở (`SessionAuthenticator` không xét); BR-TK-09 chỉ chặn đăng nhập.
- Không giới hạn theo IP (ngoài đặc tả) — nợ D011. Email cảnh báo cũng gửi tới tài khoản `PENDING` (email chưa xác thực), tối đa một thư mỗi lần khóa.
- Audit email lạ: `audit_logs` không xóa được (trigger, BR-QT-16) nên lớn dần khi bị dò hàng loạt và giữ vĩnh viễn email gõ nhầm + IP của người không phải khách. Giảm bằng Bean Validation `@Email` + giới hạn độ dài ở request; `actor_email` cắt 255 ký tự. Ở dev gọi thẳng `:8080` thì client giả được IP (ADR-0002 *Hệ quả*).
- Convention 04 §4.2, 07 §7.1, 08 §8.3 sửa theo ADR này.
- `login.max_failed_attempts`, `login.failed_window_minutes` đọc mỗi lần thử nên áp dụng ngay khi ADMIN đổi; chỉ `locked_until` được chốt — cùng hạn chế BR-QT-13 đã ghi ở ADR-0010.
- Đăng nhập của tài khoản `CUSTOMER` đọc `linkDecisionPending` qua `CustomerQueryApi` (BE-2); tới khi có bản thật, placeholder ném lỗi → 500 và rollback (nợ D010). Nhân viên không bị ảnh hưởng.
- Request sai hình thức (Bean Validation, JSON hỏng, 405, 415) không tính là một lần đăng nhập: không đếm, không audit.
