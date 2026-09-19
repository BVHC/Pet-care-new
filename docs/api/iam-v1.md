# IAM API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Identity & Access Management (Module 02): quản trị user/role/permission,
> vòng đời khóa/vô hiệu hóa account, hồ sơ cá nhân. `CreateStaff` và `POST /customers`
> (provisioning, tạo Account mới) đã nằm ở [`auth-v1.md`](./auth-v1.md), không định nghĩa
> lại ở đây — IAM không được đụng `Account` entity/repository trực tiếp
> (`docs/convention/backend/01-package-structure.md`).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#2`), `docs/02-business-rules.md`
> (RULE-02-01→07), `docs/03-state-machines.md` (FSM-1 Account), `docs/04-glossary.md` (`02`),
> `docs/05-domain-model.md` (`4.2`), `docs/06-erd.md` (`users`/`roles`/`permissions`/
> `role_permissions`/`user_roles`).
> **Contract máy đọc:** [`./openapi/iam-v1.yaml`](./openapi/iam-v1.yaml) (OpenAPI 3.1).
> **Skill áp dụng:** `designing-apis` — API là business contract, không phải CRUD của DB.

**Legend:** `CONFIRMED` = có trong docs (kèm nguồn) · `ASSUMPTION (A#)` = suy luận
hợp lý từ docs, chưa xác nhận · `TBD (Q#)` = cần PO/Product quyết định (xem mục E) ·
`IMPLEMENTED` = đã có trong code (`BE/src/main/java/com/petcare/module/iam`) ·
`DEFERRED` = còn trong đặc tả target nhưng cố ý chưa code đợt này.
Error envelope DECIDED theo `docs/convention/backend/04-exception-handling`:
`{success, errorCode, message, statusCode, timestamp, traceId}`.

**Đóng băng phạm vi:**
- Staff provisioning (`CreateStaff`) và assisted customer registration (`POST /customers`,
  RULE-02-06) đều tạo `Account` mới nên thuộc [`auth-v1.md`](./auth-v1.md) (`/api/auth/staff-accounts`,
  `/api/auth/customers`) — IAM không định nghĩa endpoint tạo Account nào.
- `ManageCustomerProfile` của Customer tự phục vụ = `GET/PATCH /users/me`. Receptionist
  xem/sửa hồ sơ Customer đã tồn tại dùng lại đúng `GET/PATCH /users/{id}` (IMPLEMENTED,
  quyền chỉ áp dụng khi target có `role=CUSTOMER` — xem C2/C-lifecycle bên dưới).
- Gán quyền đa-role qua bảng `user_roles` chưa đủ contract (cột `scope_id` mơ hồ) —
  v1 dùng `users.role` đơn làm role chính (A3, IMPLEMENTED); `user_roles` là DEFERRED (Q5).
- Custom role theo Organization (`POST /roles`, ERD `roles.organization_id`) là DEFERRED —
  v1 chỉ implement đọc catalog (`GET /roles`, `GET /permissions`), seed sẵn 9 role chuẩn
  làm global role qua migration `V4__seed_iam_roles.sql`.

---

## A. Confirmed IAM API (9 endpoints implemented + 2 deferred)

