# Auth API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Authentication & OTP (Module 01) + `CreateStaff` D-04.
> **Nguồn chân lý:** `docs/01-business-operations.md`, `docs/02-business-rules.md`,
> `docs/03-state-machines.md`, `docs/04-glossary.md`, `docs/05-domain-model.md`,
> `docs/06-erd.md`. Folder `plans/` đã lỗi thời, **không sử dụng**.
> **Contract máy đọc:** [`./openapi/auth-v1.yaml`](./openapi/auth-v1.yaml) (OpenAPI 3.1).
> **Skill áp dụng:** `designing-apis` — API là business contract, không phải CRUD của DB.

**Legend:** `CONFIRMED` = có trong docs (kèm nguồn) · `ASSUMPTION (A#)` = suy luận
hợp lý từ docs, chưa xác nhận · `TBD (Q#)` = cần PO/Product quyết định (xem mục E).
Mọi method/path/field-name/envelope trong tài liệu này đều là **PROPOSED design**
(docs không chứa URL hay JSON shape nào).

**Sửa 2026-09-13 (RULE-01-10):** danh tính chính của Register/VerifyOTP/ResendOTP
đổi từ `phone` sang **`email`** (bắt buộc + duy nhất cấp Platform); `phone`
chuyển thành optional. Lý do: dùng Gmail SMTP làm kênh gửi OTP thay SMS Gateway.
Xem Decision Log đầy đủ tại `docs/02-business-rules.md` mục 01 (cuối, sau
RULE-01-10). Toàn bộ mô tả field `phone` dưới đây trong C1-C3 cần đọc là `email`.

**Đóng băng phạm vi:**
- `CheckOTP` / `ExpireOTP` là system-internal, không expose API.
- Lock / Unlock / Deactivate thuộc IAM, không đưa vào Auth API. Ngoại lệ duy nhất:
  `AutoLockAccount` (System, RULE-01-07) là *hiệu ứng* của `Login` thất bại liên tiếp —
  contract Auth mô tả hiệu ứng này nhưng không expose endpoint riêng.
- Forgot / Reset Password **loại khỏi v1** (ERD có `otps.purpose = PASSWORD_RESET`
  nhưng business operations chưa xác nhận các operation này).
- Verify OTP tuân FSM (`PENDING_VERIFICATION → ACTIVE`), không auto-login.
- Resend OTP là endpoint riêng (`ResendOTP` là operation riêng).
- Refresh token: **implemented 2026-09-14** — `POST /auth/refresh` (xem C5).
  Rotation (1 refresh token dùng 1 lần, reuse bị từ chối) và lưu trữ theo
  ADR-0001; không có RULE-ID/FSM riêng cho refresh (đây là session/token-layer
  infra, giống Logout — RULE-01-06/02-04 chỉ nói tới việc thu hồi, không nói
  tới việc làm mới). Xem Q3 (đã giải).

---

## A. Confirmed Auth API (7 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /auth/register` | `RegisterAccount` + hiệu ứng `SendRegistrationOTP` — `01#1`, RULE-01-01/02/03 |
| 2 | `POST /auth/verify-otp` | `VerifyOTP` — `01#1`, RULE-01-02/03/05, FSM 1 |
| 3 | `POST /auth/otp/resend` | `ResendOTP` — `01#1`, RULE-01-04/05 |
| 4 | `POST /auth/login` | `Login` — `01#1`, RULE-01-01/07, FSM 1 guards |
| 5 | `POST /auth/refresh` | Session/token-layer infra (không có Command Candidate riêng trong `04-glossary.md`) — ADR-0001, RULE-01-06/02-04 |
| 6 | `POST /auth/logout` | `Logout` — `01#1`, RULE-01-06 |
| 7 | `POST /staff-accounts` (proposed path, ranh giới Auth × IAM) | `CreateStaff` D-04 — `01#1`, RULE-01-03, RULE-02-05, FSM 1 |

---

## B. API Design Matrix

