# Procurement API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Procurement Management (Module 13): yêu cầu mua nội bộ,
> đơn đặt NCC, kiểm hàng + nhận hàng, đóng đơn giao thiếu.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#13`), `docs/02-business-rules.md`
> (RULE-13-01→08), `docs/03-state-machines.md` (FSM-12 PurchaseRequest, FSM-13
> PurchaseOrder), `docs/04-glossary.md` (`13`), `docs/05-domain-model.md` (`4.13`),
> `docs/06-erd.md` (`purchase_orders`/`goods_receipts`; PR chỉ có trong sơ đồ quan hệ,
> chưa có spec cột — storage TBD Q9).
> **Contract máy đọc:** [`./openapi/procurement-v1.yaml`](./openapi/procurement-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ManageSupplier` (danh mục NCC + trạng thái `ACTIVE`) **loại khỏi v1**: ERD không có
  bảng suppliers (`purchase_orders.supplier_name` là text tự do) — PO mang tên NCC
  text, master NCC để TBD Q8 (tương tự policy M03).
- `UpdateInventory` (tăng tồn sau nhận) là effect của `ReceiveGoods` — không endpoint.
- Đơn `CLOSED`/`CANCELLED` bất biến tuyệt đối (RULE-13-08): mọi action sau kết thúc → 409.

---

## A. Confirmed Procurement API (10 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /purchase-requests` | `CreatePurchaseRequest` (→ DRAFT) — `01#13`, RULE-13-01 |
| 2 | `POST /purchase-requests/{id}/submit` | `SubmitPurchaseRequest` (→ SUBMITTED) — `01#13` |
| 3 | `POST /purchase-requests/{id}/approve` | `ApprovePurchaseRequest` — `01#13`, RULE-13-02 |
| 4 | `POST /purchase-requests/{id}/reject` | `RejectPurchaseRequest` — `01#13`, RULE-13-02 |
| 5 | `POST /purchase-requests/{id}/cancel` | `CancelPurchaseRequest` — `01#13`, RULE-13-03 |
| 6 | `POST /purchase-orders` | `CreatePurchaseOrder` (từ PR APPROVED → ISSUED) — `01#13`, RULE-13-04 |
| 7 | `GET /purchase-orders/{id}` | `TrackPurchaseOrder` — `01#13` |
| 8 | `POST /purchase-orders/{id}/inspect` | `InspectGoods` (bắt buộc trước receive) — `01#13`, RULE-13-05 |
| 9 | `POST /purchase-orders/{id}/receive` | `ReceiveGoods` (+ tăng tồn) — `01#13`, RULE-13-05/06 |
| 10 | `POST /purchase-orders/{id}/cancel` | `CancelPurchaseOrder` (chưa giao) — `01#13`, RULE-13-08 |
| 11 | `POST /purchase-orders/{id}/cancel-remaining` | `CancelRemainingPurchaseOrder` (→ CLOSED) — `01#13`, RULE-13-07 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreatePurchaseRequest | InventoryStaff | Từ Store/Warehouse có nhu cầu | RULE-13-01 (lines + giá dự kiến + NCC khuyến nghị) | POST | `/purchase-requests` | Bearer | Staff scope | `[*] → DRAFT` | Non-idempotent | Shape PROPOSED, storage TBD Q9 |
| SubmitPurchaseRequest | InventoryStaff | `DRAFT` | (lên duyệt) | POST | `…/submit` | Bearer | Creator | `DRAFT → SUBMITTED` | Idempotent | — |
| Approve/RejectPurchaseRequest | StoreManager (/OrgAdmin cho Warehouse) | `SUBMITTED` + maker-checker | RULE-13-02 (`MAKER_CHECKER_VIOLATION` CONFIRMED; reject kèm lý do) | POST | `…/approve`, `…/reject` | Bearer | Manager scope | `SUBMITTED → APPROVED/REJECTED` | Idempotent | — |
| CancelPurchaseRequest | InventoryStaff | `DRAFT`/`SUBMITTED` (chưa duyệt) | RULE-13-03 | POST | `…/cancel` | Bearer | Creator | `→ CANCELLED` | Idempotent | — |
| CreatePurchaseOrder | InventoryStaff (/Manager) | PR `APPROVED` + NCC hợp lệ | RULE-13-04 (PO từ PR duyệt; NCC `ACTIVE` — master TBD Q8 nên check tên text, A1) | POST | `/purchase-orders` | Bearer | Trong scope PR | `[*] → ISSUED` + `poNumber` BE sinh (A2) | Non-idempotent | `{purchaseRequestId, supplierName, lines?}` |
| TrackPurchaseOrder | Staff | — | — | GET | `/purchase-orders/{id}` | Bearer | Trong scope | none | Idempotent | Kèm receipts + nhận lũy kế |
| InspectGoods | InventoryStaff | Hàng đã giao | RULE-13-05 (kiểm trước nhận; fail → từ chối + biên bản trả NCC) | POST | `…/inspect` | Bearer | Staff điểm nhận | (ghi biên bản, A3) | Idempotent (ghi đè biên bản) | `{acceptedLines?, rejectedLines? + returnNote?}` PROPOSED |
| ReceiveGoods | InventoryStaff | Đã inspect đạt | RULE-13-05/06/08 (+ tăng tồn + lô/HSD; CLOSED/CANCELLED cấm nhận) | POST | `…/receive` | Bearer | Staff điểm nhận | `ISSUED → PARTIALLY_RECEIVED/RECEIVED` (+ `GoodsReceived`) | Idempotent theo đợt nhận (A4) | `{receiptLines: [{productId, receivedQty, batchNumber?, expiryDate?}]}` |
| CancelPurchaseOrder | InventoryStaff / StoreManager | Chưa giao hàng | RULE-13-08 | POST | `…/cancel` | Bearer | Trong scope | `ISSUED → CANCELLED` | Idempotent | Đã nhận 1 phần → dùng cancel-remaining |
| CancelRemainingPurchaseOrder | StoreManager / InventoryStaff | `PARTIALLY_RECEIVED` + NCC hết khả năng | RULE-13-07 | POST | `…/cancel-remaining` | Bearer | Trong scope | `PARTIALLY_RECEIVED → CLOSED` | Idempotent | Hủy nghĩa vụ phần còn thiếu |

**ASSUMPTIONS dùng chung:** A1 NCC là text `supplierName` (không master check được —
ERD text); check `ACTIVE` để TBD Q8 · A2 `poNumber` BE sinh (ERD có cột, thiếu format) ·
A3 biên bản inspect lưu trên goods_receipt (người kiểm + thời điểm CONFIRMED qua
`inspected_by`; hàng fail lập biên bản trả NCC — shape TBD Q10) · A4 mỗi đợt `receive`
1 goods_receipt; nhận lặp cùng đợt trả lại bản ghi cũ.

---

## C. Detailed endpoint contract

### C1. Purchase requests (proposed — shapes PROPOSED, storage TBD Q9)

- **`POST /purchase-requests`** — Request `{storeId (req), lines: [{productId (req),
  quantity (req), expectedPrice?, recommendedSupplier?}] (req, min 1)}`.
  → `DRAFT`. Response `201 {requestId, status: "DRAFT"}`.
  Status: `201` · `400` · `401` · `403` · `404`.
- **`…/submit`** — `DRAFT → SUBMITTED`. **`…/approve`** — maker-checker
  (`MAKER_CHECKER_VIOLATION` CONFIRMED). **`…/reject`** — `{reason?}` →
  `REJECTED` (lý do CONFIRMED RULE-13-02). **`…/cancel`** — chỉ `DRAFT`/`SUBMITTED`
  (RULE-13-03 CONFIRMED).
- **Status chung:** `200` · `401` · `403` · `404` · `409` sai trạng thái.

### C2. Purchase orders (proposed)

- **`POST /purchase-orders`** — Request `{purchaseRequestId (req),
  supplierName (req — text, A1), lines?: [{productId, quantity, unitPrice?}]
  (mặc định copy từ PR — A5)}`. Guards: PR `APPROVED` (chưa duyệt → `409`).
  Response `201 {orderId, poNumber, status: "ISSUED", totalAmount}`.
  Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`GET /purchase-orders/{id}`** — Response order + lines + receipts + nhận lũy kế.
  Status: `200` · `401` · `403` · `404`.
- **`…/inspect`** — Request `{acceptedLines?: [{productId, quantity}],
  rejectedLines?: [{productId, quantity, reason}], returnNote?}` (A3, PROPOSED).
  Chưa giao mà inspect → `409`. Response `200 {receiptId?, inspected: true}`.
  Hàng fail: từ chối nhận + biên bản trả NCC (effect).
- **`…/receive`** — Request `{receiptLines: [{productId (req), receivedQuantity (req),
  batchNumber?, expiryDate?}] (req)}`. Guards: đã inspect (RULE-13-05) + chưa kết thúc
  (RULE-13-08). Effects: tăng physical+available đúng điểm nhận + lưu lô/HSD
  (RULE-13-06). `ISSUED → PARTIALLY_RECEIVED` (còn thiếu) / `RECEIVED` (đủ).
  Response `200 {receiptId, status, receivedLines}`.
- **`…/cancel`** — Chưa nhận đợt nào → `CANCELLED`. **`…/cancel-remaining`** —
  Từ `PARTIALLY_RECEIVED` → `CLOSED` (RULE-13-07). Sau `CLOSED`/`CANCELLED`: receive/
  sửa → `409` (RULE-13-08 CONFIRMED bất biến).
- **Status chung actions:** `200` · `400` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Maker-checker PR với code CONFIRMED duy nhất (giống M12).
2. Bất biến đơn kết thúc enforce server-side (RULE-13-08) — test bắt buộc.
3. Tăng tồn + lưu lô/HSD cùng transaction với receive (RULE-13-06).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | PR cho Warehouse khác PR Store gì (người duyệt OrgAdmin)? | TBD (PO) | RULE-13-02 |
| Q4 | PO có sửa được sau ISSUED (thêm line, đổi NCC) hay bất biến? | TBD (PO) | docs không nêu |
| Q5 | Nhận vượt số đặt (thừa) xử lý sao? | TBD (PO) — như M12 Q7 | docs không nêu |
| Q6 | Thanh toán NCC ghi ở đâu (ngoài scope 25 modules?)? | TBD (PO) | docs không có |
| Q7 | Ai được tạo PO (staff tạo hay chỉ manager)? | TBD (PO) | `01#13` gán InventoryStaff tạo PR; PO không gán actor tạo |
| Q8 | Master NCC (ACTIVE/inactive) lưu ở đâu — bảng mới? | TBD (PO/BE) — loại khỏi v1 | ERD thiếu bảng |
| Q9 | `purchase_requests` + lines lưu ở đâu (ERD thiếu spec)? | TBD (BE) | ERD thiếu |
| Q10 | Shape biên bản inspect/reject (A3)? | TBD (BE) | RULE-13-05 |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/procurement-v1.yaml`](./openapi/procurement-v1.yaml).
