# Membership API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Membership & Loyalty Management (Module 19): hạng hội viên,
> tích/tiêu/hết hạn/điều chỉnh điểm. Dữ liệu cách ly theo Organization (RULE-19-10).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#19`), `docs/02-business-rules.md`
> (RULE-19-01→10), `docs/03-state-machines.md` (FSM-9), `docs/04-glossary.md` (`19`),
> `docs/05-domain-model.md` (`4.19`), `docs/06-erd.md` (`memberships`/
> `loyalty_point_ledgers`).
> **Contract máy đọc:** [`./openapi/membership-v1.yaml`](./openapi/membership-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `AddLoyaltyPoint` / `DeductLoyaltyPoint` / `ExpireLoyaltyPoint` là effects/jobs hệ
  thống (sau `PaymentSucceeded`, khi redeem, quét định kỳ) — không endpoint riêng.
- Sổ cái hết hạn điểm: `loyalty_point_ledgers` không có cột expiry/status → bookkeeping
  hết hạn TBD Q9; v1 chỉ trừ khả dụng theo chính sách (ghi `points_change` âm + type
  PROPOSED `EXPIRED`? Không — type enum ERD chỉ 3 giá trị; hết hạn thể hiện bằng điều
  chỉnh khả dụng, TBD Q9, không phát minh type mới).
- Tỷ lệ tích/quy đổi theo hạng + thời hạn điểm (12 tháng "ví dụ") + grace gia hạn +
  phí nâng hạng: toàn bộ số chính sách TBD (Q6/Q7/Q8).

---

## A. Confirmed Membership API (8 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /memberships` | `RegisterMembership` (→ ACTIVE ngay, không PENDING) — `01#19`, RULE-19-01/02 |
| 2 | `GET /memberships/me` | `ViewMembership` — `01#19`, RULE-19-01 |
| 3 | `GET /memberships/{id}/points` | `ViewLoyaltyPoint` — `01#19`, RULE-19-01 |
| 4 | `POST /memberships/{id}/renew` | `RenewMembership` (ACTIVE/grace → ACTIVE + hạn mới) — `01#19`, RULE-19-03 |
| 5 | `POST /memberships/{id}/upgrade` | `UpgradeMembership` (cũ → UPGRADED + mới ACTIVE) — `01#19`, RULE-19-04 |
| 6 | `POST /memberships/{id}/redeem` | `RedeemLoyaltyPoint` (cấn trừ hóa đơn) — `01#19`, RULE-19-06 |
| 7 | `POST /memberships/{id}/adjust` | `AdjustLoyaltyPoint` (lý do + audit) — `01#19`, RULE-19-08 |
| 8 | `GET /memberships/{id}/ledger` | (sổ cái minh bạch — derived read) |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| RegisterMembership | Customer | Trong Org | RULE-19-01/02 (ACTIVE ngay; hạng BRONZE default ERD — A1; `valid_until` bắt buộc → +12 tháng PROPOSED TBD Q8) | POST | `/memberships` | Bearer | Self (1 membership/người/Org — A2) | `[*] → ACTIVE` | 409 đã có ACTIVE (A2) | `organizationId` req (cách ly Org 19-10) |
| ViewMembership / ViewLoyaltyPoint | Customer | Của mình | RULE-19-01 | GET | `/memberships/me`, `…/points` | Bearer | Owner | none | Idempotent | Query `organizationId?` (mỗi Org 1 hồ sơ) |
| RenewMembership | Customer / Receptionist | `ACTIVE` hoặc grace | RULE-19-03 | POST | `…/renew` | Bearer | Owner / receptionist | `ACTIVE → ACTIVE` (hạn mới) | Idempotent (gia hạn lặp trả hạn cũ — A3) | `{extendMonths?}` PROPOSED; phí TBD Q6 |
| UpgradeMembership | Customer / StoreManager | Đủ điều kiện (chi tiêu/mua gói — TBD Q6) | RULE-19-04 (cũ UPGRADED terminal + mới ACTIVE) | POST | `…/upgrade` | Bearer | Owner / manager | `ACTIVE → UPGRADED` + `[*] → ACTIVE` (mới) | Idempotent (đã hạng đích trả hồ sơ mới) | `{newTier?}` PROPOSED (auto-tier TBD) |
| RedeemLoyaltyPoint | Customer | Điểm khả dụng + account không LOCKED | RULE-19-06 (chính chủ + không vượt khả dụng) + RULE-19-09 (LOCKED đình chỉ) + RULE-19-10 (đúng Org) | POST | `…/redeem` | Bearer | Owner | − điểm + ledger `REDEEMED_DISCOUNT` | Non-idempotent (mỗi call trừ thật — như administer, A4) | `{points, invoiceId}`; tỷ lệ quy đổi TBD Q6 |
| AdjustLoyaltyPoint | StoreManager / OrgAdmin | Lý do chính đáng | RULE-19-08 (lý do bắt buộc + audit) | POST | `…/adjust` | Bearer | Manager/Admin | ± điểm + ledger `MANUAL_ADJUSTMENT` | Non-idempotent | `{pointsChange (≠0), reason (req)}` |
| (ledger read) | Customer / Staff | — | Minh bạch đối soát | GET | `…/ledger` | Bearer | Owner / scope | none | Idempotent | Types CONFIRMED ERD (3 giá trị) |

**ASSUMPTIONS dùng chung:** A1 hạng khởi tạo BRONZE (ERD default) · A2 1 ACTIVE/
người/Org (docs: 1 hồ sơ/customer/org + nâng hạng đóng cũ mở mới) · A3 renew lặp
trả hạn hiện tại thay vì cộng dồn (chống cộng dồn sai) · A4 redeem như administer:
mỗi call trừ thật, cấm retry mù khi timeout (tra ledger trước).

---

## C. Detailed endpoint contract

### C1. Membership (proposed)

- **`POST /memberships`** — Request `{organizationId (req)}`. → `ACTIVE`/`BRONZE`
  (A1) + `validUntil = +12 tháng` (PROPOSED TBD Q8). Đã có ACTIVE → `409` (A2).
  Response `201 {membershipId, tier: "BRONZE", status: "ACTIVE", currentPoints: 0,
  validUntil}`. Status: `201` · `400` · `401` · `403` · `404` (org) · `409`.
- **`GET /memberships/me?organizationId=`** — Response membership + points.
  **`GET /memberships/{id}/points`** — `{currentPoints, ledgerUrl?}` + cảnh báo
  đình chỉ khi LOCKED (RULE-19-09).
- **`POST …/renew`** — `{extendMonths? (default 12 PROPOSED)}`. Guards: `ACTIVE`
  hoặc grace (grace bao lâu TBD Q8). → `ACTIVE` hạn mới. Status chuẩn.
- **`POST …/upgrade`** — `{newTier? (PROPOSED; auto theo chi tiêu TBD Q6)}`.
  Guards: `ACTIVE`. Effects CONFIRMED: cũ → `UPGRADED` (terminal) + mở mới `ACTIVE`
  hạng đích (kế thừa/quy đổi điểm? TBD Q10). Response `200 {oldMembershipId,
  newMembershipId, tier}`.

### C2. Points (proposed)

- **`POST …/redeem`** — `{points (req, >0), invoiceId (req)}`. Guards CONFIRMED:
  chính chủ · `points ≤ available` · account không `LOCKED` (đình chỉ 19-09 →
  `403` PROPOSED `MEMBERSHIP_SUSPENDED`) · invoice cùng Org (19-10).
  Effects: − điểm + ledger `REDEEMED_DISCOUNT` + cấn trừ hóa đơn (M15).
  Response `200 {membershipId, redeemedPoints, remainingPoints, discountAmount?}`
  (`discountAmount` theo tỷ lệ TBD Q6 — trả về để invoice dùng; tính ở M19 hay M15
  TBD Q11, PROPOSED tính ở đây).
- **`POST …/adjust`** — `{pointsChange (req, ≠0), reason (req CONFIRMED)}`.
  Manager/Admin + audit bắt buộc (RULE-19-08). Ledger `MANUAL_ADJUSTMENT`.
  Response `200 {currentPoints}`.
- **`GET …/ledger`** — Query PROPOSED: `page`, `pageSize`, `type?`. Response page
  entries `{id, pointsChange, transactionType, referenceId?, createdAt}`.
- Tích điểm auto (sau `PaymentSucceeded`, RULE-19-05) và hết hạn quét (RULE-19-07)
  là effects/jobs — không endpoint.

---

## D. Security & reliability

1. Mọi biến động điểm qua ledger + cập nhật `current_points` cùng transaction
   (sổ cái là chân lý đối soát).
2. LOCKED đình chỉ toàn bộ quyền lợi (RULE-19-09) — check ở redeem/view.
3. Cách ly Org tuyệt đối (RULE-19-10) — invoice khác Org → 403.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `valid_until` khởi tạo + chu kỳ hạng (A: +12 tháng)? | TBD (PO) | ERD bắt buộc, thiếu số |
| Q4 | Grace gia hạn bao lâu? | TBD (PO) | RULE-19-03 |
| Q5 | Renew có phí không, phí bao nhiêu? | TBD (PO) | docs không nêu |
| Q6 | Tỷ lệ tích/quy đổi theo hạng + điều kiện auto-upgrade? | TBD (PO) | RULE-19-04/05/06 |
| Q7 | Điểm hết hạn bookkeeping thế nào (ledger thiếu cột)? | TBD (BE) | ERD thiếu |
| Q8 | Xem Q3 (validity) | TBD (PO) | — |
| Q9 | Xem Q7 (expiry ledger) | TBD (BE) | — |
| Q10 | Điểm khi upgrade: kế thừa, quy đổi hay reset? | TBD (PO) | RULE-19-04 |
| Q11 | `discountAmount` tính ở M19 hay M15? (A: M19 trả về) | TBD (BE) | ranh giới module |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/membership-v1.yaml`](./openapi/membership-v1.yaml).
