# ADR-0096: Độ dài mã OTP trong response — `OtpSentResponse.codeLength`, `RegistrationResponse.otpCodeLength`

- **Trạng thái:** **Proposed (chờ BE-1 xác nhận)**
- **Ngày:** 2026-11-02
- **Liên quan:**
  - identity-v1 #1 `POST /auth/register`, #3 `POST /auth/register/resend-otp`, #6 `POST /auth/password/forgot`, #12 `POST /me/link/otp`, #23 `POST /staff/{id}/email-change`, #25 `POST /customers/{customerId}/account-email-change` (UC22, ADR-0097); schema `OtpSentResponse`, `RegistrationResponse`, mô tả `VerifyOtpRequest.code`.
  - BR-TK-04, 05, 07, 10; BR-QT-13 ([CFG] `otp.code_length`, 4–8, mặc định 6).
  - ADR-0004 (đọc [CFG]), ADR-0009 (OTP băm BCrypt), ADR-0023 (forgot luôn 202 cùng body, `prepare()` chạy mọi nhánh), ADR-0025 (OTP `LINK_PROFILE`), ADR-0027, ADR-0034, ADR-0040 (Swagger = hợp đồng), ADR-0097.
  - `docs/testcases/T49-fe.md` K25 và `docs/testcases/test-tay-ghep-api-theo-cum.md` L-20 (1/11: Hiến chọn phương án (a), BE trả độ dài); `docs/06-module-contracts.md` §6 (ghi chú 2/11 cho BE-1).