| # | Endpoint | Business operation (CONFIRMED) | Trạng thái |
|---|---|---|---|
| 1 | `GET /users/me` | `ManageCustomerProfile` (self read) — `01#2`, RULE-02-06 | IMPLEMENTED |
| 2 | `PATCH /users/me` | `ManageCustomerProfile` (self update) — `01#2`, RULE-02-06 | IMPLEMENTED |
| 3 | `GET /users` | `ManageUser` (list, scope-filtered) — `01#2`, RULE-02-05 | IMPLEMENTED |
| 4 | `GET /users/{id}` | `ManageUser` (detail) / `ManageCustomerProfile` (Receptionist xem Customer) — `01#2`, RULE-02-05/06 | IMPLEMENTED |
| 5 | `PATCH /users/{id}` | `ManageUser` (rebind org/store) / `ManageCustomerProfile` (Receptionist sửa Customer) — `01#2`, RULE-02-05/06 | IMPLEMENTED |
| 6 | `POST /users/{id}/role-assignment` | `AssignPermission` + `ManageRole` (gán role/scope) — `01#2`, RULE-02-02/03/05 | IMPLEMENTED |
| 7 | `POST /users/{id}/lock` | `LockAccount` — `01#2`, RULE-02-04/05, FSM-1 | IMPLEMENTED |
| 8 | `POST /users/{id}/unlock` | `UnlockAccount` — `01#2`, RULE-02-04/05 | IMPLEMENTED |
| 9 | `POST /users/{id}/deactivate` | `DeactivateAccount` — `01#2`, RULE-02-05/07, FSM-1 | IMPLEMENTED |
| 10 | `POST /users/{id}/reactivate` | `ReactivateAccount` — `01#2`, RULE-02-05/07, FSM-1 | IMPLEMENTED |
| 11 | `GET /roles`, `GET /permissions` | `ManageRole`/`ManagePermission` (read catalog) — `01#2`, RULE-02-01/02 | IMPLEMENTED (9 role seed sẵn; `permissions` rỗng — chưa module nào định nghĩa code) |
| 12 | `POST /roles` | `ManageRole` (custom role trong Organization) — `01#2`, RULE-02-02, ERD `roles` | DEFERRED |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ManageCustomerProfile (self) | Customer / Staff | Sở hữu hồ sơ | RULE-02-06 (self; cấm tự đổi role) | GET/PATCH | `/users/me` | Bearer | Owner | none | GET idempotent; PATCH theo body | Role thay đổi chỉ qua endpoint gán quyền |
| ManageUser | PlatformAdmin / OrgAdmin | Target trong scope | RULE-02-05 (hierarchy) | GET/PATCH | `/users`, `/users/{id}` | Bearer | `SUPER_ADMIN` all (`organizationId` filter tuỳ chọn — IMPLEMENTED); `ORGANIZATION_ADMIN` trong Org mình (truyền `organizationId` khác → 403) | none | GET idempotent | List phân trang chuẩn Spring `Pageable` (`page`/`size`/`sort`) — không phải shape tự chế; StoreManager không list |
| ManageCustomerProfile (counter — Receptionist) | Receptionist | Target `role=CUSTOMER` | RULE-02-06 | GET/PATCH | `/users/{id}` | Bearer | `RECEPTIONIST` — CHỈ khi target là CUSTOMER (bất kỳ, không giới hạn Store vì Customer không gắn Store); target là staff → 403 | none | GET idempotent | `PATCH` chỉ nhận 4 field profile (`fullName/gender/dateOfBirth/avatarUrl`); gửi `organizationId`/`storeId` cho Customer → `BUSINESS_RULE_VIOLATION` (RULE-02-02) |
| AssignPermission | StoreManager (+Admin) | Target là staff Store mình; quyền ≤ thẩm quyền người gán | RULE-02-02/03/05 | POST | `/users/{id}/role-assignment` | Bearer | Trong scope + no-escalation | none | Non-idempotent (ghi assignment mới) | Vi phạm leo thang → `ACCESS_DENIED_SCOPE_MISMATCH` (RULE-02-01 CONFIRMED). Response `200` (action trên resource có sẵn, không phải tạo mới) |
| LockAccount | PlatformAdmin / OrgAdmin | Account `ACTIVE` | RULE-02-04 (revoke tức thì), RULE-02-05 | POST | `/users/{id}/lock` | Bearer | Admin trong scope (StoreManager KHÔNG có quyền — `01#2`) | `ACTIVE → LOCKED` + `AccountLocked` | Idempotent (đã LOCKED vẫn 200) | Thu hồi toàn bộ session/token + blacklist |
| UnlockAccount | PlatformAdmin / OrgAdmin | Account `LOCKED` | RULE-02-04/05 | POST | `/users/{id}/unlock` | Bearer | Admin trong scope | `LOCKED → ACTIVE` + `AccountUnlocked` | Idempotent | **Không** chờ `locked_until` — Admin unlock được bất kỳ lúc nào, bất kể lý do khóa (REQ-ACC-011 CONFIRMED; bản trước của contract này ghi nhầm là phải chờ `locked_until`, chỉ áp dụng cho `AutoUnlockAccount` tự động, không áp dụng cho unlock thủ công) |
| DeactivateAccount | PlatformAdmin / OrgAdmin | Account `ACTIVE`/`LOCKED` (nhân viên nghỉ việc) | RULE-02-05/07 (revoke + từ chối login) | POST | `/users/{id}/deactivate` | Bearer | Admin trong scope | `ACTIVE/LOCKED → DEACTIVATED` + `AccountDeactivated` | Idempotent | Vĩnh viễn cho đến khi reactivate |
| ReactivateAccount | PlatformAdmin / OrgAdmin | Account `DEACTIVATED` hoặc `LOCKED` + lý do bắt buộc | RULE-02-05/07 (reason + audit) | POST | `/users/{id}/reactivate` | Bearer | Admin trong scope | `DEACTIVATED/LOCKED → ACTIVE` + `AccountReactivated` | Idempotent | `{reason}` required CONFIRMED · nguồn `LOCKED` bổ sung 2026-09-16 (xem Decision Log `docs/02-business-rules.md` mục RULE-02-07) — dùng khi Admin muốn mở khóa nhưng vẫn bắt buộc ghi lý do vào Audit Log, khác `UnlockAccount` không yêu cầu lý do |
| ManageRole/Permission (read) | Bất kỳ actor đã đăng nhập | — | RULE-02-01/02 | GET | `/roles`, `/permissions` | Bearer | authenticated (không @PreAuthorize riêng) | none | Idempotent | 9 canonical roles seed sẵn (global, `organizationId=null`); `permissions` hiện rỗng |
| ManageRole (create custom) | OrgAdmin | Code chưa tồn tại trong Org | RULE-02-02 (scope hợp lệ) | POST | `/roles` | — | — | — | — | **DEFERRED** — chưa implement đợt này (xem Đóng băng phạm vi) |

