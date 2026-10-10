# ADR-0027: Liên kết tài khoản với hồ sơ khách có sẵn (UC07) — 4 endpoint, khóa `accounts → customers`, ứng viên theo SĐT, audit ở identity, sổ địa chỉ xóa theo hồ sơ online

- **Trạng thái:** Accepted
- **Ngày:** 2026-10-10
- **Bổ sung:** ADR-0025 (OTP `LINK_PROFILE`) — đây là "phần 1" mà ADR-0025 mục 8 nhắc tới (task 08 phần 3).
- **Liên quan:**
  - BR-TK-04, 05, 06, 07, 11, 18, 19; BR-KH-01, 10; UC07 (01 L37), UC07 «include» UC02 (01 L242); 03 §1 L34 (cờ chờ liên kết nằm trên hồ sơ khách), Tài khoản#2.
  - identity-v1 #11 `GET /me/link-candidates`, #12 `POST /me/link/otp`, #13 `POST /me/link/confirm`, #14 `POST /me/link/decline`; `docs/api/00-method.md` §3.5 (thứ tự kiểm: quyền → tồn tại → guard → chuyển trạng thái).
  - erd `customers` (`uq_customers_account_id`, V1), `addresses`; system-overview §4b (thứ tự xóa BR-TK-19, TBD (1) sổ địa chỉ).
  - Convention 04 §4.4, 07 §7.1, 08 §8.3. ADR-0003, 0010, 0011, 0020, 0021, 0023, 0025, 0026. Nợ D001, D010, D013, D015 (mới).
- **Triển khai (task 08 phần 3):**
  - `identity/controller/MeController` (4 handler, `@PreAuthorize("hasRole('CUSTOMER')")`), `identity/service/LinkProfileService` (facade không transaction), `identity/service/LinkProfileAttemptService`, `identity/service/EmailMasker`, `identity/dto/{LinkOtpRequest, LinkConfirmRequest, LinkCandidateResponse, LinkResult}`, `identity/mapper/LinkProfileMapper`, `IdentityAuditActions.CUSTOMER_PROFILE_LINKED`.
  - `customer/api/CustomerApi.linkAccountToCounterProfile` đổi chữ ký + nghĩa vụ ở javadoc; `customer/api/CustomerQueryApi.checkOnlineProfileLinkable` + enum `OnlineProfileLinkability`; 2 placeholder cập nhật (vẫn ném — D001, D010).
  - Hợp đồng: `LinkOtpRequest.phone` (generator `m_identity.py`). Không migration.
  - Test: `LinkProfileServiceTest`, `LinkProfileAttemptServiceTest`, `LinkProfileMapperTest`, `EmailMaskerTest`, `module/identity/LinkProfileIT` (43 ca, module customer đứng thay bằng SQL thật theo nghĩa vụ javadoc).

## Bối cảnh

ADR-0025 làm xong OTP gắn (tài khoản, hồ sơ, email) nhưng chưa có endpoint, và để lại nghĩa vụ: khóa `accounts → customers`, kiểm lại BR-TK-19 dưới khóa **sau** khi xử lý mã. Module customer (BE-2) chưa cài `CustomerApi` / `CustomerQueryApi` (D001, D010). Hợp đồng cũ có ba lỗ: `linkAccountToCounterProfile` không biết email đã nhận mã (không kiểm được "hồ sơ đổi email giữa chừng" dưới khóa); `LinkOtpRequest` chỉ có `customerId` (khách bất kỳ dò id rồi xin mã tới email người khác); không có cách hỏi "hồ sơ online đã có dữ liệu / tài khoản đã liên kết chưa" để trả 400 sớm như hợp đồng ghi. Ngoài ra `CustomerContact` không cho biết hồ sơ là ONLINE hay COUNTER: một tài khoản **đã** liên kết vẫn gọi được UC07 lần nữa, và customer sẽ "xóa hồ sơ online" — lúc này là hồ sơ tại quầy đã liên kết.

## Quyết định (người dùng chốt 2026-10-10)

