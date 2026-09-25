# Procurement API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Procurement Management (Module 13): yêu cầu mua nội bộ,
> đơn đặt NCC, kiểm hàng + nhận hàng, đóng đơn giao thiếu.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#13`), `docs/02-business-rules.md`
> (RULE-13-01→08), `docs/03-state-machines.md` (FSM-12 PurchaseRequest, FSM-13
> PurchaseOrder), `docs/04-glossary.md` (`13`), `docs/05-domain-model.md` (`4.13`),
> `docs/06-erd.md` (`suppliers`/`purchase_requests`/`purchase_request_lines`/
> `purchase_orders`/`purchase_order_lines`/`goods_receipts` — schema đầy đủ từ V1, audit
> columns bổ sung V16).
> **Contract máy đọc:** [`./openapi/procurement-v1.yaml`](./openapi/procurement-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Trạng thái triển khai (BE-2):** Endpoint #1-7 (PurchaseRequest CRUD + CreatePurchaseOrder/
TrackPurchaseOrder) và `ManageSupplier` (4 endpoint Supplier) **ĐÃ triển khai**
(RULE-13-01→04). Endpoint #8-11 (`InspectGoods`/`ReceiveGoods`/`CancelPurchaseOrder`/
`CancelRemainingPurchaseOrder`, RULE-13-05→08) **CHƯA triển khai** — cần tích hợp Inventory
(`UpdateInventory`), để dành task sau; `PurchaseOrder` hiện chỉ dừng ở `ISSUED`.

**Đóng băng phạm vi:**
- `ManageSupplier` — **CONFIRMED, trong scope** (sửa quyết định cũ): ERD đã có bảng
  `suppliers` thật (FK `purchase_orders.supplier_id`, đóng `GAP-PRC-01` từ Phase 4) —
  KHÔNG phải text tự do như bản nháp trước. `CreatePurchaseOrder` nhận `supplierId` (UUID),
  không phải `supplierName`.
- `UpdateInventory` (tăng tồn sau nhận) là effect của `ReceiveGoods` — không endpoint (vẫn
  ngoài scope task này).
- Đơn `CLOSED`/`CANCELLED` bất biến tuyệt đối (RULE-13-08): mọi action sau kết thúc → 409
  (ngoài scope task này — chưa có transition nào tới các state đó được triển khai).

---

## A. Confirmed Procurement API (11 endpoint scope + 4 endpoint Supplier)

**Triển khai xong (BE-2, RULE-13-01→04):**

| # | Endpoint | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /stores/{id}/purchase-requests` | `CreatePurchaseRequest` (→ DRAFT) — `01#13`, RULE-13-01 |
| 2 | `POST /purchase-requests/{id}/submit` | `SubmitPurchaseRequest` (→ SUBMITTED) — `01#13` |
| 3 | `POST /purchase-requests/{id}/approve` | `ApprovePurchaseRequest` — `01#13`, RULE-13-02 |
| 4 | `POST /purchase-requests/{id}/reject` | `RejectPurchaseRequest` — `01#13`, RULE-13-02 |
| 5 | `POST /purchase-requests/{id}/cancel` | `CancelPurchaseRequest` — `01#13`, RULE-13-03 |
| 6 | `POST /purchase-orders` | `CreatePurchaseOrder` (từ PR APPROVED → ISSUED) — `01#13`, RULE-13-04 |
| 7 | `GET /purchase-orders/{id}` | `TrackPurchaseOrder` — `01#13` |
| — | `POST /suppliers`, `PATCH /suppliers/{id}`, `GET /suppliers/{id}`, `GET /suppliers` | `ManageSupplier` — `01#13`, RULE-13-04 |