**ASSUMPTIONS dùng chung:** A1 `PATCH /users/me` không cho đổi `role`/`organizationId`/`storeId`
(RULE-02-06 "không tự nâng quyền") · A3 role chính v1 = `users.role` đơn; `user_roles` đa-role
để DEFERRED (Q5) · A4 Customer không gắn Organization nào trong schema hiện tại nên
`ORGANIZATION_ADMIN` không quản trị được qua `GET/PATCH /users/{id}`; chỉ `SUPER_ADMIN`
(toàn quyền) hoặc `RECEPTIONIST` (chỉ target CUSTOMER) được truy cập.

---

## C. Detailed endpoint contract

### C1. `GET /users` (list) — IMPLEMENTED

- **Purpose:** Admin tra cứu user trong scope (`ManageUser`).
- **Auth:** Bearer. **Authorization:** `SUPER_ADMIN` (all, có thể lọc theo `organizationId`)
  / `ORGANIZATION_ADMIN` (chỉ Org mình — truyền `organizationId` khác của actor → `403
  ACCESS_DENIED_SCOPE_MISMATCH`). StoreManager không có quyền list.
- **Query (CONFIRMED, khớp code):** chuẩn Spring `Pageable` (`page`, `size`, `sort`)
  + `organizationId?` (UUID, chỉ có hiệu lực lọc thật với SUPER_ADMIN), `role?` (`RoleCode`),
  `storeId?` (UUID). Không có free-text search (`q`) — ngoài phạm vi v1 (YAGNI).
- **Response 200:** `PageResponse<UserResponse>` — `{content: [{userId, accountId,
  fullName, gender, dateOfBirth, avatarUrl, role, organizationId, storeId, status}],
  page, size, totalElements, ...}` (shape chuẩn `platform/model/PageResponse`).
  `gender`/`dateOfBirth`/`avatarUrl` CONFIRMED có trong response — trước đây bị bỏ sót
  khỏi contract (client set qua `PATCH` xong không đọc lại được, vi phạm RULE-02-06
  "Customer toàn quyền xem hồ sơ của chính mình"), đã sửa 2026-09-16.
- **Status:** `200` · `401` · `403`.

### C2. `GET/PATCH /users/{id}` — IMPLEMENTED

- **Purpose:** `ManageUser` (Admin quản trị staff) hoặc `ManageCustomerProfile`
  (Receptionist xem/sửa hồ sơ Customer tại quầy, RULE-02-06).