| Operation | Actor | Domain | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Dependencies | External Effects | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RegisterAccount | Customer | Auth (01), Agg `Account` | — | RULE-01-10 (email unique, sửa 2026-09-13), RULE-01-02 (OTP TTL 300s), RULE-01-03 (→PENDING) | POST | `/auth/register` | Public | None (self-registration) | `[*] → PENDING_VERIFICATION`, ev `AccountRegistered` | Notification (OTP delivery) | OTP gửi qua Gmail SMTP (module/notification tối giản) | Không có idempotency key trong docs; email UK → retry trùng email = lỗi nghiệp vụ (BUSINESS_RULE_VIOLATION), **không** đảm bảo idempotent. TBD Q8 | Req fields từ ERD (sửa 2026-09-13): email req + UK (RULE-01-10); phone optional UK; password req (hash) CONFIRMED, policy TBD Q5; name ASSUMPTION A3 |
| VerifyOTP | Customer | Auth (01) | Account ở `PENDING_VERIFICATION`, OTP còn hạn | RULE-01-02 (TTL 300s), RULE-01-03, RULE-01-05 (5 sai → vô hiệu + lock 15p) | POST | `/auth/verify-otp` | Public | Owner (đúng email của OTP — đổi từ phone, RULE-01-10 sửa 2026-09-13) | `PENDING_VERIFICATION → ACTIVE`, ev `AccountActivated`. **Không** phát hành token (docs không yêu cầu) | — | — | Non-idempotent (mỗi lần sai đều đếm attempt) | OTP 6 số CONFIRMED (`05#4.1` VO `OtpCode`); `purpose` FIX server-side = REGISTRATION, không expose (design decision); lấy OTP qua pessimistic lock `(email, purpose)` chống double-click |
| ResendOTP | Customer | Auth (01) | Account ở `PENDING_VERIFICATION` | RULE-01-04 (vô hiệu OTP cũ, cooldown 60s, OTP mới TTL 5p), RULE-01-05 (max 5/h) | POST | `/auth/otp/resend` | Public | Owner | Không đổi state (ở yên `PENDING_VERIFICATION`) | Notification | OTP mới gửi đi | Cố ý **non-idempotent** (mỗi call đẻ OTP mới + kill OTP cũ — đúng RULE-01-04) | Rate-limit counters là derived requirement để enforce rule |
| Login | Customer / Staff | Auth (01) | Credentials đúng, Account `ACTIVE` (hoặc `LOCKED`/`AUTO_FAILED_LOGIN` đã hết `locked_until` → auto-unlock trước khi check) | RULE-01-01 (chỉ ACTIVE + phát hành JWT/Session Token), RULE-01-07 (5 sai liên tiếp → LOCKED qua `AutoLockAccount`; `LOCKED → ACTIVE` qua `AutoUnlockAccount` khi hết `locked_until` **hoặc** qua `UnlockAccount` thủ công — FSM-1 Technical Invariant #5), RULE-02-04 (LOCKED từ chối mọi op) | POST | `/auth/login` | Public | Self (đúng credentials) | Thành công: không đổi state (trừ khi vừa auto-unlock trước đó). Thất bại lần 5 liên tiếp: `ACTIVE → LOCKED` via `AutoLockAccount`, ev `AccountLocked` | — | — | Non-idempotent (mỗi success đẻ session mới; mỗi fail đều đếm) | Identifier = email (RULE-01-10, sửa 2026-09-14, thay A1 cũ); Bearer transport ASSUMPTION A2; TTL access token = 15p (ADR-0002, CONFIRMED); `role` đơn là A7 |
| Refresh | Customer / Staff | Auth (01), session/token layer | Refresh token hợp lệ, chưa hết hạn/chưa revoke, Account đang `ACTIVE` | ADR-0001 (rotation: 1 refresh token dùng 1 lần), RULE-01-01/07 (re-check trạng thái Account tại thời điểm refresh — chặn refresh nếu đã LOCKED/DEACTIVATED sau khi token cũ phát hành) | POST | `/auth/refresh` | Public (refresh token tự chứng thực, không cần Bearer access) | Owner (đúng userId trong refresh token) | Không đổi Account state; chỉ rotate token pair | — | — | Non-idempotent (mỗi lần gọi rotate sang cặp token mới, refresh token cũ bị revoke — reuse bị từ chối `401 INVALID_REFRESH_TOKEN`) | Không có RULE-ID/FSM riêng — thuần session/token infra (ADR-0001), giống Logout |
| Logout | Customer / Staff | Auth (01) | Đang có session | RULE-01-06 (revoke session + refresh, blacklist access) | POST | `/auth/logout` | Bearer access | Owner session | Không đổi Account state | — | — | **Idempotent** (logout lặp lại vẫn 200) | Cơ chế định danh session từ access token + body `refreshToken?` là PROPOSED |
| CreateStaff D-04 | PlatformAdmin / OrganizationAdmin | Auth (01) × IAM (02), Agg `Account` + `UserAccount` | Actor có quyền quản trị trên scope mục tiêu | RULE-01-03/D-04 (`→ACTIVE` + temp password + `must_change_password=true`, bỏ OTP), RULE-02-02 (9 canonical roles), RULE-02-05 (hierarchy) | POST | `/staff-accounts` | Bearer access | `SUPER_ADMIN` toàn hệ thống; `ORGANIZATION_ADMIN` trong Org mình (RULE-02-05 CONFIRMED) | `[*] → ACTIVE`, ev `AccountActivated` | — | (Có thể gửi temp password cho staff — kênh TBD Q7) | Như register: email UK → trùng = `BUSINESS_RULE_VIOLATION`/400 (RULE-01-10, sửa 2026-09-13, không dùng 409); không idempotency key trong docs | Role enum 9 giá trị CONFIRMED; tổ hợp org/store bắt buộc theo role TBD Q9; audit TBD Q10; endpoint này **chưa triển khai code** (ngoài phạm vi Module 01 lần này) |

**ASSUMPTIONS dùng chung:** A1 login identifier = phone hoặc email (ERD cả 2 UK;
ops chỉ nói "Credentials") · A2 transport = Bearer JWT (RULE-01-01 chỉ nói
JWT/Session Token) · A3 `name` thu ở register (ERD `users.full_name` NOT NULL
nhưng ops không nói thu ở bước nào) · A4 counter fail-login reset khi success
(chữ "liên tiếp" ở RULE-01-07) · ~~A5~~ **SỬA 2026-09-14** — `accounts.locked_until`
**CÓ** cơ chế tự động mở khóa (`AutoUnlockAccount`, RULE-01-07 + FSM-1 Technical
Invariant #5 CONFIRMED), nhưng CHỈ áp dụng `lock_reason = AUTO_FAILED_LOGIN`;
`lock_reason = ADMIN_LOCK` thì đúng như A5 cũ (chỉ `UnlockAccount` thủ công).
Implementation kiểm tra điều kiện auto-unlock ngay tại lần `Login`/`Refresh` kế
tiếp (lazy, không cần job nền riêng) — xem C4/C5. Assumption A5 bản gốc (loại
suy "ERD mâu thuẫn FSM-1, không có auto-unlock") là **sai**, đã sửa · A6 resend cho account đã ACTIVE
→ từ chối (hiển nhiên từ FSM) · A7 login `role` trả về là role chính
(`users.role`); đa-role qua `user_roles` chưa đưa vào v1.

---

## C. Detailed endpoint contract

### C1. `POST /auth/register` (proposed)

- **Purpose:** tạo Account Customer + kích hoạt gửi OTP đăng ký.
- **Auth:** Public. **Authorization:** none.
- **Request:** `{email: string (bắt buộc + duy nhất — RULE-01-10, sửa 2026-09-13),
  phone?: string (10 số — optional, VO `05#4.1`), password: string (policy TBD Q5),
  name: string (A3)}`.
- **Response 201:** `{accountId: uuid, status: "PENDING_VERIFICATION"}`.
  Không trả OTP code (docs không cho phép lộ).
- **Status:** `201` created · `400` validation/business-rule (theo convention
  `docs/convention/backend/04-exception-handling`: Bean Validation →
  `VALIDATION_FAILED`, RULE-ID → `BUSINESS_RULE_VIOLATION`) · `400`
  email (hoặc phone, nếu có) đã tồn tại — **DECIDED 2026-09-13:** dùng
  `BusinessRuleViolationException`/`BUSINESS_RULE_VIOLATION` (400), **không**
  dùng `409`, để bám sát đúng 5 exception chuẩn của convention và không phải
  mở exception/HTTP-status mới chỉ cho case này (xem Decision Log
  `docs/02-business-rules.md` mục 01). Race giữa 2 request cùng email (double
  submit) vẫn được UNIQUE constraint ở DB chặn — Service bắt
  `DataIntegrityViolationException` và ném lại đúng cùng
  `BusinessRuleViolationException`. Không có `429` cho register
  (RULE-01-05 chỉ áp dụng cho resend/verify) và không dùng `422`
  (convention dùng `400`).
- **Error codes:** `VALIDATION_FAILED`, `BUSINESS_RULE_VIOLATION` (RULE-01-09
  password ngắn, RULE-01-10 email/phone đã tồn tại). Không dùng
  `PHONE_ALREADY_EXISTS`/`EMAIL_ALREADY_EXISTS`/`OTP_SEND_FAILED` riêng (đã
  loại theo cùng quyết định 2026-09-13 — gửi OTP lỗi không throw lên API, xem
  `module/notification` NotificationServiceImpl.dispatch()).
  Envelope theo convention: `{success, errorCode, message, statusCode, timestamp, traceId}` (xem Q2).
- **Validation:** email bắt buộc + unique ecosystem-wide (RULE-01-10, sửa 2026-09-13); phone (nếu có) 10 số + unique nếu có;
  password ≥ 8 ký tự (RULE-01-09), không áp thêm policy phức tạp cho đến Q5.
- **Idempotency:** không có key theo docs; retry cùng email → `BUSINESS_RULE_VIOLATION` (400). TBD Q8.
- **State transition:** `[*] → PENDING_VERIFICATION` + `AccountRegistered` (FSM 1 CONFIRMED).

### C2. `POST /auth/verify-otp` (proposed)

- **Purpose:** xác thực OTP, kích hoạt ACTIVE. Không login.
- **Auth:** Public. **Authorization:** email trong request phải khớp email của OTP session (đổi từ phone — RULE-01-10, sửa 2026-09-13).
- **Request:** `{email, otpCode: string (6 số — CONFIRMED)}` (đổi từ `phone` — RULE-01-10, sửa 2026-09-13).
- **Response 200:** `{accountId, status: "ACTIVE"}`. Không token.
- **Status (DECIDED 2026-09-13 — bám đúng 5 exception convention, KHÔNG dùng
  410/423/429 như bản đề xuất trước; xem Decision Log `docs/02-business-rules.md`
  mục 01):** `200` · `400 VALIDATION_FAILED` sai format · `400
  BUSINESS_RULE_VIOLATION` cho mọi guard OTP — sai mã/hết hạn (RULE-01-02),
  đang bị khoá phiên xác thực (RULE-01-05) — message luôn nhúng đúng RULE-ID,
  KHÔNG có field `remainingAttempts`/`lockedUntil` riêng trong response ·
  `404 RESOURCE_NOT_FOUND` không tìm thấy OTP/account theo email.
- **Validation:** OTP đúng email (đổi từ phone — RULE-01-10, sửa 2026-09-13), chưa `is_used` (ERD CONFIRMED),
  `now ≤ expires_at` (RULE-01-02 CONFIRMED; RULE-01-08 chỉ là job nền `ExpireOTP`,
  không phải guard), `attempt_count < 5` (RULE-01-05 CONFIRMED). Lấy OTP qua
  pessimistic lock (`SELECT ... FOR UPDATE`) theo `(email, purpose)` để 2 request
  verify đồng thời (double-click) không đua nhau tăng `attempt_count`/kích
  hoạt Account 2 lần — request thua chờ rồi đọc lại state mới, nhận đúng lỗi
  nghiệp vụ (không lỗi 500).
- **Idempotency:** non-idempotent (mỗi attempt đều đếm).
- **State transition:** `PENDING_VERIFICATION → ACTIVE` + `AccountActivated`;
  verify khi Account đã ACTIVE → `409 INVALID_STATE_TRANSITION` (FSM 1
  `AccountTransitionHandler` từ chối `ACTIVE→ACTIVE` — mã chuẩn convention,
  không phải `ACCOUNT_ALREADY_ACTIVE` tự chế).

### C3. `POST /auth/otp/resend` (proposed)

- **Purpose:** vô hiệu OTP cũ + phát hành OTP mới (op riêng theo `ResendOTP`).
- **Auth:** Public. **Authorization:** owner (email).
- **Request:** `{email}` (đổi từ `phone` — RULE-01-10, sửa 2026-09-13). **Response 200:** `{accountId, status: "PENDING_VERIFICATION"}`.
- **Status (DECIDED 2026-09-13 — cùng quyết định như C2, không dùng 409/429):**
  `200` · `404 RESOURCE_NOT_FOUND` (như C2) · `400 BUSINESS_RULE_VIOLATION` cho
  mọi guard: account đã ACTIVE (RULE-01-03, thay A6), đang bị khoá phiên xác
  thực (RULE-01-05), cooldown 60s chưa qua (RULE-01-04), vượt 5 lần/giờ
  (RULE-01-05). Không có header `Retry-After` riêng — message nhúng RULE-ID.
- **Validation:** chỉ khi account còn PENDING (RULE-01-03), cùng pessimistic
  lock trên OTP như C2 để chặn double-click resend.
- **Idempotency:** cố ý non-idempotent.
- **State transition:** none.

### C4. `POST /auth/login` (implemented 2026-09-14)

- **Purpose:** xác thực credentials + phát hành token pair.
- **Auth:** Public. **Authorization:** đúng credentials của account.
- **Request:** `{email (RULE-01-10 — email là danh tính đăng nhập duy nhất, KHÔNG
  còn nhận phone; sửa 2026-09-14, thay cho assumption A1 "phone hoặc email" trước đó),
  password}`.
- **Response 200:** `{accessToken, refreshToken (cặp này CONFIRMED tồn tại theo
  RULE-01-06/02-04), tokenType ("Bearer"), expiresIn (giây, lấy từ access-token TTL —
  ADR-0002)}`. **Không** có field `user` — quyết định 2026-09-14: userId/accountId/role
  đã có sẵn trong claim của accessToken (giải mã JWT phía FE), trả thêm ở JSON body là
  thừa và lộ thông tin không cần thiết. `mustChangePassword` (RULE-01-03) cũng tạm bỏ
  khỏi response vì chưa có consumer thật (CreateStaff/Module 02 chưa triển khai) — sẽ bổ
  sung lại (claim JWT hoặc field riêng) khi luồng Staff first-login được xây.
- **Status:** `200` · `400` · `401` credentials sai (`INVALID_CREDENTIALS`,
  message chung chung chống enumeration) · `403` chưa ACTIVE (`ACCOUNT_NOT_ACTIVE`:
  PENDING/DEACTIVATED — RULE-01-01 CONFIRMED) · `423` đang LOCKED (`ACCOUNT_LOCKED`,
  message nhúng `lockedUntil` — lock CONFIRMED; vẫn 423 cho đến khi hết `locked_until`
  (AUTO_FAILED_LOGIN, tự kiểm tra ở lần login kế tiếp) hoặc Admin `UnlockAccount`) ·
  `429` brute-force — KHÔNG triển khai (không có RULE-ID yêu cầu rate-limit theo IP,
  chỉ có khoá theo account RULE-01-07).
- **Validation:** email/password bắt buộc (`@Email`, `@NotBlank`).
- **Idempotency:** non-idempotent.
- **State transition:** success → none (counter reset A4). Trước khi check mật
  khẩu: nếu Account đang `LOCKED` với `lock_reason = AUTO_FAILED_LOGIN` và
  `now() >= locked_until`, hệ thống tự `LOCKED → ACTIVE` via `AutoUnlockAccount`
  (System, RULE-01-07 + FSM-1 Technical Invariant #5 CONFIRMED) rồi mới tiếp tục
  xử lý như đang `ACTIVE` — kiểm tra lazy ngay tại lần `Login` kế tiếp, không cần
  job nền riêng. `lock_reason = ADMIN_LOCK` KHÔNG đủ điều kiện auto-unlock (chỉ
  `UnlockAccount` thủ công). Fail thứ 5 liên tiếp → `ACTIVE → LOCKED` via
  `AutoLockAccount` + `AccountLocked`.

### C5. `POST /auth/refresh` (implemented 2026-09-14)

- **Purpose:** làm mới access token bằng refresh token còn hiệu lực, rotate
  sang cặp token mới (ADR-0001).
- **Auth:** Public (refresh token trong body tự chứng thực danh tính — không
  cần header `Authorization`). **Authorization:** owner (đúng `userId` mã hoá
  trong refresh token).
- **Request:** `{refreshToken: string}` (bắt buộc).
- **Response 200:** `{accessToken, refreshToken (token MỚI — token cũ bị revoke
  ngay sau khi rotate, không dùng lại được), tokenType: "Bearer", expiresIn
  (giây, ADR-0002)}`. Cùng shape với `LoginResponse` (không có `user`).
- **Status:** `200` · `400 VALIDATION_FAILED` thiếu `refreshToken` · `401
  INVALID_REFRESH_TOKEN` (JWT malformed/hết hạn, sai `type` — không phải
  refresh token, hoặc không tìm thấy/đã bị revoke trong `refresh_tokens` —
  bao gồm cả reuse-detection: dùng lại token đã rotate) · `403
  ACCOUNT_NOT_ACTIVE` (Account đã `PENDING_VERIFICATION`/`DEACTIVATED` kể từ
  lúc refresh token được cấp) · `423 ACCOUNT_LOCKED` (Account đã bị khoá kể từ
  lúc refresh token được cấp).
- **Validation:** `refreshToken` bắt buộc; phải là JWT hợp lệ với claim
  `type = "refresh"`.
- **Idempotency:** non-idempotent (mỗi lần gọi tạo cặp token mới, revoke token
  cũ — gọi lại với cùng refresh token cũ sau đó sẽ luôn `401`).
- **State transition:** không đổi Account state; chỉ thao tác tầng
  session/token (Postgres `refresh_tokens`: revoke token cũ + insert token mới
  liên kết `replaced_by` — ADR-0001). Account `LOCKED`/`DEACTIVATED` tại thời
  điểm refresh sẽ bị chặn (re-check trạng thái, không tin tưởng mù quáng claim
  cũ trong token).

### C6. `POST /auth/logout` (proposed)

- **Purpose:** thu hồi session theo RULE-01-06.
- **Auth:** Bearer access (A2). **Authorization:** owner session.
- **Request:** `{refreshToken?: string}` (PROPOSED — để revoke đúng refresh;
  không có thì revoke theo access).
- **Response 200:** `{revoked: true}` (shape PROPOSED).
- **Status:** `200` (kể cả token đã hết hạn/revoked — idempotent) ·
  `401` access thiếu/sai định dạng.
- **Validation:** none ngoài auth.
- **Idempotency:** idempotent.
- **State transition:** none (chỉ session/token layer: revoke + blacklist — RULE-01-06 CONFIRMED).

### C7. `POST /staff-accounts` (proposed)

- **Purpose:** Admin provisioning staff ACTIVE + temp password (D-04).
- **Auth:** Bearer access. **Authorization:** `SUPER_ADMIN` (mọi scope) /
  `ORGANIZATION_ADMIN` (trong Org) — RULE-02-05 CONFIRMED; StoreManager không có quyền này.
- **Request:** `{email (req + UK — RULE-01-10, sửa 2026-09-13), phone? (UK), password (temp, req CONFIRMED,
  policy TBD Q5), name (A3), role (req; 1 trong 9 CONFIRMED), organizationId?/storeId?
  (scoping TBD Q9)}`.
- **Response 201:** `{accountId, userId, status: "ACTIVE", mustChangePassword: true}`
  (flag CONFIRMED RULE-01-03).
- **Status:** `201` · `400` · `401` · `403 ACCESS_DENIED_SCOPE_MISMATCH`
  (mã CONFIRMED từ RULE-02-01) · `400 BUSINESS_RULE_VIOLATION` trùng email/phone
  (đồng bộ quyết định register 2026-09-13, không dùng 409 — **chưa triển khai
  code, endpoint này ngoài phạm vi Module 01 lần triển khai này**).
- **Validation:** như register + role/scope check.
- **Idempotency:** như register (BUSINESS_RULE_VIOLATION, TBD Q8).
- **State transition:** `[*] → ACTIVE` + `AccountActivated`.

---

## D. Security & reliability (chỉ điểm có gốc docs)

1. Không bao giờ trả `password_hash` / `otp_code` (suy trực tiếp từ tính chất
   credential/OTP — docs không cho phép lộ).
2. Enforce có gốc rule: OTP TTL 300s, cooldown 60s, 5 resend/h, 5 sai → lock 15p,
   5 login sai → LOCKED ≥15p (tự mở khóa khi hết 15p — RULE-01-07,
   `AutoUnlockAccount`), revoke + blacklist khi logout/lock/deactivate,
   refresh token rotate 1-lần-dùng (ADR-0001), scope hierarchy, role enum.
3. Concurrency (derived requirements để enforce rule, không phải business mới —
   xem giải trình đầy đủ tại plan triển khai Module 01, mục "Concurrency &
   Idempotency"): `attempt_count`/`is_used`/`locked_until` trên `otps` đọc-sửa
   qua `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) theo `(email, purpose)` —
   2 request verify/resend đồng thời bị serialize, không lost-update, không
   double-activate Account; resend invalidate-cũ + insert-mới atomic (RULE-01-04
   "lập tức"); verify đổi state + đánh dấu `is_used` cùng transaction; email
   unique bằng DB constraint, Service bắt `DataIntegrityViolationException`
   khi race và ném lại `BusinessRuleViolationException`/400 (không để lộ 500,
   không dùng 409 cho case này — quyết định 2026-09-13).
