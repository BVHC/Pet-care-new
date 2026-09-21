# Customer & Pet API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Customer & Pet Management (Module 04): hồ sơ Pet, chuyển giao
> sở hữu, ủy quyền Caregiver (FSM-3). `ManageCustomerProfile` đã nằm ở
> [`iam-v1.md`](./iam-v1.md) (self + quầy), không định nghĩa lại ở đây.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#4`), `docs/02-business-rules.md`
> (RULE-04-01→11), `docs/03-state-machines.md` (FSM-3 Caregiver), `docs/04-glossary.md`
> (`04`), `docs/05-domain-model.md` (`4.4`), `docs/06-erd.md` (`pets`/
> `pet_caregiver_delegations`).
> **Contract máy đọc:** [`./openapi/customer-pet-v1.yaml`](./openapi/customer-pet-v1.yaml).
> **Skill áp dụng:** `designing-apis`.

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `PerformDelegatedAction` là **guard cắt ngang** (caregiver chỉ được thao tác trong
  phạm vi ủy quyền, sai → `UNAUTHORIZED_DELEGATED_ACTION` CONFIRMED RULE-04-09),
  không phải endpoint — các module khác enforce.
- `ProcessInvitationExpiry` / `ProcessDelegationExpiry` là system-internal (job nền),
  không expose API (như `ExpireOTP`).
- Trạng thái Pet `DECEASED`/`TRANSFERRED` (`PetStatus` enum, cột `pets.status`) —
  **CONFIRMED implemented** qua `PATCH /pets/{id}` (field `status`, RULE-04-11).
  Đóng Q4/Q8.
- `ManagePetOwnership` (`POST /pets/{id}/transfer`) chỉ đổi `owner_id` nội bộ —
  `PetStatus` giữ nguyên `ACTIVE`, KHÔNG chuyển sang `TRANSFERRED` (RULE-04-10, phân
  định rạch ròi với PetStatus.TRANSFERRED ở RULE-04-11 — 2 khái niệm độc lập).

---

