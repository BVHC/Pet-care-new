# ADR-0025: OTP liên kết hồ sơ (`LINK_PROFILE`) — gắn đúng tài khoản và hồ sơ, hủy mã theo tài khoản, bộ đếm sai

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-09
- **Bổ sung:** ADR-0011 (khóa dòng `accounts` cho luồng OTP) và ADR-0023 (biến thể BCrypt ngoài khóa) cho mục đích `LINK_PROFILE`.
- **Liên quan:**
  - BR-TK-04 (OTP liên kết gửi tới email ghi trong hồ sơ tại quầy), BR-TK-05, 06, 07, BR-TK-19, BR-KH-01, 10 (`customers.email`, SĐT không duy nhất); UC07.
  - `docs/05-erd.md` `otp_tokens` (CHECK `ck_otp_tokens_link_customer`), §13 mục 15; identity-v1 #12–13 (`LinkOtpRequest {customerId}`, `LinkConfirmRequest {customerId, code}`).
  - ADR-0009, 0010 (sai mã vẫn commit bộ đếm), 0011, 0012 (luồng HIGH), 0018 (index FK), 0023. Nợ D012, D013.
- **Triển khai (task 08 phần 0):**
  - `identity/entity/OtpToken` (constructor có `customerId`), `identity/repository/OtpTokenRepository` (`findTop…AndCustomerId…`, `invalidateActiveForAccount`), `identity/service/OtpService` (overload `issuePrepared`, `findActive`, `settleCheckedOtp` có `customerId`; guard mục đích ⇔ `customerId`).
  - `V9__otp_profile_link.sql`: mẫu `OTP_PROFILE_LINK`, index `ix_otp_tokens_customer_id`. Số V9 đã chốt với BE-2 (2026-10-09).
  - Test: `OtpServiceTest` (phần LINK_PROFILE), `module/identity/OtpLinkProfileIT`, `SchemaMigrationIT` (mẫu, `foreignKeysToCustomersWithoutUsableIndexAreExactlyDebtD013`).
  - Endpoint `/me/link/otp`, `/me/link/confirm` thuộc phần 1.

## Bối cảnh

Mã liên kết phải chứng minh "người giữ email của **hồ sơ C** đồng ý cho **tài khoản X** liên kết **hồ sơ C**". `OtpService` cũ chỉ nhận `(accountId, purpose, email)` và không ghi được dòng `LINK_PROFILE` (CHECK bắt `customer_id`). `customers.email` không duy nhất, nên lọc theo email không phân biệt được hai hồ sơ cùng email. Mã cũ bị hủy theo `(email, purpose)`, tức là cắt ngang mọi tài khoản: tài khoản Y khai trùng SĐT có thể liên tục hủy mã của X. Cuối cùng, `noRollbackFor` (ADR-0010) chỉ là điều kiện cần cho việc bộ đếm sai được lưu đúng.

## Quyết định

1. **Biến thể ADR-0023** cho `LINK_PROFILE` (`prepare` → `issuePrepared`, `findActive` → `settleCheckedOtp`): BCrypt chạy ngoài khóa dòng `accounts`. Các overload thêm `Long customerId`; bản cũ gọi overload với `null`, caller hiện có không đổi.
2. **Mã gắn ba thành phần**: dòng `otp_tokens` lưu `account_id = X`, `customer_id = C`, `target_email` = email hồ sơ C lúc gửi. `findActive` / `settleCheckedOtp` lọc theo cả bốn `(account_id, purpose, target_email, customer_id)`. Mã của (X, A) không dùng được cho (X, B) hay (Y, A); thử ở hồ sơ khác không đụng tới bộ đếm của mã kia; hồ sơ đổi email thì mã cũ không còn khớp.
3. **Guard** `(purpose == LINK_PROFILE) ⇔ (customerId != null)` ở mọi method công khai của `OtpService`, ném `IllegalArgumentException` (lỗi lập trình, 500) **trước mọi lệnh đọc / ghi** — cùng điều kiện với CHECK DB nhưng không chờ tới lúc flush.
4. **Hủy mã cũ theo tài khoản** (người dùng chốt 2026-10-09): mã `LINK_PROFILE` mới hủy mọi mã `LINK_PROFILE` còn hiệu lực **của chính tài khoản đó** (mọi hồ sơ) — mỗi tài khoản tối đa một mã liên kết đang sống; tài khoản khác xin mã cho cùng hồ sơ không hủy được mã này. `REGISTER` / `RESET_PASSWORD` giữ cách hủy theo `(email, purpose)`. Quota BR-TK-07 vẫn theo email nhận (rule ghi vậy).
5. **Mẫu `OTP_PROFILE_LINK`** (EMAIL, luồng HIGH): biến `ma_otp`, `thoi_han_phut`, `email_tai_khoan`; bắt buộc `ma_otp`, `email_tai_khoan` — chủ hồ sơ thấy tài khoản nào đang xin liên kết (hạn chế (1) của BR-TK-19).
6. **Index `ix_otp_tokens_customer_id`** (partial `WHERE customer_id IS NOT NULL`): xóa hồ sơ online (BR-TK-19, ST02) kiểm FK ở cột này. Các FK khác trỏ tới `customers` là bảng của BE-2: nợ D013.
7. **Bộ đếm sai được lưu đúng — checklist bắt buộc** cho mọi use case dùng `settleCheckedOtp` với `LINK_PROFILE`:

| # | Điều kiện | Ai | Chứng minh |
|---|---|---|---|
| 1 | Mọi proxy `@Transactional` mà `OtpRejectedException` đi qua có `noRollbackFor`: overload có `customerId` **và** method transaction của use case | `OtpService` (đã có) · use case phần 1 | `OtpLinkProfileIT.wrongCodeCommitsWithoutRollbackOnly`; IT endpoint + mutation ở phần 1 |
| 2 | Exception ném sau khi ghi bộ đếm, trực tiếp trong method có `noRollbackFor` | `OtpService` | `OtpServiceTest` |
| 3 | `settleCheckedOtp` gọi **trước mọi lệnh ghi khác**; kiểm trạng thái hồ sơ (BR-TK-19) **sau** settle (mã đúng mà hồ sơ không hợp lệ → rollback, mã dùng lại được — khuôn BR-TK-03 của ADR-0023) | phần 1 | IT phần 1 |
| 4 | Khóa dòng `accounts` của X trước khi settle (ADR-0011); nhờ quyết định 4, mọi lệnh ghi lên mã của X đều dưới khóa của X | phần 1 (`findByIdForUpdate`) | `OtpLinkProfileIT.concurrentWrongCodesAreBothCounted`; lại qua HTTP ở phần 1 |
| 5 | Dưới khóa, mã mới nhất vẫn là `otpId` đã so BCrypt, còn hạn, đúng `customer_id`; nếu không → BR-TK-05 không đếm | `OtpService` | `OtpLinkProfileIT.codeReplacedBetweenReadAndLockIsNotCounted` |
| 6 | Lần sai thứ `otp.max_failed_attempts` [CFG] đặt `invalidated_at` trong cùng lệnh ghi được commit | `OtpToken` | `OtpLinkProfileIT.fifthWrongCodeCancelsItAndTheRightCodeIsThenRejected` |
| 7 | Không bắt rồi nuốt exception đi qua proxy `@Transactional` khác bên trong transaction; nuốt thì ở facade sau khi transaction kết thúc (convention 07) | phần 1 | Review + IT phần 1 |

8. **Nghĩa vụ phần 1 (endpoint)**: thứ tự khóa `accounts → customers`; dưới khóa kiểm lại hồ sơ C còn `COUNTER`, chưa liên kết (`account_id IS NULL`), `email = target_email`, hồ sơ online của X chưa phát sinh dữ liệu — sai thì BR-TK-19 và rollback. Cần method khóa + liên kết trên `CustomerApi` (BE-2); nếu chưa có thì mở nợ khi làm phần 1.

## Lý do và phương án bị loại

| Phương án | Vì sao không chọn |
|---|---|
| `issueOtp` / `consumeOtp` (BCrypt trong khóa) | Giữ khóa dòng `accounts` và connection trong lúc BCrypt; ADR-0022, 0023 đã chọn đưa BCrypt ra ngoài |
| Chỉ lọc theo `account_id` + email, không theo `customer_id` | Hai hồ sơ tại quầy cùng email: mã gửi cho A liên kết được B |
| Hủy mã theo `(email, purpose)` như REGISTER / RESET | Tài khoản khác khai trùng SĐT liên tục hủy mã của chủ hồ sơ; và ghi vào mã của X mà không giữ khóa của X (điều kiện 4 không còn đủ) |
| Hủy theo `(tài khoản, hồ sơ)` | Một tài khoản giữ nhiều mã liên kết cùng lúc mà không có lý do nghiệp vụ (mỗi khách chỉ liên kết một hồ sơ) |
| Để CHECK DB bắt lỗi thiếu `customerId` | Lỗi chỉ hiện lúc flush, sau khi đã hủy mã cũ trong cùng transaction |

## Hệ quả

- Phần 0 không có endpoint mới; các luồng REGISTER / RESET_PASSWORD giữ nguyên hành vi (hủy theo email).
- **Hạn chế còn lại (nợ dự kiến phần 1):** quota BR-TK-07 tính theo email nhận, nên tài khoản Y khai trùng SĐT có thể dùng hết 5 mã/giờ của email hồ sơ tại quầy, khiến chủ hồ sơ tạm thời không nhận được OTP nào (cả quên mật khẩu nếu cùng email). Quyết định 4 chặn hủy mã chéo nhưng không chặn việc tiêu quota; cần ADR quota phụ theo tài khoản người xin (ngoài đặc tả) hoặc hỏi lại đặc tả.
- **Đã kiểm 2026-10-09:** `mvn clean verify` xanh (778 unit + 364 IT; `OtpLinkProfileIT` 10 ca). Mutation (đã hoàn tác, mỗi lần một chỗ): bỏ `noRollbackFor` khỏi overload `settleCheckedOtp` có `customerId` → `wrongCodeCommitsWithoutRollbackOnly` đỏ với `UnexpectedRollbackException`; hủy mã theo email thay vì tài khoản → `newCodeInvalidatesOnlyTheSameAccountsCodes` đỏ; bỏ lọc `customer_id` → `issuedCodeIsBoundToAccountAndProfile`, `wrongAttemptForOtherProfileOrAccountDoesNotTouchTheCode` đỏ.
- `otp_tokens` thêm loại dòng không được dọn (nợ D012); index V9 lớn theo bảng.