**Ngoài phạm vi — task sau (cần tích hợp Inventory):**

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 8 | `POST /purchase-orders/{id}/inspect` | `InspectGoods` (bắt buộc trước receive) — `01#13`, RULE-13-05 |
| 9 | `POST /purchase-orders/{id}/receive` | `ReceiveGoods` (+ tăng tồn) — `01#13`, RULE-13-05/06 |
| 10 | `POST /purchase-orders/{id}/cancel` | `CancelPurchaseOrder` (chưa giao) — `01#13`, RULE-13-08 |
| 11 | `POST /purchase-orders/{id}/cancel-remaining` | `CancelRemainingPurchaseOrder` (→ CLOSED) — `01#13`, RULE-13-07 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreatePurchaseRequest | InventoryStaff (Store scope, 4-role như M12) | Từ Store/Warehouse có nhu cầu | RULE-13-01 (lines + giá dự kiến + NCC khuyến nghị) | POST | `/stores/{id}/purchase-requests` | Bearer | Staff scope | `[*] → DRAFT` | Non-idempotent | CONFIRMED — `storeId` từ path, `requestNumber` BE sinh |
| SubmitPurchaseRequest | InventoryStaff (Store scope) | `DRAFT` | FSM-12 tự guard (không RULE-ID riêng) | POST | `…/submit` | Bearer | Staff scope (không giới hạn creator) | `DRAFT → SUBMITTED` | Non-idempotent (409 nếu không phải DRAFT) | — |
| Approve/RejectPurchaseRequest | StoreManager (/OrgAdmin cho Warehouse) | `SUBMITTED` + maker-checker | RULE-13-02 (`MAKER_CHECKER_VIOLATION` CONFIRMED, code 400; reject kèm lý do bắt buộc) | POST | `…/approve`, `…/reject` | Bearer | Manager scope | `SUBMITTED → APPROVED/REJECTED` | Non-idempotent (409 nếu không phải SUBMITTED) | — |
| CancelPurchaseRequest | InventoryStaff (Store scope) | `DRAFT`/`SUBMITTED` (chưa duyệt) | RULE-13-03 (FSM-12 tự guard) | POST | `…/cancel` | Bearer | Staff scope | `→ CANCELLED` | Non-idempotent (409 nếu APPROVED/REJECTED/CANCELLED) | — |
| CreatePurchaseOrder | InventoryStaff (Store scope) | PR `APPROVED` + Supplier `ACTIVE` | RULE-13-04 (PO từ PR duyệt; Supplier master thật — CONFIRMED, Q8 resolved) | POST | `/purchase-orders` | Bearer | Trong scope PR | `[*] → ISSUED` + `poNumber` BE sinh (A2) | Non-idempotent | `{purchaseRequestId, supplierId}` — lines COPY từ PR, không nhận lại từ client |
| TrackPurchaseOrder | Staff (Store scope) | — | — | GET | `/purchase-orders/{id}` | Bearer | Trong scope | none | Idempotent | Kèm lines + supplier name + product SKU |
| ManageSupplier | Organization Admin | — | RULE-13-04 (UNIQUE org+code, ACTIVE/INACTIVE) | POST/PATCH/GET | `/suppliers[...]` | Bearer | Org scope | — | Create non-idempotent, update idempotent | CONFIRMED — bảng `suppliers` thật (Q8 resolved) |
| InspectGoods | InventoryStaff | Hàng đã giao | RULE-13-05 (kiểm trước nhận; fail → từ chối + biên bản trả NCC) | POST | `…/inspect` | Bearer | Staff điểm nhận | (ghi biên bản, A3) | Idempotent (ghi đè biên bản) | `{acceptedLines?, rejectedLines? + returnNote?}` PROPOSED |
| ReceiveGoods | InventoryStaff | Đã inspect đạt | RULE-13-05/06/08 (+ tăng tồn + lô/HSD; CLOSED/CANCELLED cấm nhận) | POST | `…/receive` | Bearer | Staff điểm nhận | `ISSUED → PARTIALLY_RECEIVED/RECEIVED` (+ `GoodsReceived`) | Idempotent theo đợt nhận (A4) | `{receiptLines: [{productId, receivedQty, batchNumber?, expiryDate?}]}` |
| CancelPurchaseOrder | InventoryStaff / StoreManager | Chưa giao hàng | RULE-13-08 | POST | `…/cancel` | Bearer | Trong scope | `ISSUED → CANCELLED` | Idempotent | Đã nhận 1 phần → dùng cancel-remaining |
| CancelRemainingPurchaseOrder | StoreManager / InventoryStaff | `PARTIALLY_RECEIVED` + NCC hết khả năng | RULE-13-07 | POST | `…/cancel-remaining` | Bearer | Trong scope | `PARTIALLY_RECEIVED → CLOSED` | Idempotent | Hủy nghĩa vụ phần còn thiếu |