1. **Chỉ làm phía identity.** Test thay module customer bằng SQL thật; app thật trả 500 tới khi BE-2 giao (D001, D010).
2. **`CustomerApi.linkAccountToCounterProfile(accountId, counterCustomerId, expectedCounterEmail, accountEmail)`** — bỏ `actorId`. Customer khóa hồ sơ tại quầy C và hồ sơ của tài khoản O, kiểm dưới khóa (O là `ONLINE`, O chưa có dữ liệu, C là `COUNTER`, C chưa có `account_id`, `C.email = expectedCounterEmail`), sai thì chỉ ném `BusinessRuleViolationException("BR-TK-19")` (không `OtpRejectedException` — sẽ lọt `noRollbackFor` của identity). `MANDATORY`, không tác dụng ngoài transaction.
3. **`LinkOtpRequest {customerId, phone?}`**: bỏ trống `phone` = SĐT hồ sơ online. Chỉ gửi mã khi C nằm trong `findLinkCandidates(phone)`; không thì **404 chung** cho "không tồn tại" và "không phải ứng viên" — ngưỡng bằng màn danh sách ứng viên, chống dò id.
4. **`CustomerQueryApi.checkOnlineProfileLinkable(accountId)` → `LINKABLE | ALREADY_LINKED | HAS_DATA`**, identity gọi ở #11, #12 để trả 400 BR-TK-19 sớm (message riêng cho từng lý do); customer kiểm lại dưới khóa ở #13. Không đổi record `CustomerContact` (đang dùng ở đăng nhập, `GET /me`, branch).
5. **Audit `CUSTOMER_PROFILE_LINKED` do identity ghi** (`record`, cùng transaction; `entity = customers/C`, before/after có `onlineCustomerId`, `linkedAccountId`, `verificationMethod = EMAIL_CODE`) — convention 08 §8.3 giao TK. Customer không audit (tránh ghi đôi).
6. **Thứ tự khóa `accounts → otp_tokens → customers`**: #12, #13, #14 khóa dòng `accounts` của chính tài khoản trước mọi lời gọi customer. Cùng chiều đăng nhập / đổi, đặt lại mật khẩu / đăng xuất / ST02 → không chu trình. UC22 (lễ tân liên kết hộ, identity-v1 #27) sau này **phải** khóa `accounts` của tài khoản được liên kết trước.
7. **Thứ tự kiểm** (`00-method.md` §3.5): #12 `BR-TK-11` (dưới khóa, xếp vào nhóm "quyền" — tiền lệ ADR-0026) → 404 → `BR-TK-19` (liên kết được; hồ sơ có email) → `BR-TK-07`. **Ngoại lệ có chủ đích ở #13**: mã OTP được settle **trước** BR-TK-19 vì bộ đếm sai phải là lệnh ghi đầu tiên (ADR-0025 checklist 3); mã đúng mà customer từ chối → rollback cả `consumed_at`, mã dùng lại được.
8. **#14 không còn cờ → 409 `InvalidStateTransitionException`** do service tạo. Lệch convention 04 §4.4 ("do `StateMachineBase` ném, service không tự tạo") có chủ đích: cờ `link_decision_pending` là thuộc tính của hồ sơ khách, không có bảng chuyển trạng thái ở 03 nên không có `TransitionHandler`; hợp đồng #14 ghi 409.
9. **`maskedEmail`** (#12) = 2 ký tự đầu phần tên (1 nếu phần tên ≤ 2) + `***@` + domain — giả định, đặc tả không quy định.
10. **BR-TK-07 ở #12 trả 400 bình thường** (endpoint cần đăng nhập, khách đã thấy hồ sơ ở #11 — không có vấn đề BR-TK-10 như ADR-0023).
11. **Sổ địa chỉ của hồ sơ online bị xóa cùng hồ sơ** khi liên kết (đóng TBD (1) của system-overview §4b): customer xóa `addresses` của O → xóa O **và `flush()`** → gắn C. `flush()` bắt buộc: Hibernate flush UPDATE trước DELETE, mà `uq_customers_account_id` không deferrable — gán `C.account_id` trước khi O bị xóa thật sẽ vi phạm UQ (409).
12. **Không idempotency cho #13**: mất response sau commit thì gọi lại nhận 400 BR-TK-05 (mã chỉ dùng một lần — BR-TK-05); FE đọc lại `GET /me`. `docs/` không có yêu cầu idempotency nào.

## Hệ quả

- Khách có mã liên kết cho C, nhưng tài khoản khác liên kết C trước: (a) tuần tự — email C đã đổi theo tài khoản kia nên mã không còn khớp → BR-TK-05, không đếm (ADR-0025 mục 2); (b) chen giữa lúc đọc và lúc khóa — customer từ chối dưới khóa → BR-TK-19, mã rollback (`LinkProfileIT.profileLinkedBetweenReadAndLockIsRejectedUnderLock`).
- Quota BR-TK-07 theo email nhận: tài khoản khác biết SĐT của hồ sơ tại quầy vẫn tiêu được 5 mã/giờ của email hồ sơ (và của quên mật khẩu nếu cùng email) — nợ **D015**.
- FE (T13): gửi lại `phone` đã dùng ở `getLinkCandidates` khi gọi `/me/link/otp`; báo trước "Sổ địa chỉ của hồ sơ online sẽ bị xóa"; sau lỗi ở confirm thì đọc lại `GET /me`.
- **Đã kiểm 2026-10-10:** mốc `mvn clean verify` trên `ecb6bc2` = 820 unit + 415 IT xanh. Mutation (mỗi lần một chỗ, đã hoàn tác): bỏ `noRollbackFor` ở `applyLink` → `wrongCodeIsCountedReturns400AndDoesNotLink`, `fifthWrongCodeIs06ThenRightCodeIsRejected` đỏ; gọi customer trước settle → `wrongCodeIsCountedReturns400AndDoesNotLink` đỏ; bỏ kiểm ứng viên ở #12 → `otpForNonCandidateIs404BeforeDataGuardAndWritesNothing` đỏ; bỏ khóa `accounts` ở #14 → `declineRacingConfirmIsSerializedByTheAccountLock` đỏ.