## A. Confirmed Customer & Pet API (9 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /pets` | `AddPet` — `01#4`, RULE-04-01/03 |
| 2 | `GET /pets` | (list pets thuộc quyền) |
| 3 | `GET /pets/{id}` | `ViewPet` — `01#4`, RULE-04-09 |
| 4 | `PATCH /pets/{id}` | `UpdatePet` — `01#4`, RULE-04-03/04 |
| 5 | `POST /pets/{id}/transfer` | `ManagePetOwnership` (chuyển giao sở hữu) — `01#4`, RULE-04-10 |
| 6 | `GET /pets/search` | `SearchCustomerPet` (receptionist tra cứu) — `01#4`, RULE-04-02 |
| 7 | `POST /pets/{id}/caregiver-invitations` | `InviteCaregiver` (→ INVITED, TTL 7d) — `01#4`, RULE-04-04/05, FSM-3 |
| 8 | `POST /caregiver-invitations/{token}/accept` | `AcceptCaregiverInvitation` (→ ACTIVE) — `01#4`, RULE-04-05/06, FSM-3 |
| 9 | `POST /caregiver-invitations/{token}/reject` | `RejectCaregiverInvitation` (→ REJECTED) — `01#4`, RULE-04-06, FSM-3 |
| 10 | `POST /pets/{id}/caregiver-revoke` | `RevokeCaregiver` (→ REVOKED, tức thì) — `01#4`, RULE-04-04/08, FSM-3 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| AddPet | Customer / Receptionist | Gắn với Customer sở hữu hợp lệ | RULE-04-01/03 | POST | `/pets` | Bearer | Owner (hoặc receptionist tại quầy) | `[*] → ACTIVE` (pet mới; A1 — docs không nêu state khởi tạo) | 409 nếu trùng microchip (A2) | Species/breed/dob/gender bắt buộc theo ERD (gender default UNKNOWN) |
| ViewPet | Customer / Caregiver / Receptionist | Quyền xem | RULE-04-09 (caregiver trong phạm vi) | GET | `/pets`, `/pets/{id}` | Bearer | Owner / active-caregiver / receptionist | none | Idempotent | Caregiver ngoài phạm vi → `UNAUTHORIZED_DELEGATED_ACTION` |
| UpdatePet | Customer (/Receptionist hỗ trợ) | Pet tồn tại và đang `ACTIVE` | RULE-04-03 (core identity chỉ Primary Owner), RULE-04-04 (caregiver cấm sửa core), RULE-04-11 (`status` → `DECEASED`/`TRANSFERRED`, terminal) | PATCH | `/pets/{id}` | Bearer | Field-level theo actor (server enforce) | `ACTIVE → DECEASED` / `ACTIVE → TRANSFERRED` (terminal, chặn mọi update sau đó — `400 RULE-04-11`) | Idempotent khi không đổi status | Core = species/breed/dob/gender CONFIRMED; `status` optional field CONFIRMED |
| ManagePetOwnership | Primary Owner | Pet + chủ mới hợp lệ | RULE-04-10 | POST | `/pets/{id}/transfer` | Bearer | Chỉ Primary Owner | Đổi `owner_id`, `PetStatus` giữ ACTIVE; toàn bộ delegation `INVITED`/`ACTIVE` của chủ cũ tự động `REVOKED` (CONFIRMED, đóng Q7) | Không idempotent — transfer cho chính chủ hiện tại bị từ chối `400 RULE-04-10` | Chủ mới xác định bằng `newOwnerId` UUID trực tiếp, không cần xác nhận (CONFIRMED, đóng Q6) |
| SearchCustomerPet | Receptionist | Tại quầy | RULE-04-02 (theo SĐT/CCCD/mã Pet/mã Customer) | GET | `/pets/search` | Bearer | `RECEPTIONIST` | none | Idempotent | Params PROPOSED (Q9) |
| InviteCaregiver | Primary Owner | Là chủ chính | RULE-04-01/04/05 | POST | `/pets/{id}/caregiver-invitations` | Bearer | Chỉ Primary Owner (`CanInviteCaregiver` invariant) | `[*] → INVITED` + `CaregiverInvited` (`expires_at = +7d` CONFIRMED) | 409 nếu đã có ACTIVE delegation cho caregiver đó (derived) | `caregiver_user_id` NULL được (mời bằng SĐT, ERD CONFIRMED) |
| Accept/RejectCaregiverInvitation | Caregiver | Lời mời còn `INVITED` + hạn | RULE-04-05/06 | POST | `/caregiver-invitations/{token}/accept`, `…/reject` | Bearer | Đúng người được mời (khớp SĐT, A3) | `INVITED → ACTIVE` / `→ REJECTED` | Idempotent (đã ACTIVE vẫn 200) | Kích hoạt trực tiếp ACTIVE, không duyệt trung gian (FSM-3 note) |
| RevokeCaregiver | Primary Owner | Delegation `ACTIVE` | RULE-04-04/08 (tức thì) | POST | `/pets/{id}/caregiver-revoke` | Bearer | Chỉ Primary Owner | `ACTIVE → REVOKED` + `CaregiverRevoked` | Idempotent | Chấm dứt mọi quyền ngay lập tức |

**ASSUMPTIONS dùng chung:** A1 pet mới ở trạng thái hoạt động (`is_active=true`; docs
không nêu state khởi tạo) · A2 microchip unique để chống tạo trùng (ERD không UK —
derived, TBD Q10) · A3 accept/reject yêu cầu đăng nhập đúng SĐT được mời (docs không
nêu cơ chế xác thực người nhận) · A4 delegation có thời hạn (`DelegationValidityPeriod`
CONFIRMED tồn tại nhưng độ dài mặc định TBD Q5 — PROPOSED: mời mang `validUntil?`).
**ASSUMPTIONS bổ sung:** A5 — định danh lời mời bằng email, không phải phone
(RULE-01-10 đã đổi danh tính chính; lệch ERD §3.2 có chủ ý, xem spec D-01).

---

## C. Detailed endpoint contract

