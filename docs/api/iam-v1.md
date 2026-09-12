# IAM API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Identity & Access Management (Module 02): quản trị user/role/permission,
> vòng đời khóa/vô hiệu hóa account, hồ sơ cá nhân. `CreateStaff` (provisioning) đã nằm ở
> [`auth-v1.md`](./auth-v1.md), không định nghĩa lại ở đây.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#2`), `docs/02-business-rules.md`
> (RULE-02-01→07), `docs/03-state-machines.md` (FSM-1 Account), `docs/04-glossary.md` (`02`),
> `docs/05-domain-model.md` (`4.2`), `docs/06-erd.md` (`users`/`roles`/`permissions`/
> `role_permissions`/`user_roles`).
> **Contract máy đọc:** [`./openapi/iam-v1.yaml`](./openapi/iam-v1.yaml) (OpenAPI 3.1).
> **Skill áp dụng:** `designing-apis` — API là business contract, không phải CRUD của DB.

**Legend:** `CONFIRMED` = có trong docs (kèm nguồn) · `ASSUMPTION (A#)` = suy luận
hợp lý từ docs, chưa xác nhận · `TBD (Q#)` = cần PO/Product quyết định (xem mục E).
Mọi method/path/field-name/envelope trong tài liệu này đều là **PROPOSED design**
(docs không chứa URL hay JSON shape nào). Error envelope DECIDED theo
`docs/convention/backend/04-exception-handling`:
`{success, errorCode, message, statusCode, timestamp, traceId}`.

**Đóng băng phạm vi:**
- Staff provisioning (`CreateStaff` + temp password + `must_change_password`) thuộc
  [`auth-v1.md`](./auth-v1.md], `POST /staff-accounts` là endpoint tạo staff duy nhất —
  IAM không định nghĩa endpoint tạo staff thứ hai.
- `ManageCustomerProfile` của Customer tự phục vụ = `GET/PATCH /users/me`. Receptionist
  tạo Customer tại quầy là assisted-registration, đi theo semantics `RegisterAccount`
  (PENDING + OTP), không phát minh luồng ACTIVE-tắt (xem C1).
- Gán quyền đa-role qua bảng `user_roles` chưa đủ contract (cột `scope_id` mơ hồ) —
  v1 dùng `users.role` đơn làm role chính (A3); `user_roles` là TBD Q5.
- Custom role theo Organization (ERD `roles.organization_id`) được hỗ trợ tạo mới;
  ma trận role × scope bắt buộc xem [`auth-v1.md`](./auth-v1.md) Q9.

---