**ASSUMPTIONS dùng chung:** A2 `poNumber`/`requestNumber` BE sinh dạng
`"PR|PO-" + yyyyMMdd + "-" + 8 hex từ UUID` (ERD có cột, thiếu format — không có sequence
table) · A3 biên bản inspect lưu trên goods_receipt (người kiểm + thời điểm CONFIRMED qua
`inspected_by`; hàng fail lập biên bản trả NCC — shape TBD Q10, vẫn ngoài scope task này) ·
A4 mỗi đợt `receive` 1 goods_receipt; nhận lặp cùng đợt trả lại bản ghi cũ (ngoài scope task
này) · A5 `CreatePurchaseOrder` không nhận `lines` từ client — copy 1:1 từ PR đã `APPROVED`
(đơn giản, chống giả mạo). (A1 — cũ, giả định `supplierName` text — đã loại bỏ, xem "Đóng
băng phạm vi".)

---

## C. Detailed endpoint contract

### C1. Purchase requests — ĐÃ triển khai

- **`POST /stores/{id}/purchase-requests`** — Request `{lines: [{productId (req),
  requestedQuantity (req, >0), estimatedUnitPrice (req, chuỗi thập phân 2 chữ số),
  recommendedSupplierName?}] (req, min 1)}`. `storeId` từ path, không trong body.
  → `DRAFT`. Response `201 {requestId, requestNumber, status: "DRAFT", lines: [...]}`.
  Guard RULE-13-01: mỗi `productId` phải cùng Organization với Store → `400` nếu khác.
  Status: `201` · `400` · `401` · `403` · `404`.
- **`…/submit`** — `DRAFT → SUBMITTED`, `409` nếu không phải `DRAFT`. **`…/approve`** —
  maker-checker (`MAKER_CHECKER_VIOLATION`, `400 BUSINESS_RULE_VIOLATION` CONFIRMED —
  không phải `409`), `409` nếu không phải `SUBMITTED`. **`…/reject`** — `{reason (req)}`
  → `REJECTED` (lý do bắt buộc, RULE-13-02). **`…/cancel`** — chỉ `DRAFT`/`SUBMITTED`
  (RULE-13-03), `409` nếu `APPROVED`/`REJECTED`/`CANCELLED`.
- **Status chung:** `200`/`201` · `400` · `401` · `403` · `404` · `409` sai trạng thái.

### C2. Purchase orders — CreatePurchaseOrder/TrackPurchaseOrder ĐÃ triển khai; phần còn lại (inspect/receive/cancel/cancel-remaining) NGOÀI PHẠM VI

- **`POST /purchase-orders`** — Request `{purchaseRequestId (req), supplierId (req —
  UUID thật, KHÔNG phải text, A1 cũ đã loại bỏ)}`. Guards: PR phải `APPROVED` (chưa duyệt
  → `400 RULE-13-04`, không phải `409`); Supplier cùng Organization + `ACTIVE`
  (→ `400 RULE-13-04` nếu sai). `lines` COPY 1:1 từ PR, không nhận từ client (A5).
  Response `201 {orderId, poNumber, purchaseRequestId, storeId, supplierId, supplierName,
  status: "ISSUED", totalAmount, lines: [...]}`.
  Status: `201` · `400` · `401` · `403` · `404`.
- **`GET /purchase-orders/{id}`** — Response order + lines (kèm `sku`) + supplier name.
  Status: `200` · `401` · `403` · `404`.
- **`…/inspect`, `…/receive`, `…/cancel`, `…/cancel-remaining`** — **NGOÀI PHẠM VI task
  này** (RULE-13-05→08, cần tích hợp Inventory). Giữ nguyên thiết kế PROPOSED bên dưới cho
  task sau, chưa có code:
  - **`…/inspect`** — Request `{acceptedLines?: [{productId, quantity}],
    rejectedLines?: [{productId, quantity, reason}], returnNote?}` (A3, PROPOSED).
    Chưa giao mà inspect → `409`. Response `200 {receiptId?, inspected: true}`.
  - **`…/receive`** — Request `{receiptLines: [{productId (req), receivedQuantity (req),
    batchNumber?, expiryDate?}] (req)}`. Guards: đã inspect (RULE-13-05) + chưa kết thúc
    (RULE-13-08). `ISSUED → PARTIALLY_RECEIVED`/`RECEIVED`.
  - **`…/cancel`** — Chưa nhận đợt nào → `CANCELLED`. **`…/cancel-remaining`** — Từ
    `PARTIALLY_RECEIVED` → `CLOSED` (RULE-13-07). Sau `CLOSED`/`CANCELLED`: mọi action
    → `409` (RULE-13-08).
- **Status chung actions (khi triển khai):** `200` · `400` · `401` · `403` · `404` · `409`.

### C3. Suppliers — ĐÃ triển khai (ManageSupplier, RULE-13-04, Organization Admin)

- **`POST /suppliers`** — Request `{code (req), name (req), contactPhone?, contactEmail?,
  address?}`. `organizationId` suy từ `actor.getOrganizationId()` (không nhận qua body/path,
  cùng lý do `POST /products` — SUPER_ADMIN không có Organization nên không gọi được).
  Guard: UNIQUE `(organizationId, code)` → `400 RULE-13-04` nếu trùng. Response
  `201 {supplierId, organizationId, code, name, contactPhone, contactEmail, address,
  status: "ACTIVE"}`. Status: `201` · `400` · `401` · `403`.
- **`PATCH /suppliers/{id}`** — Partial update (null = giữ nguyên): `{name?, contactPhone?,
  contactEmail?, address?, status?}` — `status` dùng để đổi `ACTIVE`/`INACTIVE`. Status:
  `200` · `401` · `403` · `404` · `409` (stale version).
- **`GET /suppliers/{id}`**, **`GET /suppliers`** — Đọc theo Organization của actor. Status:
  `200` · `401` · `403` · `404`.

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
| Q7 | Ai được tạo PO (staff tạo hay chỉ manager)? | **DECIDED (BE):** InventoryStaff qua `assertCanOperateStoreInventory` (Store scope, 4-role như M12 — SUPER_ADMIN/ORG_ADMIN/STORE_MANAGER/INVENTORY_STAFF) | `01#13` |
| Q8 | Master NCC (ACTIVE/inactive) lưu ở đâu — bảng mới? | **DECIDED (BE):** bảng `suppliers` thật (V1 + audit columns V16), ManageSupplier trong scope | ERD |
| Q9 | `purchase_requests` + lines lưu ở đâu (ERD thiếu spec)? | **DECIDED:** đã có schema đầy đủ (V1 + V16 audit columns), xem docs/06-erd.md §3.5 | ERD |
| Q10 | Shape biên bản inspect/reject (A3)? | TBD (BE) | RULE-13-05 |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/procurement-v1.yaml`](./openapi/procurement-v1.yaml).