- **Auth:** Bearer. **Authorization** (phụ thuộc `role` của target — kiểm tra ở Service,
  không chỉ ở `@PreAuthorize`):
  - Target là staff (khác `CUSTOMER`): `SUPER_ADMIN` (mọi Org) hoặc `ORGANIZATION_ADMIN`
    (đúng Org của target). `RECEPTIONIST` bị chặn `403`.
  - Target là `CUSTOMER`: `SUPER_ADMIN` hoặc `RECEPTIONIST` (bất kỳ, Customer không gắn
    Store). `ORGANIZATION_ADMIN` bị chặn `403` (Customer không thuộc Organization nào).
- **`PATCH` Request** — 2 nhóm field loại trừ nhau theo role của target:
  - Target staff: `{organizationId?, storeId?, clearStoreId?}` (rebind, KHÔNG đổi role —
    dùng endpoint role-assignment). `storeId=null` nghĩa là "giữ nguyên" (partial update);
    để xoá hẳn storeId (vd rút FINANCE_STAFF khỏi Store, giữ Organization) phải gửi
    `clearStoreId: true` — gửi kèm cả `storeId` khác null lúc đó là mâu thuẫn, trả
    `400 BUSINESS_RULE_VIOLATION`. `organizationId` không có cờ tương tự vì mọi role
    staff (trừ SUPER_ADMIN/CUSTOMER, không đi qua endpoint này) đều bắt buộc org. Gửi
    field profile cho target staff → `400 BUSINESS_RULE_VIOLATION` (RULE-02-06).
  - Target `CUSTOMER`: `{fullName?, gender?, dateOfBirth?, avatarUrl?}`. Gửi
    `organizationId`/`storeId` cho Customer → `400 BUSINESS_RULE_VIOLATION` (RULE-02-02).
- **Response 200:** `UserResponse`.
- **Status:** `200` · `400` · `401` · `403 ACCESS_DENIED_SCOPE_MISMATCH` · `404`.

### C3. `POST /users/{id}/role-assignment` — IMPLEMENTED

- **Purpose:** Gán role/scope cho staff (`AssignPermission`/`ManageRole`).
- **Auth:** Bearer. **Authorization:** người gán phải có thẩm quyền bao trùm
  role+scope được gán (RULE-02-03, RULE-02-05); StoreManager chỉ trong Store mình.
- **Request:** `{role (1 trong 9 CONFIRMED), organizationId?, storeId?}`.
- **Response 200:** `{userId, role, organizationId, storeId}` (action trên resource có
  sẵn, không phải tạo mới → `200`, không phải `201`).
- **Status:** `200` · `400` · `401` · `403` leo thang/ngoài scope
  (`ACCESS_DENIED_SCOPE_MISMATCH` CONFIRMED) · `404` user không tồn tại.
- **Guards:** role phải hợp lệ trong scope gán (RULE-02-02, vd `CUSTOMER` không gắn
  Store); gán vượt thẩm quyền người gán → 403.

### C4. Account lifecycle (4 actions) — IMPLEMENTED

- `POST /users/{id}/lock` — Request `{}`. Guards: chỉ từ `ACTIVE`; StoreManager
  gọi → 403 (docs không gán quyền). Hiệu ứng CONFIRMED: revoke toàn bộ
  session/access/refresh + blacklist (RULE-02-04). Idempotent.
- `POST /users/{id}/unlock` — Guards: chỉ từ `LOCKED`. Cho phép bất kỳ lúc nào,
  không chờ `locked_until` (REQ-ACC-011 — xem mục B). Không có auto-unlock qua
  endpoint này (auto-unlock do hệ thống tự chạy khi login, xem auth-v1 A5).
- `POST /users/{id}/deactivate` — Request `{reason?}`. Guards: từ `ACTIVE` hoặc
  `LOCKED` (RULE-02-07 CONFIRMED); revoke + từ chối login vĩnh viễn.
