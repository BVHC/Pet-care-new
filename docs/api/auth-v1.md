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

**Đóng băng phạm vi:**
- `CheckOTP` / `ExpireOTP` là system-internal, không expose API.
- Lock / Unlock / Deactivate thuộc IAM, không đưa vào Auth API. Ngoại lệ duy nhất:
  `AutoLockAccount` (System, RULE-01-07) là *hiệu ứng* của `Login` thất bại liên tiếp —
  contract Auth mô tả hiệu ứng này nhưng không expose endpoint riêng.
- Forgot / Reset Password **loại khỏi v1** (ERD có `otps.purpose = PASSWORD_RESET`
  nhưng business operations chưa xác nhận các operation này).
- Verify OTP tuân FSM (`PENDING_VERIFICATION → ACTIVE`), không auto-login.
- Resend OTP là endpoint riêng (`ResendOTP` là operation riêng).
- Refresh token: docs nhắc tới (RULE-01-06, RULE-02-04) nhưng **không** có
  operation/FSM/rule cho rotation/reuse → chưa đủ contract, loại khỏi confirmed API (xem Q3).

---

## A. Confirmed Auth API (6 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /auth/register` | `RegisterAccount` + hiệu ứng `SendRegistrationOTP` — `01#1`, RULE-01-01/02/03 |
| 2 | `POST /auth/verify-otp` | `VerifyOTP` — `01#1`, RULE-01-02/03/05, FSM 1 |
| 3 | `POST /auth/otp/resend` | `ResendOTP` — `01#1`, RULE-01-04/05 |
| 4 | `POST /auth/login` | `Login` — `01#1`, RULE-01-01/07, FSM 1 guards |
| 5 | `POST /auth/logout` | `Logout` — `01#1`, RULE-01-06 |
| 6 | `POST /staff-accounts` (proposed path, ranh giới Auth × IAM) | `CreateStaff` D-04 — `01#1`, RULE-01-03, RULE-02-05, FSM 1 |

---

## B. API Design Matrix

