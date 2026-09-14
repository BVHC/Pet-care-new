# Customer & Pet API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Customer & Pet Management (Module 04): hồ sơ Pet, chuyển giao
> sở hữu, ủy quyền Caregiver (FSM-3). `ManageCustomerProfile` đã nằm ở
> [`iam-v1.md`](./iam-v1.md) (self + quầy), không định nghĩa lại ở đây.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#4`), `docs/02-business-rules.md`
> (RULE-04-01→09), `docs/03-state-machines.md` (FSM-3 Caregiver), `docs/04-glossary.md`
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
- Trạng thái Pet `DECEASED`/`TRANSFERRED` (glossary `PetStatus`) không có cột ERD
  (`pets` chỉ có `is_active`) và không có op xử lý pet chết → TBD Q8, loại khỏi v1.
- `ManagePetOwnership` v1 chỉ bao phủ chuyển giao sở hữu (`TRANSFERRED` theo nghĩa
  đổi chủ, giữ nguyên bản ghi Pet).

---

## A. Confirmed Customer & Pet API (9 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /pets` | `AddPet` — `01#4`, RULE-04-01/03 |
| 2 | `GET /pets` | (list pets thuộc quyền) |
| 3 | `GET /pets/{id}` | `ViewPet` — `01#4`, RULE-04-09 |
| 4 | `PATCH /pets/{id}` | `UpdatePet` — `01#4`, RULE-04-03/04 |
| 5 | `POST /pets/{id}/transfer` | `ManagePetOwnership` (chuyển giao sở hữu) — `01#4`, RULE-04-01/03 |
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
| UpdatePet | Customer (/Receptionist hỗ trợ) | Pet tồn tại | RULE-04-03 (core identity chỉ Primary Owner), RULE-04-04 (caregiver cấm sửa core) | PATCH | `/pets/{id}` | Bearer | Field-level theo actor (server enforce) | none | Idempotent | Core = species/breed/dob/gender CONFIRMED |
| ManagePetOwnership | Primary Owner | Pet + chủ mới hợp lệ | RULE-04-01/03 | POST | `/pets/{id}/transfer` | Bearer | Chỉ Primary Owner | (chủ đổi; mọi delegation cũ? TBD Q7) | Idempotent | Cơ chế xác định chủ mới TBD Q6 |
| SearchCustomerPet | Receptionist | Tại quầy | RULE-04-02 (theo SĐT/CCCD/mã Pet/mã Customer) | GET | `/pets/search` | Bearer | `RECEPTIONIST` | none | Idempotent | Params PROPOSED (Q9) |
| InviteCaregiver | Primary Owner | Là chủ chính | RULE-04-01/04/05 | POST | `/pets/{id}/caregiver-invitations` | Bearer | Chỉ Primary Owner (`CanInviteCaregiver` invariant) | `[*] → INVITED` + `CaregiverInvited` (`expires_at = +7d` CONFIRMED) | 409 nếu đã có ACTIVE delegation cho caregiver đó (derived) | `caregiver_user_id` NULL được (mời bằng SĐT, ERD CONFIRMED) |
| Accept/RejectCaregiverInvitation | Caregiver | Lời mời còn `INVITED` + hạn | RULE-04-05/06 | POST | `/caregiver-invitations/{token}/accept`, `…/reject` | Bearer | Đúng người được mời (khớp SĐT, A3) | `INVITED → ACTIVE` / `→ REJECTED` | Idempotent (đã ACTIVE vẫn 200) | Kích hoạt trực tiếp ACTIVE, không duyệt trung gian (FSM-3 note) |
| RevokeCaregiver | Primary Owner | Delegation `ACTIVE` | RULE-04-04/08 (tức thì) | POST | `/pets/{id}/caregiver-revoke` | Bearer | Chỉ Primary Owner | `ACTIVE → REVOKED` + `CaregiverRevoked` | Idempotent | Chấm dứt mọi quyền ngay lập tức |

**ASSUMPTIONS dùng chung:** A1 pet mới ở trạng thái hoạt động (`is_active=true`; docs
không nêu state khởi tạo) · A2 microchip unique để chống tạo trùng (ERD không UK —
derived, TBD Q10) · A3 accept/reject yêu cầu đăng nhập đúng SĐT được mời (docs không
nêu cơ chế xác thực người nhận) · A4 delegation có thời hạn (`DelegationValidityPeriod`
CONFIRMED tồn tại nhưng độ dài mặc định TBD Q5 — PROPOSED: mời mang `validUntil?`).