> **Phạm vi v1 (Pets CRUD — implemented):** toàn bộ C1, bao gồm cả
> `POST /pets/{id}/transfer` (implemented 2026-09-21, RULE-04-10) và field `status`
> của `PATCH /pets/{id}` (RULE-04-11). Toàn bộ C2 (search) vẫn **OUT v1**
> (theo plan `docs/superpowers/plans/2026-09-14-pets-crud.md` Global Constraints;
> receptionist tạo hộ cũng OUT).

### C1. Pets (proposed)

- **`POST /pets`** — Request `{name (req), species (req: `DOG`/`CAT`/`BIRD`/`OTHER`
  CONFIRMED), breed?, gender (`MALE`/`FEMALE`/`UNKNOWN` CONFIRMED), dateOfBirth?,
  weightKg?, microchipNumber?, ownerId? (receptionist tạo hộ — trong Store)}$.
  Customer tự tạo thì owner = mình. Response `201 {petId, ownerId, status: "ACTIVE"}`
  (`status: PetStatus` enum — v1 khóa `ACTIVE` per RULE-04-11; field `isActive:boolean`
  cũ đã bỏ vì lạc hậu so với ERD/glossary).
  Status: `201` · `400` · `401` · `403` · `409` trùng microchip (A2).
- **`GET /pets`** — List pets của mình (customer) / được ủy quyền (caregiver thấy pets
  ACTIVE-delegated) / toàn Store (receptionist? TBD Q11 — PROPOSED: không, dùng
  `/pets/search`). Query PROPOSED: `page`, `pageSize`. Response `200 {items, page, pageSize, total}`.
- **`GET /pets/{id}`** — Guards: owner / active-caregiver trong phạm vi / receptionist.
  Ngoài phạm vi → `403 UNAUTHORIZED_DELEGATED_ACTION` (CONFIRMED code RULE-04-09).
- **`PATCH /pets/{id}`** — Field-level CONFIRMED: core identity (species/breed/dob/gender)
  + transfer chỉ Primary Owner; caregiver gọi với core fields → `403`
  (`UNAUTHORIZED_DELEGATED_ACTION`); receptionist sửa thông tin phi-core tại quầy.
  Field `status?` (CONFIRMED, RULE-04-11) — Primary Owner đổi Pet sang `DECEASED` hoặc
  `TRANSFERRED`; cả 2 là terminal, mọi `PATCH` sau đó (kể cả field khác) bị từ chối
  `400 RULE-04-11` cho tới khi nào Pet còn `ACTIVE`.
  Status: `200` · `400` · `401` · `403` · `404`.
- **`POST /pets/{id}/transfer`** — **Implemented** (RULE-04-10). Request
  `{newOwnerId}` (UUID, CONFIRMED — đóng Q6, không cần chủ mới xác nhận). Chỉ Primary
  Owner; transfer cho chính chủ hiện tại → `400 RULE-04-10`. Response `200 PetResponse`
  (`ownerId` đã đổi, `status` giữ nguyên `ACTIVE`).
  Toàn bộ `PetCaregiverDelegation` đang `INVITED`/`ACTIVE` của chủ cũ tự động chuyển
  `REVOKED` ngay lập tức, cùng transaction (CONFIRMED — đóng Q7, ghi đè quyết định
  PROPOSED "giữ nguyên" trước đây; RULE-04-10 + RHD-01 là nguồn chân lý).

### C2. Counter search — OUT v1 (proposed)

- **`GET /pets/search`** — Query PROPOSED (Q9): `phone?`, `citizenId?`, `petCode?`,
  `customerCode?` (tiêu chí CONFIRMED RULE-04-02, tên param PROPOSED).
  Chỉ `RECEPTIONIST`. Response `200 {customers: [...], pets: [...]}` (shape PROPOSED).

### C3. Caregiver delegation (implemented)

- **`POST /pets/{id}/caregiver-invitations`** — Request `{caregiverEmail (req),
  caregiverPhone?, validUntil?}`. Chỉ Primary Owner (không phải owner → 403; caregiver
  mời thêm người → 403 RULE-04-04 CONFIRMED). Response
  `201 {invitationId, status: "INVITED", expiresAt (+7d CONFIRMED)}`. Người được mời
  đã có tài khoản → token đi qua email, response không mang token; chưa có tài khoản →
  response mang `invitationToken` đúng 1 lần cho Primary Owner tự chuyển đi (D-02).
  Trùng lời mời/ủy quyền còn sống → `409 CAREGIVER_INVITATION_CONFLICT`.
- **`POST /caregiver-invitations/{token}/accept`** — Guards: `INVITED` + còn hạn
  (quá hạn → `400` RULE-04-05, tự kiểm tại request không đợi job — D-08); đúng người
  được mời (đã gán `caregiverUserId` thì bắt buộc khớp — A-03; chưa gán thì người cầm
  token nhận ủy quyền — A-02). Response `200 {delegationId, status: "ACTIVE"}`.
  Đã ở trạng thái đích **và đúng chủ thể** → 200, không sinh event lần hai (D-10).
- **`POST /caregiver-invitations/{token}/reject`** — Response `200 {status: "REJECTED"}`.
  Ngữ nghĩa idempotent như accept (D-10).
- **`POST /pets/{id}/caregiver-revoke`** — Request `{caregiverEmail (req)}`.
  Chỉ Primary Owner. Phủ cả `INVITED` (hủy lời mời đang treo — D-04) và `ACTIVE`.
  Hiệu lực tức thì CONFIRMED. Response `200 {status: "REVOKED"}`.
  Revoke lần hai → 200, không sinh event lần hai (D-10).
- **Status chung:** `200`/`201` · `400` · `401` · `403` · `404` · `409` sai trạng thái
  hoặc trùng lời mời (`CAREGIVER_INVITATION_CONFLICT`).

---

## D. Security & reliability

1. Field-level authorization Pet do server enforce (RULE-04-03/04), không dựa vào FE ẩn nút.
2. `CanInviteCaregiver`/`CanTransferOwnership` ⇔ Primary Owner (invariant CONFIRMED).
3. Invitation token như credential: không log, TTL 7d, hết hạn auto `EXPIRED` (job nền).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | State khởi tạo Pet (A1) | TBD (PO) — ERD chỉ có `is_active` | ERD vs glossary `PetStatus` |
| Q4 | Xử lý pet chết (`DECEASED`): op nào, cột nào? | ĐÓNG (RULE-04-11: qua `PATCH /pets/{id}` field `status`, cột `pets.status`) | glossary có enum, nay đã có op + cột |
| Q5 | `DelegationValidityPeriod` mặc định bao lâu? `validUntil?` có cho truyền không? | ĐÓNG (D-03: validUntil tùy chọn, NULL = vô thời hạn) | docs nêu tên, thiếu số |
| Q6 | `transfer` xác định chủ mới bằng gì (userId/phone)? Chủ mới có phải xác nhận không? | ĐÓNG (RULE-04-10: `newOwnerId` UUID trực tiếp, không cần xác nhận) | implemented 2026-09-21 |
| Q7 | Delegations cũ khi đổi chủ: giữ hay hủy? | ĐÓNG (RULE-04-10 + RHD-01: tự động `REVOKED` toàn bộ `INVITED`/`ACTIVE` của chủ cũ) | implemented 2026-09-21, thay thế PROPOSED "giữ" trước đây |
| Q8 | Xem Q4 (DECEASED) | ĐÓNG — xem Q4 | — |
| Q9 | Tên param search (`phone/citizenId/petCode/customerCode`) | TBD (BE) — PROPOSED | RULE-04-02 cho tiêu chí, thiếu tên |
| Q10 | Microchip unique toàn hệ thống (A2)? | TBD (PO) | ERD không UK |
| Q11 | Receptionist có `GET /pets` list-all không hay chỉ search? | TBD (PO) — PROPOSED chỉ search | RULE-04-02 (tra cứu theo tiêu chí) |
| Q12 | Trao `invitation_token` cho caregiver qua kênh nào (response 1 lần / SMS)? | ĐÓNG (D-02: email nếu đã có account, trả token 1 lần nếu chưa) | docs không nêu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/customer-pet-v1.yaml`](./openapi/customer-pet-v1.yaml).