## A. Confirmed IAM API (10 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /users/me` | `ManageCustomerProfile` (self read) — `01#2`, RULE-02-06 |
| 2 | `PATCH /users/me` | `ManageCustomerProfile` (self update) — `01#2`, RULE-02-06 |
| 3 | `POST /customers` | `ManageCustomerProfile` (Receptionist tạo Customer tại quầy) — `01#2`, RULE-02-06 |
| 4 | `GET /users` | `ManageUser` (list, scope-filtered) — `01#2`, RULE-02-05 |
| 5 | `GET /users/{id}` | `ManageUser` (detail) — `01#2`, RULE-02-05 |
| 6 | `PATCH /users/{id}` | `ManageUser` (update binding/org/store) — `01#2`, RULE-02-05 |
| 7 | `POST /users/{id}/role-assignment` | `AssignPermission` + `ManageRole` (gán role/scope) — `01#2`, RULE-02-02/03/05 |
| 8 | `POST /users/{id}/lock` | `LockAccount` — `01#2`, RULE-02-04/05, FSM-1 |
| 9 | `POST /users/{id}/unlock` | `UnlockAccount` — `01#2`, RULE-02-04/05, FSM-1 inv #5 |
| 10 | `POST /users/{id}/deactivate` | `DeactivateAccount` — `01#2`, RULE-02-05/07, FSM-1 |
| 11 | `POST /users/{id}/reactivate` | `ReactivateAccount` — `01#2`, RULE-02-05/07, FSM-1 |
| 12 | `GET /roles`, `GET /permissions` | `ManageRole`/`ManagePermission` (read catalog) — `01#2`, RULE-02-01/02 |
| 13 | `POST /roles` | `ManageRole` (custom role trong Organization) — `01#2`, RULE-02-02, ERD `roles` |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ManageCustomerProfile (self) | Customer / Staff | Sở hữu hồ sơ | RULE-02-06 (self; cấm tự đổi role) | GET/PATCH | `/users/me` | Bearer | Owner | none | GET idempotent; PATCH theo body | Role thay đổi chỉ qua endpoint gán quyền |
| ManageCustomerProfile (counter) | Receptionist | Tại quầy Store | RULE-02-06 | POST | `/customers` | Bearer | `RECEPTIONIST` (Store mình) | `[*] → PENDING_VERIFICATION` (như RegisterAccount) | 409 nhờ phone UK | Assisted registration; ACTIVE-tắt không có trong docs → TBD Q6 |
| ManageUser | PlatformAdmin / OrgAdmin | Target trong scope | RULE-02-05 (hierarchy) | GET/PATCH | `/users`, `/users/{id}` | Bearer | `SUPER_ADMIN` all; `ORGANIZATION_ADMIN` trong Org | none | GET idempotent | List phân trang PROPOSED (Q7); StoreManager không list cross-store |
| AssignPermission | StoreManager (+Admin) | Target là staff Store mình; quyền ≤ thẩm quyền người gán | RULE-02-02/03/05 | POST | `/users/{id}/role-assignment` | Bearer | Trong scope + no-escalation | none | Non-idempotent (ghi assignment mới) | Vi phạm leo thang → `ACCESS_DENIED_SCOPE_MISMATCH` (RULE-02-01 CONFIRMED) |
| LockAccount | PlatformAdmin / OrgAdmin | Account `ACTIVE` | RULE-02-04 (revoke tức thì), RULE-02-05 | POST | `/users/{id}/lock` | Bearer | Admin trong scope (StoreManager KHÔNG có quyền — `01#2`) | `ACTIVE → LOCKED` + `AccountLocked` | Idempotent (đã LOCKED vẫn 200) | Thu hồi toàn bộ session/token + blacklist |
| UnlockAccount | PlatformAdmin / OrgAdmin | Account `LOCKED` + đã qua `locked_until` (FSM-1 inv #5) | RULE-02-04/05 | POST | `/users/{id}/unlock` | Bearer | Admin trong scope | `LOCKED → ACTIVE` + `AccountUnlocked` | Idempotent | Unlock sớm hơn `locked_until` → 409 PROPOSED |
| DeactivateAccount | PlatformAdmin / OrgAdmin | Account `ACTIVE`/`LOCKED` (nhân viên nghỉ việc) | RULE-02-05/07 (revoke + từ chối login) | POST | `/users/{id}/deactivate` | Bearer | Admin trong scope | `ACTIVE/LOCKED → DEACTIVATED` + `AccountDeactivated` | Idempotent | Vĩnh viễn cho đến khi reactivate |
| ReactivateAccount | PlatformAdmin / OrgAdmin | Account `DEACTIVATED` + lý do bắt buộc | RULE-02-05/07 (reason + audit) | POST | `/users/{id}/reactivate` | Bearer | Admin trong scope | `DEACTIVATED → ACTIVE` + `AccountReactivated` | Idempotent | `{reason}` required CONFIRMED |
| ManageRole/Permission (read) | Admin | — | RULE-02-01/02 | GET | `/roles`, `/permissions` | Bearer | Theo scope | none | Idempotent | 9 canonical roles CONFIRMED (RULE-02-02) |
| ManageRole (create custom) | OrgAdmin | Code chưa tồn tại trong Org | RULE-02-02 (scope hợp lệ) | POST | `/roles` | Bearer | `ORGANIZATION_ADMIN` (Org mình); `SUPER_ADMIN` (global, `organizationId=null`) | none | 409 nhờ UK `(organization_id, code)` | Không được đè canonical codes? TBD Q8 |

**ASSUMPTIONS dùng chung:** A1 `PATCH /users/me` không cho đổi `role`/`organizationId`/`storeId`
(RULE-02-06 "không tự nâng quyền") · A2 `POST /customers` tái dùng semantics register
(PENDING + OTP) vì docs không đặc tả luồng quầy riêng · A3 role chính v1 = `users.role`
đơn; `user_roles` đa-role để TBD Q5 · A4 lock/unlock/deactivate/rejectivate của
tài khoản CUSTOMER thuộc Org nào do OrgAdmin Org đó (RULE-02-05 "khách hàng thuộc
Organization") — customer chưa gắn Org nào thì chỉ `SUPER_ADMIN` (TBD Q9).

---

## C. Detailed endpoint contract

### C1. `POST /customers` (proposed, assisted registration)

- **Purpose:** Receptionist tạo Customer tại quầy (`ManageCustomerProfile`).
- **Auth:** Bearer. **Authorization:** `RECEPTIONIST` trong Store đang phục vụ.
- **Request:** `{phone (req + UK CONFIRMED), email?, password (req), name (req)}` —
  giống `RegisterRequest` (auth-v1).
- **Response 201:** `{accountId, userId, status: "PENDING_VERIFICATION"}` + gửi OTP
  như register (A2). ACTIVE-tắt tại quầy KHÔNG có trong docs → TBD Q6.
- **Status:** `201` · `400` · `401` · `403` ngoài Store mình
  (`ACCESS_DENIED_SCOPE_MISMATCH` CONFIRMED) · `409` trùng phone/email.

### C2. `GET /users` (proposed, list)

- **Purpose:** Admin tra cứu user trong scope (`ManageUser`).
- **Auth:** Bearer. **Authorization:** `SUPER_ADMIN` (all) / `ORGANIZATION_ADMIN`
  (Org mình). StoreManager không có quyền list (docs không gán — TBD Q10 nếu cần).
- **Query PROPOSED (Q7):** `page`, `pageSize`, `role?`, `storeId?`, `status?`, `q?`
  (docs không có pagination/filter convention).
- **Response 200:** `{items: [{userId, accountId, name, role, organizationId, storeId,
  status}], page, pageSize, total}` (shape PROPOSED).
- **Status:** `200` · `401` · `403`.

### C3. `POST /users/{id}/role-assignment` (proposed)

- **Purpose:** Gán role/scope cho staff (`AssignPermission`/`ManageRole`).
- **Auth:** Bearer. **Authorization:** người gán phải có thẩm quyền bao trùm
  role+scope được gán (RULE-02-03, RULE-02-05); StoreManager chỉ trong Store mình.
- **Request:** `{role (1 trong 9 CONFIRMED), organizationId?, storeId?}`.
- **Response 200:** `{userId, role, organizationId, storeId}`.
- **Status:** `200` · `400` · `401` · `403` leo thang/ngoài scope
  (`ACCESS_DENIED_SCOPE_MISMATCH` CONFIRMED) · `404` user không tồn tại.
- **Guards:** role phải hợp lệ trong scope gán (RULE-02-02, vd `CUSTOMER` không gắn
  Store); gán vượt thẩm quyền người gán → 403.

### C4. Account lifecycle (proposed, 4 actions)

- `POST /users/{id}/lock` — Request `{}`. Guards: chỉ từ `ACTIVE`; StoreManager
  gọi → 403 (docs không gán quyền). Hiệu ứng CONFIRMED: revoke toàn bộ
  session/access/refresh + blacklist (RULE-02-04). Idempotent.
- `POST /users/{id}/unlock` — Guards: chỉ từ `LOCKED` và đã qua `locked_until`
  (FSM-1 inv #5); unlock sớm → `409` PROPOSED. Không có auto-unlock
  (xem auth-v1 A5).
- `POST /users/{id}/deactivate` — Request `{reason?}`. Guards: từ `ACTIVE` hoặc
  `LOCKED` (RULE-02-07 CONFIRMED); revoke + từ chối login vĩnh viễn.
- `POST /users/{id}/reactivate` — Request `{reason (required CONFIRMED)}`.
  Guards: chỉ từ `DEACTIVATED`; ghi audit bắt buộc (RULE-02-07).
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409` sai trạng thái
  (`INVALID_STATE_TRANSITION` theo convention).

### C5. `GET /roles`, `GET /permissions`, `POST /roles` (proposed)

- **Purpose:** Tra cứu + tạo custom role (`ManageRole`/`ManagePermission`).
- **Auth:** Bearer. **Authorization:** read theo scope; create: `ORGANIZATION_ADMIN`
  (Org mình) / `SUPER_ADMIN` (global).
- **`POST /roles` Request:** `{code (req), name (req), scope (1 trong 5 CONFIRMED),
  organizationId? (null = global, chỉ SUPER_ADMIN), permissionCodes?: string[]}`.
- **Response 201:** `{roleId, code, name, scope, organizationId}`.
- **Status:** `200`/`201` · `400` · `401` · `403` · `409` trùng `(organization_id, code)`
  (ERD UK CONFIRMED). Ghi đè canonical codes → TBD Q8.

---

## D. Security & reliability (chỉ điểm có gốc docs)

1. Không trả `password_hash`; role/permission check server-side (RULE-02-01/03).
2. Enforce có gốc rule: scope hierarchy, no-escalation, 9 roles, revoke tức thì
   khi lock/deactivate, reason + audit khi reactivate.
3. Concurrency (derived): gán role + revoke session cùng transaction; phone UK
   bằng DB constraint (409).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung với auth-v1 Q1) | docs không có URL |
| Q2 | Envelope | DECIDED theo convention `04-exception-handling` | convention |
| Q3 | `POST /customers` có ACTIVE-tắt tại quầy hay giữ PENDING+OTP (A2)? | TBD (PO) | docs không đặc tả luồng quầy |
| Q4 | `user_roles.scope_id` trỏ tới bảng nào (store hay org)? Đa-role resolve ra sao? | TBD (PO/BE) | ERD mơ hồ |
| Q5 | Có expose API gán đa-role (`user_roles`) trong v1 không, hay `users.role` đơn là đủ? | TBD (PO) | ERD có cả 2 cơ chế |
| Q6 | Xem C1 (ACTIVE-tắt tại quầy) | TBD (PO) | — |
| Q7 | Pagination/filter convention cho `GET /users`, `/roles` | TBD (BE) — PROPOSED `page/pageSize` | docs không có |
| Q8 | Custom role có được trùng code canonical không? | TBD (PO) | ERD cho custom, thiếu rule |
| Q9 | Customer chưa gắn Org: ai quản trị? | TBD (PO) | RULE-02-05 |
| Q10 | StoreManager có được list staff Store mình (`GET /users`) không? | TBD (PO) | `01#2` không gán |

---

## F. OpenAPI 3.1 YAML

Single source of truth cho contract máy đọc: [`./openapi/iam-v1.yaml`](./openapi/iam-v1.yaml).