- **Triển khai:**
  - Người viết: FE-1 (Hiến) với Claude, **thay BE-1**; **người dùng (Hiến) cho phép sửa module `identity` của BE-1** cho việc này. BE-1 chưa xem. Các file dưới đây nằm trong module của BE-1.
  - Sửa (main): `identity/dto/{OtpSentResponse, RegistrationResponse}` (thêm trường `int` ở **cuối** record), `identity/dto/VerifyAccountRequest` (chỉ chữ mô tả `@Schema`: bỏ "6 chữ số"), `identity/mapper/{PasswordResetMapper, RegistrationMapper, LinkProfileMapper, StaffAdminMapper}`, `identity/service/PasswordResetService`, `identity/controller/AuthController` (một câu trong mô tả `forgot`). **Không đổi** `OtpService`, record `IssuedOtp` / `PreparedOtp`, `ConfigKey`.
  - Hợp đồng: `docs/api/generator/m_identity.py` (schema `OtpSentResponse.codeLength`, `RegistrationResponse.otpCodeLength`, chữ của `VerifyOtpRequest.code`, notes #6) → `identity-v1.{md,yaml}` sinh lại bằng `generate.py identity`. `docs/api/INDEX.md` không đổi (số endpoint không đổi bởi ADR này).
  - Test: 6 phương thức mới (5 unit, 1 IT) và khẳng định thêm trong 5 IT cũ (chi tiết ở *Kiểm chứng*).
  - Không migration.

## Bối cảnh

[CFG] `otp.code_length` (4–8, mặc định 6) cho ADMIN đổi qua UC10 (`PUT /api/system-configs/{key}`, identity-v1 #32). Chỉ `OtpService` đọc nó (ở `issueOtp` và `prepare()`); không DTO nào trả độ dài mã. FE cứng 6 số ở 5 chỗ (`VerifyOtpPage`, `ForgotPasswordPage`, `useCustomerPet.otpError`, `CustomerAccountDialogs`): ADMIN đổi cấu hình khỏi 6 thì quên mật khẩu, đăng ký và UC22 không nhập hoặc không gửi được mã đúng, còn BE vẫn nhận mã `^\d{1,8}$` nên không có chỗ nào báo lỗi sớm. Đo 1/11 (`test-tay…` L-20) nêu hai hướng: (a) BE trả thêm độ dài, (b) chỉ sửa FE nhận 4–8 chữ số, không cần BE; **Hiến chọn (a) ngày 1/11**. FE đã xong phần đọc (K25): đọc `codeLength` / `otpCodeLength` tùy chọn, hợp lệ 4–8, vắng hoặc không hợp lệ thì dùng 6, nên chạy cả khi BE chưa có.

## Quyết định

### 1. Hai trường cộng thêm, cuối record, bắt buộc

- `OtpSentResponse` thêm `codeLength` (`int`, **cuối record**). Mọi endpoint trả `OtpSentResponse` trả trường này: `forgot` (#6), `resend-otp` (#3), `/me/link/otp` (#12), sửa email nhân viên (#23, cùng DTO và cùng `StaffAdminMapper.toOtpSentResponse`), UC22 #25 (ADR-0097).
- `RegistrationResponse` (#1) thêm `otpCodeLength` (`int`, cuối record), cạnh `otpResendAvailableAt`.
- Cả hai là `required` trong `identity-v1` (kiểu nguyên thủy, luôn có giá trị). Không đổi trường cũ: FE cũ bỏ qua.

### 2. Giá trị = độ dài của **mã vừa sinh**, không đọc cấu hình lần hai

`code().length()` của mã đang được gửi. Không gọi `SystemConfigApi` thêm lần nữa, vì ADMIN có thể đổi `otp.code_length` giữa lúc sinh mã và lúc dựng response: số FE hiển thị phải bằng mã thật đã vào outbox. Không đổi record `IssuedOtp` (đã có `code`) hay `PreparedOtp`; mã gốc không ra response, chỉ lấy độ dài.

### 3. Quên mật khẩu trả CÙNG giá trị cho email lạ (BR-TK-10, ADR-0023)

`PasswordResetService.requestPasswordReset` gọi `otps.prepare()` ở **mọi** nhánh trước khi rẽ (đã gửi, email lạ, tài khoản không đủ điều kiện, BR-TK-07 bị nuốt, lỗi khóa bị nuốt). Độ dài lấy từ `prepared.code().length()`, không từ tài khoản, nên body giống hệt ở mọi nhánh và không lộ email nào có tài khoản. `PasswordResetMapper.toOtpSentResponse(Instant resendAvailableAt, String maskedEmail, int codeLength)`.

### 4. Cách ánh xạ ở bốn mapper

- `LinkProfileMapper.toOtpSentResponse(IssuedOtp, String)` và `StaffAdminMapper.toOtpSentResponse(IssuedOtp)`: `@Mapping(target = "codeLength", expression = "java(otp.code().length())")`. Hai mapper này có `unmappedTargetPolicy = ERROR`, nên thiếu ánh xạ là lỗi biên dịch.
- `RegistrationMapper`: hai `@Mapping` `expression` cùng kiểu, `otpCodeLength` cho `toResponse(Account, IssuedOtp)` và `codeLength` cho `toOtpSentResponse(IssuedOtp)` (dùng ở resend-otp).
- `PasswordResetMapper`: tham số `int codeLength` thứ ba, MapStruct ghép theo **tên tham số**.

### 5. Thêm chữ cho hợp đồng và Swagger

`VerifyOtpRequest.code` / `VerifyAccountRequest.code` bỏ chữ "6 chữ số", thay bằng "độ dài theo cấu hình `otp.code_length` (mặc định 6 chữ số)". Mô tả #6 thêm một câu: `codeLength` giống nhau ở mọi nhánh. `@Operation.description`, `@Schema` chép nguyên văn từ yaml (ADR-0040), `OpenApiContractParityIT` so cây thuộc tính nên Java và hợp đồng phải đổi cùng lúc.

## Phương án đã loại

- **Phương án (b) của L-20: FE nhận 4–8 chữ số, không cần BE:** khách phải tự đếm số chữ số trong khi mã có độ dài cố định theo cấu hình, và FE không biết khi nào đủ mã để bật nút; Hiến chọn (a) nên bỏ.
- **Đọc `SystemConfigApi.getInt(OTP_CODE_LENGTH)` lúc dựng response:** hai nguồn có thể lệch (xem §2) và thêm một phụ thuộc cho mapper / service.
- **Đưa độ dài vào `IssuedOtp`:** record đổi chữ ký, kéo theo mọi nơi tạo `IssuedOtp` (`OtpService`, test của ba module); `code().length()` có sẵn là đủ.
- **Lấy độ dài từ tài khoản / từ mã đã lưu (dòng `otp_tokens`):** quên mật khẩu với email lạ không có dòng nào, phải bịa số, mà BR-TK-10 cần cùng giá trị.
- **Thêm `ttlMinutes` (thời hạn mã) cùng đợt:** chưa làm. FE còn cứng "5 phút" ở hai trang auth, nhưng đó là việc riêng (xem *Việc mở*).
- **Gắn `codeLength` vào header hay endpoint cấu hình công khai:** thêm một đường đọc mới cho một con số; response vừa gửi mã đã là nơi FE cần số này.

## Hệ quả

- **Bẫy MapStruct:** `PasswordResetMapper` và `RegistrationMapper` dùng chính sách mặc định, **chỉ cảnh báo** khi một trường đích không có nguồn, nên trường mới ra `0` âm thầm nếu ánh xạ bị đổi tên hay xóa; không có lỗi biên dịch. Vì vậy test khẳng định **giá trị** (8 và 4 chứ không phải 6, để mặc định 6 không che lỗi), không chỉ sự có mặt của khóa. `LinkProfileMapper` và `StaffAdminMapper` (`ERROR`) tự bắt thiếu ánh xạ khi biên dịch, nhưng vẫn có test giá trị vì `expression` sai vẫn biên dịch được.
- **Hợp đồng `identity-v1`:** `OtpSentResponse` thêm `codeLength` (int32, bắt buộc), `RegistrationResponse` thêm `otpCodeLength` (int32, bắt buộc). Sinh từ `m_identity.py`; không sửa tay `.md` / `.yaml`.
- **FE:** đọc tùy chọn, mặc định 6 (K25). Đổi tên trường thì báo FE-1, sửa nhanh ở `FE/src/shared/types/auth.ts` và các nơi dùng. Mock của #23 (`identity-admin.mock.ts`) đã trả `codeLength`, test hai khóa đã đổi thành ba khóa.
- Sau khi ADMIN đổi `otp.code_length`: mã đã gửi trước đó giữ độ dài cũ tới khi hết hạn; response của lần gửi tiếp theo trả độ dài mới. FE đọc số này từ chính response của lần gửi gần nhất.
- **Điểm merge với nhánh `manh` (BE-1):** `OtpSentResponse`, `RegistrationResponse`, bốn mapper, `PasswordResetService`, `AuthController`, `docs/api/generator/m_identity.py` + `identity-v1.{md,yaml}`, các test đã liệt kê, số ADR 0096. `OtpSentResponse` đổi chữ ký record nên mọi nơi gọi `new OtpSentResponse(...)` (chủ yếu test) phải theo.

## Kiểm chứng

Mục này có hai loại: số **đã đo** trực tiếp ngày 2/11 (chạy trong bản sao mvn tách biệt, không dùng `BE/target`; log ở `D:/Do-an/.c2-tmp/final2/cum-1b/`, không nằm trong repo) và **lời agent** cài đặt, ghi rõ từng chỗ.

- **Ca mới (6 phương thức):**
  - Unit (5): `LinkProfileMapperTest.otpSentCodeLengthFollowsTheIssuedCode`, `StaffAdminMapperTest.otpSentResponseCodeLengthFollowsTheIssuedCode` (mã 8 số và 4 số, không phải 6), `PasswordResetServiceTest.forgotCodeLengthFollowsPreparedCodeOnEveryBranch` (mã 8 số, bốn nhánh cùng một response), `RegistrationServiceTest.registrationResponseCodeLengthFollowsTheIssuedCode`, `RegistrationServiceTest.resendCodeLengthFollowsTheIssuedCode`.
  - IT (1): `SystemConfigAdminIT.otpCodeLengthChangeReachesForgotAndRegisterResponses` — `PUT` `otp.code_length` = 8 → forgot cho email có tài khoản lẫn email lạ đều `codeLength` 8 (BR-TK-10), đăng ký trả `otpCodeLength` 8, và cả hai bằng độ dài `ma_otp` thật trong `notification_outbox`.
- **Khẳng định thêm trong IT cũ:** `LinkProfileIT`, `PasswordResetIT`, `RegistrationIT`, `RegistrationResendIT`, `StaffEmailChangeIT` (cả bộ khóa response nay có `codeLength` / `otpCodeLength` = 6 và độ dài bằng mã thật). `CustomerAccountEmailChangeIT` (ADR-0097) khẳng định tương tự cho #25. `OtpServiceTest.codeLengthFollowsConfig` có từ trước, không tính là ca mới.
- **Đã đo (2/11):** unit toàn bộ mọi module cộng `PetcareApplicationTests`: 5492 ca, 0 lỗi (riêng identity 2597); IT gộp trong MỘT JVM — mọi `*IT` của `module/identity` và `module/customer` cộng `OpenApiContractParityIT` và `SchemaMigrationIT`, 41 lớp: **1007 ca, 0 lỗi** (chi tiết từng lớp ở ADR-0097 mục Kiểm chứng); sáu IT hồi quy chạm response OTP (`PasswordResetIT`, `RegistrationResendIT`, `RegistrationIT`, `StaffEmailChangeIT`, `SystemConfigAdminIT`, `PendingAccountCleanupIT`) 199 ca xanh.
- **Lời agent (chưa đối chiếu):** có chạy đột biến ở bốn nơi (tên từng đột biến và ca đỏ **chưa được chép lại**; người chạy bổ sung trước khi ADR thành Accepted). Với các unit test viết sau mã, bằng chứng "đỏ" chỉ đến từ đột biến này. Con số "783 ca" agent báo cho nhóm identity bị bỏ vì trùng đúng số của ADR-0089 (trước UC22).
- **Hợp đồng (đã đo):** `node docs/api/check-contracts.mjs` — `ALL CONTRACTS PASS`, 231 operations, sau khi sinh lại.

## Việc mở

- **Có thêm thời hạn mã không?** `ttlMinutes` (hoặc tên khác) lấy từ `IssuedOtp.ttlMinutes` đã có; FE còn cứng "5 phút" ở `ForgotPasswordPage` và `VerifyOtpPage`. Chưa làm; **BE-1 quyết** có gộp vào ADR này hay tách.
- **Tên trường:** `codeLength` / `otpCodeLength` (hai tên khác nhau vì `RegistrationResponse` đặt tiền tố `otp` cho cả `otpResendAvailableAt`). Nếu BE-1 muốn tên khác hay một tên chung, đổi ở DTO, mapper, `m_identity.py`, và báo FE-1.
- ADR này **Proposed**: BE-1 chưa xem.