---

## C. Detailed endpoint contract

> **Phạm vi v1 (Pets CRUD — implemented):** chỉ C1 trừ `POST /pets/{id}/transfer`.
> `transfer` (C1) + toàn bộ C2 (search) + C3 (caregiver delegation) = **OUT v1**
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
  Status: `200` · `400` · `401` · `403` · `404`.
- **`POST /pets/{id}/transfer`** — **OUT v1.** Request `{newOwnerId (PROPOSED; cách xác định chủ
  mới TBD Q6)}`. Chỉ Primary Owner. Response `200 {petId, ownerId}`.
  Delegations cũ khi đổi chủ: TBD Q7 (PROPOSED: giữ nguyên cho đến khi chủ mới revoke).

### C2. Counter search — OUT v1 (proposed)

- **`GET /pets/search`** — Query PROPOSED (Q9): `phone?`, `citizenId?`, `petCode?`,
  `customerCode?` (tiêu chí CONFIRMED RULE-04-02, tên param PROPOSED).
  Chỉ `RECEPTIONIST`. Response `200 {customers: [...], pets: [...]}` (shape PROPOSED).

### C3. Caregiver delegation — OUT v1 (proposed)

- **`POST /pets/{id}/caregiver-invitations`** — Request `{caregiverPhone (req CONFIRMED),
  validUntil? (PROPOSED, A4)}`. Chỉ Primary Owner (không phải owner → 403; caregiver
  mời thêm người → 403 RULE-04-04 CONFIRMED). Response
  `201 {invitationId, status: "INVITED", expiresAt (+7d CONFIRMED)}`. Không trả
  `invitation_token` thô? Token cần trao cho caregiver — kênh trao TBD Q12
  (PROPOSED: response mang token 1 lần, như OTP thì không lộ — mâu thuẫn cần PO chốt).
- **`POST /caregiver-invitations/{token}/accept`** — Guards: `INVITED` + còn hạn
  (quá hạn → `410` PROPOSED `INVITATION_EXPIRED`); đúng người (A3). Response
  `200 {delegationId, status: "ACTIVE"}`. Đã ACTIVE gọi lại → 200 idempotent.
- **`POST /caregiver-invitations/{token}/reject`** — Response `200 {status: "REJECTED"}`.
- **`POST /pets/{id}/caregiver-revoke`** — Request `{caregiverUserId (req)}`.
  Chỉ Primary Owner. Hiệu lực tức thì CONFIRMED. Response `200 {status: "REVOKED"}`.
- **Status chung:** `200`/`201` · `400` · `401` · `403` · `404` · `409` sai trạng thái
  · `410` hết hạn (PROPOSED extension, như auth-v1).

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
| Q4 | Xử lý pet chết (`DECEASED`): op nào, cột nào? | TBD (PO) — loại khỏi v1 | glossary có enum, ERD+ops không có |
| Q5 | `DelegationValidityPeriod` mặc định bao lâu? `validUntil?` có cho truyền không? | TBD (PO) | docs nêu tên, thiếu số |
| Q6 | `transfer` xác định chủ mới bằng gì (userId/phone)? Chủ mới có phải xác nhận không? | TBD (PO) | docs không chi tiết |
| Q7 | Delegations cũ khi đổi chủ: giữ hay hủy? | TBD (PO) — PROPOSED giữ | docs không nêu |
| Q8 | Xem Q4 (DECEASED) | TBD (PO) | — |
| Q9 | Tên param search (`phone/citizenId/petCode/customerCode`) | TBD (BE) — PROPOSED | RULE-04-02 cho tiêu chí, thiếu tên |
| Q10 | Microchip unique toàn hệ thống (A2)? | TBD (PO) | ERD không UK |
| Q11 | Receptionist có `GET /pets` list-all không hay chỉ search? | TBD (PO) — PROPOSED chỉ search | RULE-04-02 (tra cứu theo tiêu chí) |
| Q12 | Trao `invitation_token` cho caregiver qua kênh nào (response 1 lần / SMS)? | TBD (PO) | docs không nêu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/customer-pet-v1.yaml`](./openapi/customer-pet-v1.yaml).
