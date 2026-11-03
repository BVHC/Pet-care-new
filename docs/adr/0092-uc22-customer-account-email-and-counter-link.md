# ADR-0092: UC22 — lễ tân sửa hộ email tài khoản khách và liên kết hộ hồ sơ tại quầy (identity-v1 #25–27)

- **Trạng thái:** **Proposed (chờ BE-1 xác nhận; mục 9a–c cần BE-2)**
- **Ngày:** 2026-11-02
- **Tác giả:** FE-1 (Hiến) với Claude, **thay BE-1**.
- **Liên quan:**
  - identity-v1 #25 `POST /customers/{customerId}/account-email-change`, #26 `POST /customers/{customerId}/account-email-change/confirm`, #27 `POST /customers/{customerId}/link-account`; UC22; BR-TK-01, 04, 05, 06, 07, 11, 16, 19; BR-KH-01, 10; BR-QT-15; giả định A6 (`verificationMethod`); `docs/api/00-method.md` §3.5.
  - ADR-0034 (khuôn sửa email: mục **TBD (dòng ~51) nay trỏ tới ADR này**; ADR-0034 không sửa), ADR-0027 (UC07: mục 6 thứ tự khóa, mục 7 thứ tự kiểm), ADR-0025 (OTP gắn chủ thể), ADR-0023 (BCrypt ngoài khóa), ADR-0010 (sai mã vẫn commit bộ đếm), ADR-0011 (khóa `accounts` cho luồng OTP), ADR-0013 (ST02), ADR-0041 (hồ sơ khách dùng chung toàn chuỗi), ADR-0040 (Swagger = hợp đồng), ADR-0091 (`codeLength` của #25). Nợ D015 (quota theo email đích).
  - `docs/06-module-contracts.md` §6 (ghi chú 2/11 cho BE-1 và cho BE-2); `docs/testcases/T49-fe.md` K21; `docs/testcases/test-tay-ghep-api-theo-cum.md` L-21.
- **Triển khai:**
  - Người viết: FE-1 (Hiến) với Claude; **người dùng (Hiến) cho phép sửa module `identity` của BE-1** (2/11/2026). BE-1 chưa xem. **Không sửa module `customer`** (BE-2): mọi lời gọi sang customer dùng `CustomerApi` / `CustomerQueryApi` đã có.
  - Mới (controller): `identity/controller/CustomerAccountController` (`/api/customers/{customerId}/…`, tag "Customer account", 3 handler).
  - Mới (service): `CustomerAccountService` (facade #25, #26, không tx), `CustomerEmailChangeService` (phần đọc / ghi #25, #26), `CounterLinkService` (facade #27, không tx), `CounterLinkStepService` (hai transaction của #27).
  - Mới (dto / exception): `CustomerEmailChangeRequest`, `CounterLinkRequest`, enum `VerificationMethod {ID_CARD_IN_PERSON}`; `CustomerAccountNotEditableException`, `LinkAccountNotFoundException`. Sửa: `IdentityAuditActions` (thêm `CUSTOMER_EMAIL_CHANGED`, `VERIFICATION_ID_CARD`; `CUSTOMER_PROFILE_LINKED` dùng chung với UC07). Dùng lại: `OtpCodeRequest`, `OtpSentResponse`, `LinkResult`, `StaffAdminMapper.toOtpSentResponse`, `LinkProfileMapper.toLinkResult`, `StaffEmailChangeService.{MSG_*, EmailChangeCode, EmailChangeAttempt}`.
  - Hợp đồng: `docs/api/generator/m_identity.py` (notes và lỗi của op #25–27; rules #26 thêm BR-TK-01; #26 thêm 409 `CONCURRENCY_CONFLICT`; 404 của #27 mô tả riêng; mô tả `verificationMethod` của `CounterLinkRequest`) → `identity-v1.{md,yaml}` sinh lại. `docs/api/INDEX.md` không đổi (identity đã 38, tổng 231).
  - Test mới: `CustomerAccountEmailChangeIT` (32 ca), `CustomerAccountLinkIT` (30 ca), `CustomerEmailChangeServiceTest` (20), `CustomerAccountServiceTest` (6), `CounterLinkServiceTest` (3), `CounterLinkStepServiceTest` (22); `OpenApiContractParityIT` đổi số endpoint đã cài 181 → 184.
  - Không migration. Mẫu `OTP_EMAIL_CHANGE`, `EMAIL_CHANGED_NOTICE` có sẵn từ V11.

## Bối cảnh

Hợp đồng identity-v1 #25–27 (UC22, BR-TK-16) có từ trước nhưng BE chưa có controller: đo 1/11, ba `POST` đều 404 `RESOURCE_NOT_FOUND` "Không tìm thấy đường dẫn yêu cầu" (K21, L-21). Hai hộp thoại của lễ tân ("Sửa email tài khoản", "Liên kết tài khoản online") gọi chúng; FE tạm hiện "chưa được máy chủ hỗ trợ". ADR-0034 mục TBD để lại việc đồng bộ `customers.email` "chốt khi làm UC22"; ADR-0027 mục 6 ghi UC22 #27 **phải** khóa `accounts` của tài khoản được liên kết trước. Hợp đồng cũ không nói: hồ sơ chưa gắn tài khoản thì sao, tài khoản `PENDING` hay đang khóa thì sao, hồ sơ quầy không có email thì liên kết thế nào, thứ tự kiểm lỗi. Người dùng cho phép FE-1 làm thay BE-1 ngày 2/11 (cùng đợt với ADR-0091).

## Quyết định

### 1. Vai trò, vị trí, tiền tố

- **Chỉ RECEPTIONIST (A06)**: `@PreAuthorize("hasRole('RECEPTIONIST')")`. Role khác kể cả quản lý → 403 `ACCESS_DENIED`, không audit. **Không `StaffHierarchyPolicy` (BR-QT-01), không phạm vi chi nhánh**: hồ sơ khách dùng chung toàn chuỗi (ADR-0041, nhân viên mọi chi nhánh thấy mọi hồ sơ). Người gọi còn phải đổi mật khẩu lần đầu → 400 BR-TK-17 (`MustChangePasswordInterceptor`).
- Controller mới `CustomerAccountController` ở `/api/customers/{customerId}/…`, cùng tiền tố với `CustomerController` của module customer nhưng **không trùng path nào**; tag Swagger "Customer account". Tách khỏi `CustomerController` vì đó là module của BE-2.

### 2. #25 / #26 — đổi email TÀI KHOẢN khách (khuôn ADR-0034)

Thứ tự kiểm (cả hai endpoint): 400 hình thức (Bean Validation, trước `@PreAuthorize`) → 403 role → **404 hồ sơ** ("Không tìm thấy hồ sơ khách #id") → **hồ sơ chưa gắn tài khoản: 400 `VALIDATION_FAILED` KHÔNG hậu tố BR** (đúng hợp đồng) → khóa dòng `accounts` (`findByIdForUpdate`) → tài khoản không phải CUSTOMER → 404 như hồ sơ không có (lễ tân không sửa email nhân viên qua cửa này) → **`PENDING`: 400 `VALIDATION_FAILED` không BR** (`Account.changeEmail` ném lỗi lập trình với `PENDING`, sẽ thành 500) → email mới trùng email hiện tại / thuộc tài khoản khác (kể cả `PENDING`, nhân viên) → BR-TK-01 → BR-TK-07 (60 giây, 5 mã/giờ [CFG], **tính theo email đích**).

- Tài khoản `is_locked` **được sửa**: mục đích là khôi phục tài khoản (BR-TK-16, tiền lệ ADR-0034 Q1). `DISABLED` không tồn tại với khách (CHECK `ck_accounts_disabled_staff_only`).
- Request `CustomerEmailChangeRequest {newEmail, verificationMethod}`; enum `VerificationMethod {ID_CARD_IN_PERSON}` (A6): thiếu → 400 `VALIDATION_FAILED`, giá trị lạ → 400 `MALFORMED_REQUEST`. Response #25 `OtpSentResponse(resendAvailableAt, maskedEmail = null, codeLength)` (ADR-0091); #26 `204` không body, body `OtpCodeRequest {code}` dùng lại.

### 3. Hai exception 400/404 không mã BR

- `CustomerAccountNotEditableException extends PlatformException` với `VALIDATION_FAILED`: `BusinessRuleViolationException` luôn nối " (BR-…)" vào message nên không dùng được cho "hồ sơ chưa gắn tài khoản" / "tài khoản `PENDING`" (không thuộc rule nào). Tiêu chí 2 của convention 04 §4.2 (message khác default), cùng khuôn `BranchSelectionRequiredException` (ADR-0089).
- `LinkAccountNotFoundException` (`RESOURCE_NOT_FOUND`, câu cố định "Không tìm thấy tài khoản khách hàng đã kích hoạt với email này", **không nhắc lại email người gõ**): một câu chung cho email lạ, tài khoản nhân viên, tài khoản khách chưa có hồ sơ và tài khoản `PENDING`, để lễ tân không dò được email nào là nhân viên / đang chờ xác thực.
- Cả hai lớp cần BE-1 duyệt (xem mục 9).

### 4. OTP `CHANGE_EMAIL` và giao dịch của #25 / #26

- Mã gắn `(account_id = tài khoản khách, target_email = email mới)`, **KHÔNG `customer_id`**: CHECK `ck_otp_tokens_link_customer` chỉ cho `LINK_PROFILE` có `customer_id`, và guard `OtpService.checkCustomerMatchesPurpose` chặn ngược lại. Hủy mã cũ **theo tài khoản** (`OtpPurpose.invalidatesPerAccount()`, ADR-0034 Q3): mỗi tài khoản tối đa một mã đổi email còn sống.
- Facade `CustomerAccountService` **không `@Transactional`**: `otps.prepare()` (BCrypt) và `passwordEncoder.matches` chạy ngoài giao dịch và ngoài khóa (ADR-0023). #25: một transaction `issueEmailChangeOtp` (khóa `accounts` → kiểm → `issuePrepared` → outbox `OTP_EMAIL_CHANGE` tới email **mới**). #26: `findEmailChangeCode` (readOnly, cùng 404 / 400 như #25 **trước** khi tra mã) → BCrypt ngoài → `applyEmailChange` (`noRollbackFor = OtpRejectedException`): khóa `accounts` → kiểm lại hồ sơ / tài khoản dưới khóa → `settleCheckedOtp` là **lệnh ghi đầu tiên** (ADR-0010 / 0025: sai mã vẫn commit bộ đếm; mã mới hơn / hết hạn → BR-TK-05 không đếm) → BR-TK-01 dưới khóa → đổi email → thư → audit → `flush()`.
- **BR-TK-01 sau settle** (email bị tài khoản khác lấy sau khi gửi mã) → rollback cả `consumed_at`: mã dùng lại được. Hai xác nhận song song cho cùng email mới: `uq_accounts_email` nổ tại `flush()` → 409 `CONCURRENCY_CONFLICT`.
- Không còn mã đang mở → 400 BR-TK-05 (facade ném sau khi transaction đọc kết thúc).

### 5. Thông báo và audit

- #25: `OTP_EMAIL_CHANGE {ma_otp, thoi_han_phut}` tới email mới (BR-TK-04), **không audit** (không đổi dữ liệu; ADR-0034 Q4).
- #26 (chỉ khi thành công): hai thư `EMAIL_CHANGED_NOTICE {email_cu, email_moi, thoi_diem}` (tới email cũ rồi email mới; `thoi_diem` `HH:mm dd/MM/yyyy` giờ VN); **không hủy phiên của khách** (JWT chỉ mang id, phiên cũ vẫn hợp lệ; tiền lệ ADR-0034 Q2); audit `CUSTOMER_EMAIL_CHANGED` (`IdentityAuditActions`), `entity = accounts/<id>`, `before {email}`, `after {email, verificationMethod = ID_CARD_IN_PERSON}`.

### 6. #27 — liên kết hộ, không OTP, CHIA HAI GIAO DỊCH

`CounterLinkService` (facade, **không `@Transactional`**) gọi hai method của `CounterLinkStepService` ở **hai transaction riêng**:

1. `findCounterEmail` (readOnly, không khóa): `CustomerQueryApi.findContact` → 404 hồ sơ → hồ sơ đã gắn tài khoản: BR-TK-19, **một câu chung** ("hồ sơ online hoặc đã liên kết"; `CustomerContact` không có kênh nên không tách được hai ca) → hồ sơ không có email: BR-TK-19 kèm hướng dẫn bổ sung email.
2. `applyLink`: khóa `accounts` theo email chuẩn hóa **trước** (ADR-0027 mục 6) → không có / không CUSTOMER / không `ACTIVE` (gồm `PENDING`) / không có hồ sơ: 404 một câu chung → `is_locked`: BR-TK-11 → guard "hồ sơ của tài khoản chính là hồ sơ đang liên kết" (bấm đúp): BR-TK-19 → `checkOnlineProfileLinkable` `ALREADY_LINKED` / `HAS_DATA`: BR-TK-19, **hai câu riêng** (không dùng câu "của bạn" của UC07) → `CustomerApi.linkAccountToCounterProfile(accountId, customerId, contact.email(), account.getEmail())` → audit `CUSTOMER_PROFILE_LINKED` (`entity = customers/<hồ sơ quầy>`, `LinkSnapshot`, `verificationMethod = ID_CARD_IN_PERSON` = hằng `VERIFICATION_ID_CARD`). **Không thư, không hủy phiên.**

**Vì sao hai giao dịch:** `findContact` nạp entity hồ sơ vào persistence context. Gộp một transaction thì bước "kiểm lại dưới khóa" của `linkAccountToCounterProfile` đọc lại bản cũ đã nạp, và hồ sơ vừa bị người khác liên kết bị ghi đè. Có IT hành vi cho đúng khoảng hở này: `counterProfileLinkedByAnotherAccountBetweenReadAndLockIsRejectedUnderLock` (javadoc của `CounterLinkService` ghi đó là ca bị hỏng nếu gộp `@Transactional` lên facade; agent cài đặt báo ca đỏ "expected 400 but was 200" khi thử — tên ca và thông báo lỗi chưa được đối chiếu với nhau, xem *Kiểm chứng*).

### 7. Chặn `PENDING` ở #27 là BẮT BUỘC; hồ sơ quầy không email

- **PENDING:** ST02 (`PendingAccountCleanupService.purge`, dòng 60) gọi `CustomerApi.deleteOnlineProfileOfUnverifiedAccount`, **không lọc kênh tạo** (`lockOwn` lấy hồ sơ gắn tài khoản bất kể kênh). Hồ sơ quầy gắn nhầm vào tài khoản `PENDING` sẽ bị xóa khi tài khoản quá hạn, hoặc nếu hồ sơ có dữ liệu thì FK `RESTRICT` làm job lỗi lặp vô hạn trên tài khoản đó. Vì vậy #27 trả 404 chung cho `PENDING`.
- **Hồ sơ quầy không có email:** UC07 và `linkAccountToCounterProfile` so email hồ sơ với `expectedCounterEmail` nên email rỗng không liên kết được. Hướng dẫn: "sửa hồ sơ khách (`PATCH /api/customers/{id}`) bổ sung email rồi liên kết lại". **Đã chứng minh chạy được không cần sửa module customer** bằng IT đầu-cuối trên controller customer thật (`guidanceIsNotADeadEndPatchTheEmailThenLink`): `customers.email` không UNIQUE (`twoProfilesMayShareTheSameTemporaryEmailAndBothLink`), lễ tân đặt được email cho hồ sơ `COUNTER` chưa gắn, và `Customer.linkAccount` ghi đè email bằng email tài khoản (`aTemporaryProfileEmailDifferentFromTheAccountEmailStillLinksAndIsOverwritten`).
- **Hạn chế đã biết:** (i) email tạm của hồ sơ lộ cho UC07 (`hasEmail = true`, OTP của khách có thể tới email tạm: lễ tân nên đặt đúng email tài khoản); (ii) hai lời gọi (PATCH rồi #27) không nguyên tử; (iii) `PATCH /api/customers/{id}` không audit.

### 8. Hợp đồng, Swagger, test

`@Operation.description` và mô tả lỗi trong `@ApiErrorDoc` chép nguyên văn từ yaml (ADR-0040); `OpenApiContractParityIT` đổi 181 → 184. Test #27 dùng **module customer THẬT** (không dùng stand-in `JdbcCustomerModule` của `LinkProfileIT`, vì stand-in che đúng khoảng trống thật ở mục 7). Chi tiết ở *Kiểm chứng*.

### 9. Việc mở / không làm — ai quyết

Các mục (a)–(c) cần **BE-2 (Dũng)**; BE-1 xác nhận phần identity.

- **(a) BE-2 — `customers.email` không đồng bộ (ADR-0034 mục TBD).** Hai hướng, **không ngang nhau**:
  1. Ghi lại `customers.email` khi #26 thành công (cần method mới trên `CustomerApi`, gọi trong cùng transaction, khóa `accounts → customers`). Chỉ sửa **độ lệch sau khi đổi email** (và sau #27). **Không** sửa hồ sơ online đăng ký: `CustomerApi.createOnlineProfile(accountId, fullName, phone)` không nhận email (`CustomerApiService` dòng ~41) nên `customers.email = NULL`, hộp UC22 vẫn hiện "Email hiện tại: —" — trường hợp chính của việc khôi phục tài khoản. Muốn sửa luôn phải thêm email vào `createOnlineProfile` và `RegistrationService` (BE-1).
  2. Đường nhân viên của customer (`GET /api/customers/{id}`, `CustomerStaffMapper.toProfile`, hiện chỉ map `customers.email`) đọc email tài khoản qua `identity.api.AccountContactApi.findContacts` (customer đã phụ thuộc `identity.api`). Sửa **cả hai** (hồ sơ online `NULL` lẫn độ lệch), không cần method ghi mới.
- **(b) BE-2 — bỏ bước PATCH cho hồ sơ quầy không email.** Một dòng: `CustomerApiService` dòng ~99–100 đổi `expectedCounterEmail == null || !expectedCounterEmail.equals(counter.getEmail())` thành `!java.util.Objects.equals(expectedCounterEmail, counter.getEmail())` (UC07 không đổi hành vi vì không bao giờ truyền null). Phải sửa `CustomerApiServiceTest`, javadoc `CustomerApi`, `docs/06-module-contracts.md` (§4.2, dòng "Liên kết hồ sơ (UC07, UC22)", ~dòng 92) và ADR-0027 mục 2. Hoặc thêm method thứ hai `linkAccountToCounterProfileByStaff` (phải thêm override vào `LinkProfileIT.JdbcCustomerModule`).
- **(c) BE-2 — ST02 và `CustomerContact`.** Lọc kênh ở `deleteOnlineProfileOfUnverifiedAccount` (phòng thủ; hiện #27 tự chặn `PENDING`) và/hoặc thêm kênh vào `CustomerContact` để #27 trả hai câu riêng thay một câu chung.
- **(d) Hiến / BE-1 — thứ tự kiểm của #27.** Code kiểm BR-TK-19 của hồ sơ **trước**, rồi mới 404 tài khoản; `docs/api/00-method.md` §3.5 và ADR-0027 mục 7 ghi 404 (tồn tại) trước guard. Cần chốt đổi BE hay giữ (và sửa chữ ở tài liệu).
- **(e) BE-1 — nợ nhỏ.** Quota BR-TK-07 theo email đích vẫn không khóa (nợ D015, namespace advisory **1005** còn trống); `verificationMethod` được kiểm ở #25 nhưng **không lưu** (audit #26 ghi cứng `ID_CARD_IN_PERSON`, thêm phương thức thứ hai cần cột lưu); TOCTOU nhỏ: #25 đọc hồ sơ rồi tài khoản không khóa **trước** khi khóa `accounts`; chưa có IT đồng thời cho #25 / #26 (409 `CONCURRENCY_CONFLICT` của #26 ghi ở hợp đồng nhưng chưa có IT); không hủy phiên khi khôi phục tài khoản; xác nhận hai exception mới (mục 3).
- **(f) PO / Hiến — hủy phiên cũ.** Khôi phục tài khoản (khách mất email cũ) có cần hủy phiên đã mở không (hiện không, theo ADR-0034 Q2).
- **(g) Đã xong phía FE:** mock khớp; xử lý 404 "chưa hỗ trợ" của K21 không còn cần trên BE mới.
- ADR này **Proposed**: BE-1 chưa xem; BE-2 chưa xem mục 9a–c.

## Phương án đã loại

- **Một transaction cho #27 (`@Transactional` trên facade):** hỏng "kiểm lại dưới khóa" (mục 6).
- **OTP cho #27:** lễ tân đã đối chiếu CCCD tại quầy (BR-TK-16, 19); hợp đồng ghi "không cần OTP"; không có email nào đáng tin để gửi mã (hồ sơ quầy có thể không email).
- **Cho phép tài khoản `PENDING` ở #27:** ST02 xóa hồ sơ (mục 7).
- **Dùng `BusinessRuleViolationException` cho "hồ sơ chưa gắn tài khoản" / "`PENDING`":** nối " (BR-…)" sai nghĩa; hợp đồng ghi không mã. Dùng `ResourceNotFoundException` cho tài khoản ở #27: message mặc định lặp lại id / email người gõ.
- **Phân cấp BR-QT-01 / phạm vi chi nhánh:** hồ sơ khách chuỗi, chỉ một vai trò gọi được (mục 1).
- **Cấm sửa email tài khoản đang bị khóa:** đây chính là đường khôi phục tài khoản.
- **Hủy phiên khi đổi email:** tiền lệ ADR-0034 Q2; hỏi PO ở mục 9f.
- **Đồng bộ `customers.email` ngay trong ADR này:** cần sửa module customer của BE-2 (mục 9a).
- **Sửa `CustomerApi` để #27 không cần bước PATCH:** module của BE-2 (mục 9b).

## Hệ quả

- Sau #26: đăng nhập / quên mật khẩu bằng email cũ như email lạ, email mới dùng ngay với mật khẩu cũ, email cũ được giải phóng; phiên của khách giữ nguyên; thư đã xếp hàng trước khi đổi vẫn tới email cũ (người nhận chốt lúc ghi outbox).
- **`customers.email` KHÔNG đồng bộ sau #26** — `CustomerAccountEmailChangeIT.customerProfileEmailIsNotSyncedKnownGap` ghim hiện trạng (khi BE-2 đồng bộ, ca này phải đổi). Hệ quả nhìn thấy: hộp UC22 và hồ sơ ở đường nhân viên vẫn hiện email cũ; lễ tân thử gõ email mới nhận "Email mới trùng email hiện tại". Và #27 ghi đè `customers.email` bằng email tài khoản nên về sau #26 làm lệch tiếp (ảnh hưởng cả hồ sơ đã liên kết). Xem 9a.
- **Khóa:** #25 / #26 `accounts → otp_tokens`, không ghi `customers`. #27 `accounts → customers` (không `otp_tokens`; `customers` bởi `CustomerApi`, id tăng dần trong module customer). Bước đọc hồ sơ của #27 nằm **ngoài** transaction ghi (mục 6).
- **Điểm merge với nhánh `manh` (BE-1):** `OtpSentResponse`, `RegistrationResponse`, bốn mapper, `PasswordResetService`, `AuthController` (ADR-0091); `IdentityAuditActions`; `OpenApiContractParityIT` (181 → 184); `docs/api/generator/m_identity.py` + `identity-v1.{md,yaml}`; số ADR 0091 / 0092. `docs/api/INDEX.md` không đổi.
- **FE:** mock đã khớp (`identity.mock.ts`); `K21` (hiện "chưa được máy chủ hỗ trợ" cho 404 "không có đường dẫn") không còn gặp trên BE mới, 404 thực thể ("email chưa có tài khoản") vẫn là 404 như mock.

## Kiểm chứng

Mục này có hai loại: số **đã đo** trực tiếp ngày 2/11 (chạy trong bản sao mvn tách biệt, không dùng `BE/target`; log ở `D:/Do-an/.c2-tmp/final2/cum-1b/`, không nằm trong repo) và **lời agent** cài đặt, ghi rõ từng chỗ.

- **Số ca đếm từ mã nguồn** (ca thường + từng dòng của test tham số hóa): `CustomerAccountEmailChangeIT` 32, `CustomerAccountLinkIT` 30, `CustomerEmailChangeServiceTest` 20, `CustomerAccountServiceTest` 6, `CounterLinkServiceTest` 3, `CounterLinkStepServiceTest` 22; khớp số agent báo.
- **Ca đáng chú ý:** `CustomerAccountEmailChangeIT.emailTakenAfterCodeSentIsBrTk01AndCodeStaysUsable`, `bcryptRunsWithoutHoldingAConnection`, `failureAfterSettleRollsBackEmailCodeNoticesAndAudit` (trigger làm INSERT `audit_logs` / `notification_outbox` thất bại), `profileLinkedToStaffAccountIs404AndNeverTouchesStaffEmail`, `customerProfileEmailIsNotSyncedKnownGap`; `CustomerAccountLinkIT.doubleSubmitOfTheSameLinkSucceedsOnceAndTheOtherIsBrTk19`, `oneAccountForTwoCounterProfilesLinksOnlyOne`, `counterProfileLinkedByAnotherAccountBetweenReadAndLockIsRejectedUnderLock`, `counterLinkRacingTheCustomersOwnProfileLinkDoesNotDeadlock`, `writeFailureAfterTheDeleteRollsBackTheWholeLink`, `pendingAccountIs404AndTheCounterProfileIsNeverAttachedToIt`.
- **Đã đo (2/11):**
  - unit toàn bộ mọi module cộng `PetcareApplicationTests` (nạp context, Flyway + Hibernate validate): 5492 ca, 0 lỗi; riêng package identity 2597 ca xanh;
  - IT chạy gộp trong MỘT JVM (điều kiện của lần chạy đầy đủ, để lộ dữ liệu rò giữa các lớp dùng chung SĐT/email): 41 lớp = mọi `*IT` của `module/identity` và `module/customer` + `OpenApiContractParityIT` + `SchemaMigrationIT`, **1007 ca, 0 lỗi, 0 bỏ qua**. Trong đó `CustomerAccountEmailChangeIT` 32, `CustomerAccountLinkIT` 30, `LinkProfileIT` 43, `OpenApiContractParityIT` 11 (ghim 184), `CounterCustomerIT` 22, `CustomerModuleApiIT` 12, `CustomerProfileIT` 28, `MeIT` 13, `OtpLinkProfileIT` 10;
  - lần chạy riêng trước đó: sáu IT hồi quy (`PasswordResetIT`, `RegistrationResendIT`, `RegistrationIT`, `StaffEmailChangeIT`, `SystemConfigAdminIT`, `PendingAccountCleanupIT`) 199 ca xanh;
  - `node docs/api/check-contracts.mjs`: `ALL CONTRACTS PASS`, 231 operations.
- **Trung thực về TDD:** một số unit test của UC22 được viết sau mã. Với chúng, bằng chứng "đỏ" chỉ đến từ các đột biến dưới đây, không phải từ một lần chạy đỏ trước khi có mã.
- **Lời agent (chưa đối chiếu):**
  - đột biến 6 (nhóm #25 / #26) và 11 (nhóm #27), mỗi lần một chỗ rồi hoàn tác; **tên từng đột biến và ca đỏ chưa được chép lại** — người chạy bổ sung trước khi ADR thành Accepted. Đột biến duy nhất có mô tả cụ thể: đặt `@Transactional` lên facade `CounterLinkService` → agent báo ca đọc-rồi-khóa đỏ "expected 400 but was 200" (javadoc `CounterLinkService` nêu tên ca nhắm đích; chưa thấy bản ghi chạy);
  - con số "783 ca" agent báo cho nhóm identity bị bỏ: trùng đúng số của ADR-0089 (trước UC22) nên không đáng tin, đã thay bằng 1007 ca đo ở trên.
