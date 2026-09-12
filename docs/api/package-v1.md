# Package API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Package Management (Module 20): mua/kích hoạt/trừ lượt/hủy/
> điều chỉnh/hoàn lượt no-show + phạt vắng mặt tự động.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#20`), `docs/02-business-rules.md`
> (RULE-20-01→08 + lifecycle/refund invariants), `docs/03-state-machines.md` (FSM-10),
> `docs/04-glossary.md` (`20`), `docs/05-domain-model.md` (`4.20`),
> `docs/06-erd.md` (`service_packages`/`package_usage_records`).
> **Contract máy đọc:** [`./openapi/package-v1.yaml`](./openapi/package-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ActivatePackage` auto (qua `PaymentSucceeded` / check-in lượt đầu) và `ProcessPackageExpiry`
  và phạt no-show auto là effects/jobs — endpoint `activate` chỉ cho đường POS thủ công.
- `TrackPackageUsage` = read lịch sử (không phải op ghi).
- `consumption_type` (RULE-20-08 `NO_SHOW_PENALTY`) không có cột ERD → response
  usage mang `consumptionType` PROPOSED + storage TBD Q9.
- Không có master catalog gói trong ERD (mermaid nhắc `package_catalog_id` nhưng spec
  cột không có) → mua gói khai inline (service + units + giá), TBD Q10.

---

## A. Confirmed Package API (9 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /packages` | `PurchasePackage` (→ PURCHASED) — `01#20`, RULE-20-01 |
| 2 | `GET /packages` | `ViewPackage` (của mình) — `01#20`, RULE-20-05 |
| 3 | `GET /packages/{id}` | `ViewPackage` (detail + số dư) |
| 4 | `POST /packages/{id}/activate` | `ActivatePackage` (POS thủ công) — `01#20`, RULE-20-01 |
| 5 | `POST /packages/{id}/confirm-usage` | `ConfirmPackageUsage` (trừ lượt) — `01#20`, RULE-20-02/04/05 |
| 6 | `GET /packages/{id}/usage` | `TrackPackageUsage` (read) — `01#20`, RULE-20-04 |
| 7 | `POST /packages/{id}/cancel` | `CancelPackage` (→ CANCELLED + auto RefundRequested) — `01#20`, RULE-20-06 |
| 8 | `POST /packages/{id}/adjust` | `AdjustPackage` (lý do + audit) — `01#20`, RULE-20-07 |
| 9 | `POST /packages/{id}/refund-unit` | `RefundPackageUnit` (bất khả kháng + lý do) — `01#20`, RULE-20-08 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| PurchasePackage | Customer | Service + units + giá + hạn | RULE-20-01 (gắn customer + quyền lợi) | POST | `/packages` | Bearer | Self | `[*] → PURCHASED` | Non-idempotent | Inline `{serviceId, totalUnits, purchasePrice, expiresAt?}` (không catalog — TBD Q10); `packageCode` BE sinh (A1) |
| ViewPackage | Customer | Của mình (+ pet/ủy quyền) | RULE-20-05 | GET | `/packages…` | Bearer | Owner | none | Idempotent | Kèm `remainingUnits` + history link |
| ActivatePackage | Receptionist (POS) | `PURCHASED` + đã trả | RULE-20-01 (quầy: ngay sau trả) | POST | `…/activate` | Bearer | Receptionist | `PURCHASED → ACTIVATED` | Idempotent | Auto paths (event/check-in) không endpoint |
| ConfirmPackageUsage | Receptionist | `ACTIVATED`/`PARTIALLY_CONSUMED` + còn hạn + đủ lượt + pet thuộc quyền | RULE-20-02/04/05 | POST | `…/confirm-usage` | Bearer | Receptionist | `→ PARTIALLY_CONSUMED` (`>0`) / `FULLY_CONSUMED` (`==0`) + usage record | Non-idempotent (mỗi call trừ lượt — như administer) | `{appointmentId (req), unitsConsumed? default 1}` |
| TrackPackageUsage | Customer / Staff | — | RULE-20-04 (mã GD + giờ + DV + store + NV + pet) | GET | `…/usage` | Bearer | Owner / scope | none | Idempotent | Fields record CONFIRMED (ghi vào response) |
| CancelPackage | StoreManager | `PURCHASED`/`ACTIVATED`/`PARTIALLY_CONSUMED` + policy Org | RULE-20-06 (auto `RefundRequested` theo công thức) | POST | `…/cancel` | Bearer | StoreManager | `→ CANCELLED` + refund effect (M17) | Idempotent | Công thức CONFIRMED (giá×còn/tổng − phí; phí TBD Q11) |
| AdjustPackage | StoreManager | Lý do | RULE-20-07 (lý do + audit) | POST | `…/adjust` | Bearer | StoreManager | (sửa `remainingUnits`) | Non-idempotent | `{newRemainingUnits?/deltaUnits?, reason (req)}` — shape TBD Q12, PROPOSED cả 2 + server suy ra |
| RefundPackageUnit | StoreManager | No-show penalty oan + bất khả kháng | RULE-20-08 (lý do bắt buộc + audit) | POST | `…/refund-unit` | Bearer | StoreManager | +1 lượt (không quá `totalUnits` — A2) | Idempotent theo penalty record? (A3: `{penaltyUsageId?}`) | `{reason (req), penaltyUsageId?}` |

**ASSUMPTIONS dùng chung:** A1 `packageCode` BE sinh unique (ERD UK, thiếu format) ·
A2 refund-unit không vượt `totalUnits` (docs không chặn — derived chống lạm) ·
A3 `penaltyUsageId` optional để hoàn đúng lượt phạt (không có → hoàn lượt gần nhất,
TBD Q13).

---

## C. Detailed endpoint contract

### C1. Buy & view (proposed)

- **`POST /packages`** — Request `{serviceId (req), totalUnits (req, >0),
  purchasePrice (req, ≥0), expiresAt? (PROPOSED — ERD bắt buộc, policy hạn TBD Q14)}`.
  → `PURCHASED` (`remainingUnits = totalUnits`). Thanh toán/invoice gói (M15
  `PACKAGE_INVOICE`) là flow ngoài — ở đây chỉ mở gói (TBD Q15).
  Response `201 {packageId, packageCode, status: "PURCHASED", remainingUnits}`.
  Status: `201` · `400` · `401` · `404`.
- **`GET /packages`**, **`GET /packages/{id}`** — Owner (+ pet scope). Response kèm
  `remainingUnits`/`totalUnits`/`expiresAt`/`status`.

### C2. Use (proposed)

- **`POST …/activate`** — Từ `PURCHASED`. Receptionist (POS). Response
  `200 {status: "ACTIVATED"}`. Auto paths không qua đây.
- **`POST …/confirm-usage`** — Request `{appointmentId (req), unitsConsumed?
  (default 1)}`. Guards CONFIRMED (RULE-20-02): state dùng được + còn hạn +
  `remaining ≥ consume` + pet thuộc quyền (owner/caregiver). Trừ lượt + usage record
  (mã GD + giờ + DV + store + NV + pet — CONFIRMED RULE-20-04).
  `FULLY_CONSUMED`/`EXPIRED` trừ tiếp → `409`. Hết lượt đúng 0 → `FULLY_CONSUMED`.
  Status: `200` · `400` · `401` · `403` · `404` · `409`.
- **`GET …/usage`** — Response `200 {items: [{usageId, appointmentId,
  unitsConsumed, consumptionType? (PROPOSED, TBD Q9), consumedAt, storeId,
  staffId, petId}]}`.

### C3. Cancel & adjust (proposed)

- **`POST …/cancel`** — Từ 3 states CONFIRMED. Effect: auto `RefundRequested`
  (M17) số tiền công thức CONFIRMED. Response `200 {status: "CANCELLED",
  refundId?, refundAmount?}`.
- **`POST …/adjust`** — `{newRemainingUnits?, deltaUnits?, reason (req)}`
  (một trong hai số — server suy ra số còn lại; shape TBD Q12). Audit bắt buộc.
  Response `200 {remainingUnits}`.
- **`POST …/refund-unit`** — `{reason (req), penaltyUsageId?}` (A3). Guards: không
  vượt total (A2). Response `200 {remainingUnits}`.
- **Status chung:** `200` · `400` · `401` · `403` (manager-only) · `404` · `409`.

---

## D. Security & reliability

1. Trừ lượt + ghi usage cùng transaction (không âm lượt đồng thời).
2. Phạt no-show auto (effect M06/M07) + hoàn tay có lý do — 2 đường tách bạch.
3. Mọi adjust/refund-unit kèm audit (RULE-20-07/08).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `packageCode` format? | TBD (BE) | ERD UK |
| Q4 | `expiresAt` mặc định khi mua không truyền (A: bắt buộc truyền)? | TBD (PO) | ERD bắt buộc |
| Q5 | Dùng lượt khác Store mua (chuỗi) được không? | TBD (PO) | RULE-20-05 (chính sách Org) |
| Q6 | 1 appointment trừ nhiều lượt (combo nhiều DV)? | TBD (PO) | `units_consumed` số nguyên |
| Q7 | Pet dùng lượt có phải đúng pet mua cho không (mua cho pet nào)? | TBD (PO) | purchase không gắn pet |
| Q8 | Phí hủy `CancellationAdminFee` bao nhiêu? | TBD (PO) | công thức CONFIRMED, phí TBD |
| Q9 | `consumption_type` lưu ở đâu (record thiếu cột)? | TBD (BE) | RULE-20-08 vs ERD |
| Q10 | Master catalog gói (không ERD)? | TBD (PO) | mermaid nhắc, spec thiếu |
| Q11 | Xem Q8 (phí hủy) | TBD (PO) | — |
| Q12 | Shape adjust (absolute vs delta)? (A: cả 2) | TBD (BE) | docs không nêu |
| Q13 | Hoàn đúng lượt phạt khi không có id (A3)? | TBD (PO) | docs không nêu |
| Q14 | Xem Q4 (hạn mặc định) | TBD (PO) | — |
| Q15 | Mua gói có qua invoice PACKAGE_INVOICE không, flow nào? | TBD (PO) | `InvoiceType` có PACKAGE |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/package-v1.yaml`](./openapi/package-v1.yaml).