4. Không suy diễn thêm: không lockout IP, không CAPTCHA, không device binding
   (docs không có → TBD Q11).
5. `ExpireOTP` là job nền (RULE-01-08) — BE cần scheduler/worker nhưng không có API.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning (`/api` vs `/api/v1`) | TBD (PO) | docs không có URL |
| Q2 | Response envelope + error shape | DECIDED — theo `docs/convention/backend/04-exception-handling`: `{success, errorCode, message, statusCode, timestamp, traceId}`; `ACCESS_DENIED_SCOPE_MISMATCH` → 403 CONFIRMED; `410`/`423`/`429` là PROPOSED extensions chưa có trong convention | convention + RULE-02-01 |
| Q3 | Có expose `POST /auth/refresh` không? | **DECIDED 2026-09-14** — có, xem C5. Không có RULE-ID/FSM riêng (đúng như nhận định gốc), nhưng đủ contract để triển khai như session/token infra (giống Logout): rotation 1-lần-dùng theo ADR-0001, re-check `AccountStatus` tại thời điểm refresh dựa trên RULE-01-01/07 hiện có — không cần rule mới | ADR-0001, RULE-01-01/07 |
| Q4 | Access/refresh TTL + rotation policy + reuse-handling | **DECIDED một phần 2026-09-14** — TTL: access 15 phút (ADR-0002), refresh 30 ngày (ADR-0001, `jwt.refresh-token-ttl-days`); rotation: 1-lần-dùng, reuse bị từ chối `401` (ADR-0001). **Sửa nhận định cũ:** ERD `accounts.locked_until` = "mở khóa tự động" **KHÔNG** mâu thuẫn FSM-1 — FSM-1 Technical Invariant #5 xác nhận `AutoUnlockAccount` là cơ chế thật (chỉ áp dụng `lock_reason = AUTO_FAILED_LOGIN`), premise "FSM-1 manual-only" của Q4 bản gốc là sai, đã sửa (xem A5 ở mục B) | ADR-0001, ADR-0002, RULE-01-07, FSM-1 §Technical Invariant #5 |
| Q5 | Password policy (độ dài, complexity) + temp-password generation/distribution | TBD (PO) | ERD chỉ yêu cầu hash tồn tại |
| Q6 | `must_change_password` enforcement: flag FE hay BE chặn cứng mọi API? | TBD (PO) | invariant chỉ nói "yêu cầu đổi ở lần đầu" |
| Q7 | Kênh gửi OTP (SMS/email/app) + behavior khi gửi thất bại + mock OTP ở dev | TBD (PO) | docs nói OTP cho phone/email nhưng không chốt kênh register |
| Q8 | Idempotency keys cho register/createStaff (hiện chỉ có 409 nhờ UK) | TBD (PO) | docs không có key |
| Q9 | Ma trận role × scope bắt buộc cho CreateStaff (role nào cần org/store, CUSTOMER có tạo qua đây không) | TBD (PO) | RULE-02-02/05 cho khung, thiếu ma trận chi tiết |
| Q10 | Audit log cho auth (register/verify/login/createStaff) — chỉ ReactivateAccount bắt buộc reason + audit (FSM 1) | TBD (PO) | FSM 1 + Module 25 |
| Q11 | Anti-enumeration (404 vs 401 gộp), brute-force thresholds IP-level, CAPTCHA | TBD (PO) | docs chỉ có ngưỡng phone-level |

---

## F. OpenAPI 3.1 YAML

Single source of truth cho contract máy đọc: [`./openapi/auth-v1.yaml`](./openapi/auth-v1.yaml).
Mọi TBD (Q1, Q3–Q11) được giữ trong `description`, không bịa giá trị (không `servers`,
không `expiresIn`, không password constraints). Q2 đã DECIDED theo backend convention
(error envelope thống nhất toàn BE).
