# Inventory API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Inventory & Warehouse Management (Module 12): tồn kho,
> điều chỉnh Maker-Checker, chuyển kho 2 bước, theo dõi lô/hạn (FEFO).
> Warehouse trung tâm = Store `facilityType=CENTRAL_WAREHOUSE` (A1) nên dùng chung
> endpoints với `storeId` của kho — không endpoint kho riêng.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#12`), `docs/02-business-rules.md`
> (RULE-12-01→13 + discrepancy invariant), `docs/03-state-machines.md` (FSM-11),
> `docs/04-glossary.md` (`12`), `docs/05-domain-model.md` (`4.12`),
> `docs/06-erd.md` (`inventory_items`/`inventory_reservations`/`inventory_adjustments`/
> `inventory_batches`/`stock_transfers`/`stock_transfer_lines`).
> **Contract máy đọc:** [`./openapi/inventory-v1.yaml`](./openapi/inventory-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `TriggerLowStockAlert` / `TriggerExpiryWarning` là jobs nền — không endpoint
  (reads ở đây cung cấp số liệu cho jobs).
- `inventory_reservations` (giữ 15m cho đơn Online) là effect nội bộ của M14 —
  không endpoint.
- Theo dõi lô/hạn hàng tổng hợp (`TrackBatch`/`TrackExpiry`): **CONFIRMED (V15)** —
  bảng `inventory_batches` (docs/06-erd.md §3.5) lưu chi tiết theo lô cho mọi hàng
  hóa (không chỉ vaccine như `vaccine_batches` M10); `GET …/inventory-batches` đọc
  từ bảng này, order theo FEFO. Q9 đã chốt.

---

## A. Confirmed Inventory API (13 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /stores/{id}/inventory` | `TrackInventory` — `01#12`, RULE-12-01 |
| 2 | `POST /stores/{id}/inventory/receive` | `ReceiveInventory` — `01#12` |
| 3 | `POST /stores/{id}/inventory/issue` | `IssueInventory` — `01#12`, RULE-12-05 |
| 4 | `POST /stores/{id}/inventory/count` | `CountInventory` (kiểm kê → sinh adjustment) — `01#12`, RULE-12-02 |
| 5 | `GET /stores/{id}/inventory-batches` | `TrackBatch`/`TrackExpiry` (read, storage CONFIRMED `inventory_batches` V15) — `01#12`, RULE-12-11 |
| 6 | `POST /stores/{id}/inventory-adjustments` | `AdjustInventory` (→ PENDING) — `01#12`, RULE-12-02 |
| 7 | `POST /inventory-adjustments/{id}/approve` | `ApproveInventoryAdjustment` — `01#12`, RULE-12-03 |
| 8 | `POST /inventory-adjustments/{id}/reject` | (từ chối — đối xứng approve) |
| 9 | `GET /stores/{id}/inventory-adjustments` | (tra cứu phiếu) |
| 10 | `POST /stock-transfers` | `CreateStockTransfer` (→ REQUESTED) — `01#12`, RULE-12-04 |
| 11 | `POST /stock-transfers/{id}/approve`, `…/reject` | `Approve/RejectStockTransfer` — `01#12`, RULE-12-06 |
| 12 | `POST /stock-transfers/{id}/cancel` | `CancelStockTransfer` — `01#12`, RULE-12-10 |
| 13 | `POST /stock-transfers/{id}/ship` | `ShipStockTransfer` (→ IN_TRANSIT) — `01#12`, RULE-12-05/07 |
| 14 | `POST /stock-transfers/{id}/receive` | `ReceiveStockTransfer` (→ RECEIVED) — `01#12`, RULE-12-08 |
| 15 | `POST /stock-transfers/{id}/receive-discrepancy` | `ReceiveStockTransferWithDiscrepancy` — `01#12`, RULE-12-08/09 |
| 16 | `POST /stock-transfers/{id}/resolve-discrepancy` | `ResolveStockTransferDiscrepancy` (→ RECEIVED) — `01#12`, RULE-12-09 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| TrackInventory | InventoryStaff | — | RULE-12-01 (riêng từng Store/Warehouse) | GET | `/stores/{id}/inventory` | Bearer | Staff scope | none | Idempotent | `available = physical − reserved` CONFIRMED |
| ReceiveInventory | InventoryStaff | — | RULE-12-01, RULE-12-11 | POST | `…/inventory/receive` | Bearer | Staff Store đó | + physical/available, upsert lô | Non-idempotent (mỗi call 1 lần nhập, cùng batchNumber thì cộng dồn) | `{productId, quantity, batchNumber (req), manufactureDate?, expiryDate?}` CONFIRMED |
| IssueInventory | InventoryStaff | `quantity ≤ available`; không hết hạn/hỏng | RULE-12-05 | POST | `…/inventory/issue` | Bearer | Staff Store đó | − physical/available | Non-idempotent | `{productId, quantity, reason?}` |
| CountInventory | InventoryStaff | — | RULE-12-02 | POST | `…/inventory/count` | Bearer | Staff Store đó | Sinh adjustment PENDING cho chênh lệch (A2) | Non-idempotent (mỗi kiểm 1 bản ghi) | `{productId, countedQuantity}` |
| TrackBatch/Expiry | InventoryStaff | — | RULE-12-11 (FEFO) | GET | `…/inventory-batches` | Bearer | Staff scope | none | Idempotent | Storage CONFIRMED `inventory_batches` (V15), order theo `expiryDate ASC` |
| AdjustInventory | InventoryStaff | Ghi rõ lý do | RULE-12-02 (reasons CONFIRMED) | POST | `…/inventory-adjustments` | Bearer | Staff Store đó | `[*] → PENDING` | Non-idempotent | Reasons: `DAMAGE`/`EXPIRY`/`THEFT`/`COUNT_VARIANCE`/`TRANSIT_VARIANCE` CONFIRMED |
| ApproveInventoryAdjustment | StoreManager (/OrgAdmin cho Warehouse) | `PENDING` + maker-checker | RULE-12-03 (`created_by != approved_by` → `MAKER_CHECKER_VIOLATION` CONFIRMED) | POST | `…/approve`, `…/reject` | Bearer | Manager scope | `PENDING → APPROVED/REJECTED` (approve cập nhật sổ) | Idempotent | — |
| CreateStockTransfer | InventoryStaff | 2 đầu cùng Org | RULE-12-04 | POST | `/stock-transfers` | Bearer | Staff (điểm xuất) | `[*] → REQUESTED` | Non-idempotent | `{fromStoreId, toStoreId, lines[]}` + `transferNumber` BE sinh (A3) |
| Approve/RejectStockTransfer | StoreManager (điểm xuất) | `REQUESTED` + maker-checker | RULE-12-06 (`MAKER_CHECKER_VIOLATION` CONFIRMED) | POST | `…/approve`, `…/reject` | Bearer | Manager điểm xuất | `REQUESTED → APPROVED/REJECTED` | Idempotent | — |
| CancelStockTransfer | InventoryStaff (tạo) | `REQUESTED`, chưa duyệt/xuất | RULE-12-10 | POST | `…/cancel` | Bearer | Creator | `REQUESTED → CANCELLED` | Idempotent | — |
| ShipStockTransfer | InventoryStaff | `APPROVED` + đủ available + không hết hạn | RULE-12-05/07 (− available nguồn, `IN_TRANSIT` trung gian) | POST | `…/ship` | Bearer | Staff điểm xuất | `APPROVED → IN_TRANSIT` | Idempotent (đã ship trả lại bản ghi) | Hàng transit không tính cho điểm nhận |
| ReceiveStockTransfer | InventoryStaff | `IN_TRANSIT`, đủ + nguyên vẹn | RULE-12-08 (1) | POST | `…/receive` | Bearer | Staff điểm nhận | `IN_TRANSIT → RECEIVED` (+ available đích) | Idempotent | — |
| ReceiveStockTransferWithDiscrepancy | InventoryStaff | Phát hiện thiếu/thừa/hỏng | RULE-12-08 (2): phương trình `shipped = received + damaged + lost` | POST | `…/receive-discrepancy` | Bearer | Staff điểm nhận | `IN_TRANSIT → DISCREPANCY_RECORDED` (nhập phần nguyên vẹn + cách ly hỏng) | Idempotent | `{lines: [{received, damaged, lost}]}` — tổng khớp shipped |
| ResolveStockTransferDiscrepancy | StoreManager (điểm nhận) | Đã có adjustment `TRANSIT_VARIANCE` APPROVED | RULE-12-09 | POST | `…/resolve-discrepancy` | Bearer | Manager điểm nhận | `DISCREPANCY_RECORDED → RECEIVED` | Idempotent | `{adjustmentId}` — adjustment lập qua endpoint AdjustInventory |

**ASSUMPTIONS dùng chung:** A1 warehouse dùng chung endpoints (phân biệt bằng
`facilityType`) · A2 count sinh adjustment chênh lệch (maker) thay vì chỉ log —
nếu không chênh thì không sinh gì · A3 `transferNumber` BE sinh unique (ERD có cột,
thiếu format — như `appointmentNumber`).

---

## C. Detailed endpoint contract

### C1. Stock & adjustments (proposed)

- **`GET /stores/{id}/inventory`** — Query PROPOSED: `productId?`, `lowOnly?`
  (`available ≤ min_stock_level` CONFIRMED ngưỡng ERD), `page`, `pageSize`.
  Response `200 {items: [{productId, sku, quantityPhysical, quantityReserved,
  quantityAvailable, minStockLevel}], …}`.
- **`POST …/inventory/receive`** — `{productId (req), quantity (req, >0),
  batchNumber (req, RULE-12-11), manufactureDate?, expiryDate?}`. Upsert
  `inventory_batches` theo unique key (storeId, productId, batchNumber) — cùng
  batchNumber thì cộng dồn quantity. Response `200 {…số mới}`.
- **`POST …/inventory/issue`** — `{productId (req), quantity (req, >0), reason?}`.
  Guards RULE-12-05: `quantity ≤ available` (rollup, fast-path) và tổng các lô
  chưa hết hạn đủ `quantity` (FEFO, guard thật — RULE-12-11). Vi phạm → `400
  BUSINESS_RULE_VIOLATION` (BusinessRuleViolationException theo convention 04 —
  không phải 409, 409 chỉ dành cho InvalidStateTransition/ConcurrencyConflict).
- **`POST …/inventory/count`** — `{productId (req), countedQuantity (req, ≥0)}`.
  Chênh → sinh adjustment `COUNT_VARIANCE` PENDING (response kèm `adjustmentId`);
  khớp (variance 0) → không lưu gì, `adjustmentId: null` (Q5 DECIDED).
- **`POST …/inventory-adjustments`** — `{productId (req), quantityAdjusted (req,
  ≠0, +/-), reason (req: 5 giá trị CONFIRMED)}`. → `PENDING`. Status:
  `201` · `400` · `401` · `403` · `404`.
- **`POST /inventory-adjustments/{id}/approve`** — Guards: `PENDING` +
  `approver != created_by` (bằng nhau → `400 MAKER_CHECKER_VIOLATION` CONFIRMED — BusinessRuleViolationException theo convention 04, tách khỏi `403 ACCESS_DENIED_SCOPE_MISMATCH`).
  Approve cập nhật sổ ngay (cùng transaction). **`…/reject`** — `{reason?}` →
  `REJECTED`. Status: `200` · `401` · `403` · `404` · `409` sai trạng thái.

### C2. Stock transfers (proposed)

- **`POST /stock-transfers`** — `{fromStoreId (req), toStoreId (req),
  lines: [{productId, quantity}] (req, min 1)}`. Guards: cùng Org (khác Org → `403`
  CONFIRMED RULE-12-04). Response `201 {transferId, transferNumber, status:
  "REQUESTED"}`. Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`…/approve`** — Manager điểm xuất + maker-checker. **`…/reject`** —
  `{reason?}`. **`…/cancel`** — creator + `REQUESTED` (RULE-12-10).
- **`…/ship`** — Guards: `APPROVED` + đủ available từng line + không hết hạn
  (RULE-12-05). Trừ available nguồn, hàng `IN_TRANSIT`. Response `200 {status:
  "IN_TRANSIT", shippedAt}`.
- **`…/receive`** — Từ `IN_TRANSIT`, nguyên vẹn đủ số. Tăng available đích ngay.
  Response `200 {status: "RECEIVED", receivedAt}`.
- **`…/receive-discrepancy`** — `{lines: [{productId, receivedQuantity,
  damagedQuantity, lostQuantity}]}`. Guard phương trình CONFIRMED
  (`shipped = received + damaged + lost`, sai → `400`). Nhập phần nguyên vẹn +
  cách ly hỏng (`DAMAGED_STOCK`) + hạch toán thất thoát (effects). Response
  `200 {status: "DISCREPANCY_RECORDED"}`.
- **`…/resolve-discrepancy`** — `{adjustmentId (req)}`. Guards: adjustment
  `TRANSIT_VARIANCE` đã `APPROVED` (RULE-12-09). → `RECEIVED`.
- **Status chung transfer actions:** `200` · `400` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Mọi trừ kho (issue/ship/dispense) check `available` + hạn trong cùng transaction
   + optimistic lock (`version` ERD) → hết hàng đồng thời ra `409`.
2. Maker-checker 2 chỗ (adjustment + transfer) với code CONFIRMED duy nhất
   `MAKER_CHECKER_VIOLATION`.
3. Phương trình sai lệch enforce server-side (`400` khi tổng lệch).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `transferNumber` format? | TBD (BE) — như `appointmentNumber` | ERD có cột |
| Q4 | Issue không qua đơn (hao hụt dùng nội bộ) có cần adjustment kèm không? | TBD (PO) | docs tách issue/adjust |
| Q5 | Count khớp (variance 0) có lưu biên bản kiểm kê không? | **DECIDED (BE):** Không — trả `variance: 0, adjustmentId: null`, không ghi gì (A2 promoted) | RULE-12-02 |
| Q6 | `min_stock_level` ai cấu hình (ERD default 5, không op)? | TBD (PO) | ERD có cột, ops không có |
| Q7 | Thừa hàng khi nhận (received > shipped) xử lý sao (phương trình chỉ có thiếu/hỏng/mất)? | TBD (PO) | RULE-12-09 |
| Q8 | Điều chuyển Store↔Warehouse khác gì Store↔Store (người duyệt)? | TBD (PO) | RULE-12-06 (manager điểm xuất) |
| Q9 | Lô/hạn hàng tổng hợp lưu ở đâu (TrackBatch/Expiry thiếu bảng)? | **DECIDED (BE):** Bảng `inventory_batches` mới (V15, docs/06-erd.md §3.5) — chi tiết theo lô, tách khỏi rollup `inventory_items` | ERD V15 |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/inventory-v1.yaml`](./openapi/inventory-v1.yaml).