- `POST /users/{id}/reactivate` — Request `{reason (required CONFIRMED)}`.
  Guards: từ `DEACTIVATED` **hoặc `LOCKED`** (bổ sung 2026-09-16 — xem Decision Log
  `docs/02-business-rules.md` mục RULE-02-07 và FSM-1 `docs/03-state-machines.md` §1);
  ghi audit bắt buộc (RULE-02-07). Luôn phát `AccountReactivated` bất kể trạng thái
  nguồn; khác `UnlockAccount` (chỉ từ `LOCKED`, không yêu cầu lý do) — dùng
  `ReactivateAccount` khi cần bắt buộc ghi lý do vào Audit Log ngay cả với khóa tạm.
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409` sai trạng thái
  (`INVALID_STATE_TRANSITION` theo convention).

### C5. `GET /roles`, `GET /permissions` — IMPLEMENTED · `POST /roles` — DEFERRED

- **Purpose:** Tra cứu catalog role/permission (`ManageRole`/`ManagePermission`).
- **Auth:** Bearer. **Authorization:** authenticated (không `@PreAuthorize` riêng —
  mọi actor đăng nhập đọc được catalog).
- **`GET /roles` Response 200:** `{roleId, code, name, scope, organizationId}[]` — 9
  role chuẩn seed sẵn qua `V4__seed_iam_roles.sql`, tất cả `organizationId=null` (global).
- **`GET /permissions` Response 200:** `{code, module}[]` — hiện trả mảng rỗng, chưa
  module nghiệp vụ nào định nghĩa permission code thật.
- **`POST /roles`** (custom role theo Organization): **DEFERRED**, chưa implement.
  Giữ nguyên đặc tả target trong docs cho tham khảo sau này: Request
  `{code (req), name (req), scope (1 trong 5 CONFIRMED), organizationId? (null = global,
  chỉ SUPER_ADMIN), permissionCodes?: string[]}`, Response 201
  `{roleId, code, name, scope, organizationId}`, `409` trùng `(organization_id, code)`.

---

## D. Security & reliability (chỉ điểm có gốc docs)

1. Không trả `password_hash`; role/permission check server-side (RULE-02-01/03).
2. Enforce có gốc rule: scope hierarchy, no-escalation, 9 roles, revoke tức thì
   khi lock/deactivate, reason + audit khi reactivate.
3. Concurrency (derived): gán role + revoke session cùng transaction; email UK
   bằng DB constraint (409, xem auth-v1 cho các endpoint tạo Account).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung với auth-v1 Q1) | docs không có URL |
| Q2 | Envelope | DECIDED theo convention `04-exception-handling` | convention |
| Q3 | `POST /customers` có ACTIVE-tắt tại quầy hay giữ PENDING+OTP? | **DECIDED: ACTIVE ngay, không OTP** — xem `auth-v1.md` | PO quyết định 2026-09-14 |
| Q4 | `user_roles.scope_id` trỏ tới bảng nào (store hay org)? Đa-role resolve ra sao? | DEFERRED (PO/BE) — ngoài phạm vi v1 | ERD mơ hồ |
| Q5 | Có expose API gán đa-role (`user_roles`) trong v1 không, hay `users.role` đơn là đủ? | **DECIDED: `users.role` đơn là đủ cho v1** — `user_roles`/custom role DEFERRED | PO quyết định 2026-09-14 |
| Q7 | Pagination/filter convention cho `GET /users`, `/roles` | **DECIDED: chuẩn Spring `Pageable`** (không phải `page/pageSize` tự chế) | Khớp code hiện tại |
| Q8 | Custom role có được trùng code canonical không? | DEFERRED (PO) — chỉ liên quan khi `POST /roles` được implement | ERD cho custom, thiếu rule |
| Q9 | Customer chưa gắn Org: ai quản trị? | **DECIDED: `SUPER_ADMIN` hoặc `RECEPTIONIST`** (không phải `ORGANIZATION_ADMIN`, vì Customer không gắn Org trong schema) | PO quyết định 2026-09-14 |
| Q10 | StoreManager có được list staff Store mình (`GET /users`) không? | **DECIDED: Không** — khớp `@PreAuthorize` hiện tại | Khớp code hiện tại |

---

## F. OpenAPI 3.1 YAML

Single source of truth cho contract máy đọc: [`./openapi/iam-v1.yaml`](./openapi/iam-v1.yaml).