| Operation | Actor | Domain | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Dependencies | External Effects | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| RegisterAccount | Customer | Auth (01), Agg `Account` | — | RULE-01-01 (phone unique), RULE-01-02 (OTP TTL 300s), RULE-01-03 (→PENDING) | POST | `/auth/register` | Public | None (self-registration) | `[*] → PENDING_VERIFICATION`, ev `AccountRegistered` | Notification (OTP delivery) | OTP gửi qua kênh ngoài (kênh cụ thể TBD Q7) | Không có idempotency key trong docs; phone UK → retry trùng phone = 409, **không** đảm bảo idempotent. TBD Q8 | Req fields từ ERD: phone req + UK CONFIRMED; email optional CONFIRMED; password req (hash) CONFIRMED, policy TBD Q5; name ASSUMPTION A3 |
| VerifyOTP | Customer | Auth (01) | Account ở `PENDING_VERIFICATION`, OTP còn hạn | RULE-01-02 (TTL 300s), RULE-01-03, RULE-01-05 (5 sai → vô hiệu + lock 15p) | POST | `/auth/verify-otp` | Public | Owner (đúng phone của OTP) | `PENDING_VERIFICATION → ACTIVE`, ev `AccountActivated`. **Không** phát hành token (docs không yêu cầu) | — | — | Non-idempotent (mỗi lần sai đều đếm attempt) | OTP 6 số CONFIRMED (`05#4.1` VO `OtpCode`); `purpose` FIX server-side = REGISTRATION, không expose (design decision) |
| ResendOTP | Customer | Auth (01) | Account ở `PENDING_VERIFICATION` | RULE-01-04 (vô hiệu OTP cũ, cooldown 60s, OTP mới TTL 5p), RULE-01-05 (max 5/h) | POST | `/auth/otp/resend` | Public | Owner | Không đổi state (ở yên `PENDING_VERIFICATION`) | Notification | OTP mới gửi đi | Cố ý **non-idempotent** (mỗi call đẻ OTP mới + kill OTP cũ — đúng RULE-01-04) | Rate-limit counters là derived requirement để enforce rule |
| Login | Customer / Staff | Auth (01) | Credentials đúng, Account `ACTIVE` | RULE-01-01 (chỉ ACTIVE + phát hành JWT/Session Token), RULE-01-07 (5 sai liên tiếp → LOCKED qua `AutoLockAccount`; mở khóa thủ công qua `UnlockAccount` — FSM-1 invariant #5, KHÔNG auto-unlock), RULE-02-04 (LOCKED từ chối mọi op) | POST | `/auth/login` | Public | Self (đúng credentials) | Thành công: không đổi state. Thất bại lần 5 liên tiếp: `ACTIVE → LOCKED` via `AutoLockAccount`, ev `AccountLocked`. `LOCKED → ACTIVE` chỉ qua `UnlockAccount` (IAM, ngoài scope) | — | — | Non-idempotent (mỗi success đẻ session mới; mỗi fail đều đếm) | Identifier phone-hoặc-email ASSUMPTION A1; Bearer transport ASSUMPTION A2; TTL token TBD Q4; `locked_until` là ngưỡng sớm nhất cho Unlock (A5); `role` đơn là A7 |
| Logout | Customer / Staff | Auth (01) | Đang có session | RULE-01-06 (revoke session + refresh, blacklist access) | POST | `/auth/logout` | Bearer access | Owner session | Không đổi Account state | — | — | **Idempotent** (logout lặp lại vẫn 200) | Cơ chế định danh session từ access token + body `refreshToken?` là PROPOSED |
| CreateStaff D-04 | PlatformAdmin / OrganizationAdmin | Auth (01) × IAM (02), Agg `Account` + `UserAccount` | Actor có quyền quản trị trên scope mục tiêu | RULE-01-03/D-04 (`→ACTIVE` + temp password + `must_change_password=true`, bỏ OTP), RULE-02-02 (9 canonical roles), RULE-02-05 (hierarchy) | POST | `/staff-accounts` | Bearer access | `SUPER_ADMIN` toàn hệ thống; `ORGANIZATION_ADMIN` trong Org mình (RULE-02-05 CONFIRMED) | `[*] → ACTIVE`, ev `AccountActivated` | — | (Có thể gửi temp password cho staff — kênh TBD Q7) | Như register: phone UK → trùng = 409; không idempotency key trong docs | Role enum 9 giá trị CONFIRMED; tổ hợp org/store bắt buộc theo role TBD Q9; audit TBD Q10 |

**ASSUMPTIONS dùng chung:** A1 login identifier = phone hoặc email (ERD cả 2 UK;
ops chỉ nói "Credentials") · A2 transport = Bearer JWT (RULE-01-01 chỉ nói
JWT/Session Token) · A3 `name` thu ở register (ERD `users.full_name` NOT NULL
nhưng ops không nói thu ở bước nào) · A4 counter fail-login reset khi success
(chữ "liên tiếp" ở RULE-01-07) · A5 `accounts.locked_until` là *ngưỡng sớm nhất*
cho phép `UnlockAccount` thủ công (FSM-1 invariant #5), KHÔNG phải auto-unlock —
login vẫn `423` cho đến khi Admin unlock (ERD mô tả cột này là "mở khóa tự động"
là chưa khớp FSM, xem Q4) · A6 resend cho account đã ACTIVE
→ từ chối (hiển nhiên từ FSM) · A7 login `role` trả về là role chính
(`users.role`); đa-role qua `user_roles` chưa đưa vào v1.

---

## C. Detailed endpoint contract

### C1. `POST /auth/register` (proposed)

- **Purpose:** tạo Account Customer + kích hoạt gửi OTP đăng ký.
- **Auth:** Public. **Authorization:** none.
- **Request:** `{phone: string (10 số — CONFIRMED `05#4.1` VO), email?: string,
  password: string (policy TBD Q5), name: string (A3)}`.
- **Response 201:** `{accountId: uuid, status: "PENDING_VERIFICATION"}`.
  Không trả OTP code (docs không cho phép lộ).
- **Status:** `201` created · `400` validation/business-rule (theo convention
  `docs/convention/backend/04-exception-handling`: Bean Validation →
  `VALIDATION_FAILED`, RULE-ID → `BUSINESS_RULE_VIOLATION`) · `409` phone/email
  đã tồn tại (ERD UK CONFIRMED; conflicts → `INVALID_STATE_TRANSITION` /
  `CONCURRENCY_CONFLICT` theo convention). Không có `429` cho register
  (RULE-01-05 chỉ áp dụng cho resend/verify) và không dùng `422`
  (convention dùng `400`).
- **Error codes (PROPOSED, trừ khi ghi chú):** `VALIDATION_FAILED`,
  `BUSINESS_RULE_VIOLATION`, `PHONE_ALREADY_EXISTS`, `EMAIL_ALREADY_EXISTS`,
  `OTP_SEND_FAILED`
  (Notification downstream fail — behavior khi SMS fail TBD Q7).
  Envelope theo convention: `{success, errorCode, message, statusCode, timestamp, traceId}` (xem Q2).
- **Validation:** phone 10 số + unique ecosystem-wide (RULE-01-01 CONFIRMED);
  email unique nếu có (ERD UK); password bắt buộc, không áp policy cho đến Q5.
- **Idempotency:** không có key theo docs; retry cùng phone → 409. TBD Q8.
- **State transition:** `[*] → PENDING_VERIFICATION` + `AccountRegistered` (FSM 1 CONFIRMED).

### C2. `POST /auth/verify-otp` (proposed)

- **Purpose:** xác thực OTP, kích hoạt ACTIVE. Không login.
- **Auth:** Public. **Authorization:** phone trong request phải khớp phone của OTP session.
- **Request:** `{phone, otpCode: string (6 số — CONFIRMED)}`.
- **Response 200:** `{accountId, status: "ACTIVE"}`. Không token.
- **Status:** `200` · `400` sai format · `401` OTP sai còn lượt
  (`OTP_INVALID` + `remainingAttempts` — shape PROPOSED) · `410` hết hạn
  (`OTP_EXPIRED`, TTL 300s CONFIRMED) · `423` phiên bị lock 15p
  (`OTP_VERIFICATION_LOCKED` + `lockedUntil` — lock 15p CONFIRMED, mã string PROPOSED) ·
  `404` không tìm thấy OTP/account (có thể gộp 404→401 chống enumeration — TBD Q11).
- **Validation:** OTP đúng phone, chưa `is_used` (ERD CONFIRMED),
  `now ≤ expires_at` (RULE-01-02 CONFIRMED; RULE-01-08 chỉ là job nền `ExpireOTP`,
  không phải guard), `attempt_count < 5` (RULE-01-05 CONFIRMED).
- **HTTP-semantics note:** `410`/`423`/`429` là PROPOSED extensions — convention
  `04-exception-handling` hiện chưa có mapping cho chúng.
- **Idempotency:** non-idempotent (mỗi attempt đều đếm).
- **State transition:** `PENDING_VERIFICATION → ACTIVE` + `AccountActivated`;
  verify khi đã ACTIVE → `409 ACCOUNT_ALREADY_ACTIVE` (PROPOSED, suy từ FSM).

### C3. `POST /auth/otp/resend` (proposed)

- **Purpose:** vô hiệu OTP cũ + phát hành OTP mới (op riêng theo `ResendOTP`).
- **Auth:** Public. **Authorization:** owner (phone).
- **Request:** `{phone}`. **Response 200:** `{accountId, status: "PENDING_VERIFICATION"}`.
- **Status:** `200` · `404` (như C2, TBD Q11) · `409` account đã ACTIVE (A6) ·
  `429` cooldown 60s (`OTP_RESEND_TOO_FAST` + header `Retry-After` — 60s CONFIRMED,
  mã/header PROPOSED) / quá 5/h (`OTP_RESEND_LIMIT` — 5/h CONFIRMED).
- **Validation:** chỉ khi account còn PENDING (A6).
- **Idempotency:** cố ý non-idempotent.
- **State transition:** none.

### C4. `POST /auth/login` (proposed)

- **Purpose:** xác thực credentials + phát hành token pair.
- **Auth:** Public. **Authorization:** đúng credentials của account.
- **Request:** `{identifier (A1: phone hoặc email), password}`.
- **Response 200:** `{accessToken, refreshToken (cặp này CONFIRMED tồn tại theo
  RULE-01-06/02-04; TTL TBD Q4 nên response KHÔNG có `expiresIn`), tokenType (A2 "Bearer"),
  user: {userId, accountId, role (role chính theo A7; 9 enum CONFIRMED), mustChangePassword
  (CONFIRMED need từ RULE-01-03)}}`.
- **Status:** `200` · `400` · `401` credentials sai (`INVALID_CREDENTIALS` PROPOSED,
  message chung chung chống enumeration) · `403` chưa ACTIVE (`ACCOUNT_NOT_ACTIVE`:
  PENDING/DEACTIVATED — RULE-01-01 CONFIRMED) / `mustChangePassword` chỉ là flag,
  enforcement TBD Q6 · `423` đang LOCKED (`ACCOUNT_LOCKED` + `lockedUntil` — lock
  CONFIRMED; vẫn 423 cho đến khi Admin `UnlockAccount`, KHÔNG auto-unlock theo
  FSM-1 invariant #5) · `429` brute-force (PROPOSED, convention chưa có mapping — TBD Q11).
- **Validation:** identifier/password bắt buộc; chuẩn hóa (trim; email lowercase — PROPOSED).
- **Idempotency:** non-idempotent.
- **State transition:** success → none (counter reset A4); fail thứ 5 liên tiếp →
  `ACTIVE → LOCKED` via `AutoLockAccount` (System, RULE-01-07 CONFIRMED) +
  `AccountLocked`. KHÔNG có auto-unlock: `LOCKED → ACTIVE` chỉ qua `UnlockAccount`
  thủ công (FSM-1 invariant #5); `locked_until` (A5) là ngưỡng sớm nhất cho Unlock.

### C5. `POST /auth/logout` (proposed)

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

### C6. `POST /staff-accounts` (proposed)

- **Purpose:** Admin provisioning staff ACTIVE + temp password (D-04).
- **Auth:** Bearer access. **Authorization:** `SUPER_ADMIN` (mọi scope) /
  `ORGANIZATION_ADMIN` (trong Org) — RULE-02-05 CONFIRMED; StoreManager không có quyền này.
- **Request:** `{phone (req + UK CONFIRMED), email? (UK), password (temp, req CONFIRMED,
  policy TBD Q5), name (A3), role (req; 1 trong 9 CONFIRMED), organizationId?/storeId?
  (scoping TBD Q9)}`.
- **Response 201:** `{accountId, userId, status: "ACTIVE", mustChangePassword: true}`
  (flag CONFIRMED RULE-01-03).
- **Status:** `201` · `400` · `401` · `403 ACCESS_DENIED_SCOPE_MISMATCH`
  (mã CONFIRMED từ RULE-02-01) · `409` trùng phone/email.
- **Validation:** như register + role/scope check.
- **Idempotency:** như register (409, TBD Q8).
- **State transition:** `[*] → ACTIVE` + `AccountActivated`.

---

## D. Security & reliability (chỉ điểm có gốc docs)

1. Không bao giờ trả `password_hash` / `otp_code` (suy trực tiếp từ tính chất
   credential/OTP — docs không cho phép lộ).
2. Enforce có gốc rule: OTP TTL 300s, cooldown 60s, 5 resend/h, 5 sai → lock 15p,
   5 login sai → LOCKED ≥15p, revoke + blacklist khi logout/lock/deactivate,
   scope hierarchy, role enum.
3. Concurrency (derived requirements để enforce rule, không phải business mới):
   tăng `attempt_count` / `failed_login_attempts` atomic; resend invalidate-cũ +
   insert-mới atomic (RULE-01-04 "lập tức"); verify đổi state + đánh dấu `is_used`
   cùng transaction; phone unique bằng DB constraint (409).
4. Không suy diễn thêm: không lockout IP, không CAPTCHA, không device binding
   (docs không có → TBD Q11).
5. `ExpireOTP` là job nền (RULE-01-08) — BE cần scheduler/worker nhưng không có API.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning (`/api` vs `/api/v1`) | TBD (PO) | docs không có URL |
| Q2 | Response envelope + error shape | DECIDED — theo `docs/convention/backend/04-exception-handling`: `{success, errorCode, message, statusCode, timestamp, traceId}`; `ACCESS_DENIED_SCOPE_MISMATCH` → 403 CONFIRMED; `410`/`423`/`429` là PROPOSED extensions chưa có trong convention | convention + RULE-02-01 |
| Q3 | Có expose `POST /auth/refresh` không? Docs có refresh token (RULE-01-06/02-04) nhưng **không** có op/FSM/rule cho rotation/reuse → thiếu contract | TBD (PO) — v1 dùng refresh trong login/logout response nhưng không có endpoint refresh riêng cho đến khi bổ sung op + rule | RULE-01-06, RULE-02-04 |
| Q4 | Access/refresh TTL + rotation policy + reuse-handling; phụ: ERD `accounts.locked_until` ghi "mở khóa tự động" mâu thuẫn FSM-1 manual-only — cần sửa mô tả ERD thành "ngưỡng sớm nhất cho `UnlockAccount`" | TBD (PO) | docs không có TTL; ERD vs FSM-1 |
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
