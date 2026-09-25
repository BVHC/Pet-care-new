# Order API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Order Management (Module 14, v1 In-Store Fulfillment):
> đơn POS tức thời + đơn Online nhiều bước (D-03), giữ kho 15 phút, hủy/đổi trả.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#14`), `docs/02-business-rules.md`
> (RULE-14-01→09 + reservation invariant), `docs/03-state-machines.md` (FSM-5),
> `docs/04-glossary.md` (`14`), `docs/05-domain-model.md` (`4.14`),
> `docs/06-erd.md` (`orders`/`order_items`/`fulfillment_stage_logs`).
> **Contract máy đọc:** [`./openapi/order-v1.yaml`](./openapi/order-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ProcessOrderTimeout` (hủy quá 15m) là job nền — không endpoint.
- `SendOrderNotification` là effect M23 tại 6 mốc (RULE-14-09) — không endpoint.
- Hoàn tiền đi qua [`refund-v1.md`](./refund-v1.md); thanh toán qua
  [`payment-v1.md`](./payment-v1.md). Ở đây chỉ chốt transition + điều kiện kho.
- Voucher/loyalty trừ trên đơn: validate thuộc M18/M19 — `voucherCode?` nhận vào
  (PROPOSED) và tính `discountAmount` theo đó, chi tiết xem 2 contract kia.

**Đã triển khai (task "Order: entity + Cart + migration"):** chỉ #1 `CreateOrder`, #2
`CheckoutOrder`, #3/#4 `ViewOrder`, #5 `CancelOrder` (bảng §A bên dưới). #6-#10
(`CancelOrderWithRefund`/`ConfirmOrder`/`ProcessOrder`/`PrepareProductOrder`/`CompleteStoreOrder`)
**NGOÀI PHẠM VI — để task sau**, phụ thuộc Payment (M16)/Refund (M17) chưa tồn tại; chữ ký request/
response giữ nguyên PROPOSED làm tài liệu tham khảo, chưa có controller/service thật phía sau.

---

## A. Confirmed Order API (10 endpoints + 1 domain event)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /orders` | `CreateOrder` (Online → PENDING_PAYMENT + giữ kho; POS → PAID) — `01#14`, RULE-14-01/02/04 |
| 2 | `POST /orders/{id}/checkout` | `CheckoutOrder` (chốt giỏ + (gia hạn) giữ 15m) — `01#14`, RULE-14-04 |
| 3 | `GET /orders` | `ViewOrder` (list của mình) — `01#14`, RULE-14-01 |
| 4 | `GET /orders/{id}` | `ViewOrder` (detail) |
| 5 | `POST /orders/{id}/cancel` | `CancelOrder` (chưa trả → CANCELLED + nhả kho) — `01#14`, RULE-14-04/07/08 |
| 6 | `POST /orders/{id}/cancel-with-refund` | **NGOÀI PHẠM VI** — `CancelOrderWithRefund` (→ CANCELLED + hoàn 100%) — `01#14`, RULE-14-07 |
| 7 | `POST /orders/{id}/confirm` | **NGOÀI PHẠM VI** — `ConfirmOrder` (PAID → CONFIRMED) — `01#14`, RULE-14-03/05 |
| 8 | `POST /orders/{id}/process` | **NGOÀI PHẠM VI** — `ProcessOrder` (→ PROCESSING) — `01#14`, RULE-14-05 |
| 9 | `POST /orders/{id}/prepare` | **NGOÀI PHẠM VI** — `PrepareProductOrder` (→ READY) — `01#14`, RULE-14-05 |
| 10 | `POST /orders/{id}/complete` | **NGOÀI PHẠM VI** — `CompleteStoreOrder` (→ DELIVERED) — `01#14`, RULE-14-03/06 |
| 11 | (event) | Đổi trả 100% sau giao → `REFUNDED` (effect của M17, không endpoint) |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreateOrder | Customer / Receptionist | Items ACTIVE + đủ available | RULE-14-01/02; Online: reserve 15m (optimistic lock); POS: trừ physical cùng transaction | POST | `/orders` | Bearer | Owner intent / receptionist | Online: `[*] → PENDING_PAYMENT` + reserve; POS: `[*] → PAID` + trừ physical | Non-idempotent (mỗi call 1 đơn + giữ kho) | `channel` PROPOSED (`POS_RETAIL`/`ONLINE_APP` CONFIRMED values); `orderNumber` BE sinh (A1) |
| CheckoutOrder | Customer | `PENDING_PAYMENT` | RULE-14-04 (chốt + giữ 15m) | POST | `…/checkout` | Bearer | Owner | (ở yên PENDING + refresh TTL — A2) | Idempotent (chốt lại cùng giỏ) | Sửa items sau chốt? TBD Q7 |
| ViewOrder | Customer | Đơn mình | RULE-14-01 | GET | `/orders…` | Bearer | Owner (staff xem theo M25/report sau) | none | Idempotent | — |
| CancelOrder | Customer / Receptionist | `PENDING_PAYMENT` | RULE-14-04/07/08 (nhả reserve + CANCELLED bất biến) | POST | `…/cancel` | Bearer | Owner / receptionist | `PENDING_PAYMENT → CANCELLED` | Idempotent | Đã trả tiền → dùng cancel-with-refund |
| CancelOrderWithRefund **(NGOÀI PHẠM VI)** | Customer / Manager / Receptionist | `PAID`/`CONFIRMED`/`PROCESSING`/`READY` | RULE-14-07 (hoàn 100% + hoàn kho → CANCELLED duy nhất) | POST | `…/cancel-with-refund` | Bearer | Theo actor + policy (A3) | `→ CANCELLED` + sinh refund request M17 | Idempotent (hoàn lại request cũ) | `{reason}` PROPOSED bắt buộc mềm — chưa implement, phụ thuộc Refund M17 |
| ConfirmOrder **(NGOÀI PHẠM VI)** | Receptionist (/System) | `PAID` | RULE-14-03/05 | POST | `…/confirm` | Bearer | Receptionist | `PAID → CONFIRMED` | Idempotent | System auto-confirm khi nào TBD Q8 — chưa implement, phụ thuộc Payment M16 |
| ProcessOrder **(NGOÀI PHẠM VI)** | Receptionist / InventoryStaff | `CONFIRMED` | RULE-14-05 | POST | `…/process` | Bearer | Staff Store | `CONFIRMED → PROCESSING` | Idempotent | Physical trừ chính thức tại đây (invariant: reserve → physical khi vào đóng gói) — chưa implement |
| PrepareProductOrder **(NGOÀI PHẠM VI)** | InventoryStaff | `PROCESSING` | RULE-14-05 (+ RULE-12-04 xuất kho) | POST | `…/prepare` | Bearer | Staff Store | `PROCESSING → READY` | Idempotent | — chưa implement |
| CompleteStoreOrder **(NGOÀI PHẠM VI)** | Receptionist | POS: `PAID`; Online: `READY` + mã nhận hàng | RULE-14-03/06 (POS instant; Online cần pickup code) | POST | `…/complete` | Bearer | Receptionist | `PAID/READY → DELIVERED` | Idempotent | `pickupCode?` PROPOSED + storage TBD Q6 — chưa implement, phụ thuộc Payment M16 |

**ASSUMPTIONS dùng chung:** A1 `orderNumber` BE sinh unique (ERD có cột, thiếu format) ·
A2 checkout giữ nguyên `PENDING_PAYMENT` + refresh TTL 15m (docs không tách rõ
Create vs Checkout — ít-phát-minh-nhất) · A3 cancel-with-refund: customer tự hủy sau
trả cần policy duyệt? Docs gán cả 3 actors — v1 cho phép cả 3, TBD Q9.

---

## C. Detailed endpoint contract

### C1. Create & checkout (proposed)

- **`POST /orders`** — Request `{storeId (req), channel (req: `POS_RETAIL`/
  `ONLINE_APP` CONFIRMED), customerId? (ASSUMPTION A5 — bắt buộc khi `POS_RETAIL`, bỏ qua/reject
  khi `ONLINE_APP` vì BE tự lấy actor.userId, chống mạo danh), items: [{productId (req), quantity
  (req)}] (req, min 1), voucherCode? (PROPOSED, validate M18 — CHƯA implement, M18 chưa tồn tại)}`.
  Guards CONFIRMED (RULE-14-02): items `ACTIVE` tại Store + `available ≥ quantity` (thiếu hoặc
  inactive → **`400 BUSINESS_RULE_VIOLATION`**, không phải `409` — `BusinessRuleViolationException`
  luôn map 400 theo `docs/convention/backend/04-exception-handling`, sửa lại so với bản nháp trước).
  Online → `201 {orderId, orderNumber, status: "PENDING_PAYMENT", reservedUntil (+15m)}`;
  POS (receptionist) → `201 {…, status: "PAID"}` + trừ physical cùng transaction
  (RULE-14-04). Status: `201` · `400` · `401` · `403` · `404`.
- **`POST /orders/{id}/checkout`** — Guards: `PENDING_PAYMENT` + giỏ còn hợp lệ
  (giá/tồn refresh). Response `200 {status: "PENDING_PAYMENT", reservedUntil}` (A2).
  Status: `200` · `401` · `403` · `404` · `409` (giỏ hỏng/giá đổi — A4: giá chốt
  theo hiện hành, TBD Q7).

### C2. Views (proposed)

- **`GET /orders`** — Customer: đơn mình. Query PROPOSED: `page`, `pageSize`,
  `status?`. Response page shape chuẩn.
- **`GET /orders/{id}`** — Owner. Status: `200` · `401` · `403` · `404`.

### C3. Cancel (proposed)

- **`…/cancel`** — Từ `PENDING_PAYMENT`. Nhả reserve + `CANCELLED` (bất biến
  RULE-14-08 CONFIRMED: sau CANCELLED mọi xử lý → 409).
- **`…/cancel-with-refund`** — Request `{reason?}`. Từ `PAID`/`CONFIRMED`/
  `PROCESSING`/`READY`. Effects: hoàn kho + sinh refund request 100% (M17) →
  `CANCELLED` duy nhất (D-03 CONFIRMED). Response `200 {status: "CANCELLED",
  refundId?}`.
- **Status:** `200` · `401` · `403` · `404` · `409` sai trạng thái.

### C4. Fulfillment (proposed) — NGOÀI PHẠM VI, để task sau (phụ thuộc Payment M16/Refund M17)

- **`…/confirm`** — Từ `PAID` → `CONFIRMED`. **`…/process`** — → `PROCESSING`
  (trừ physical chính thức). **`…/prepare`** — → `READY`.
- **`…/complete`** — POS: từ `PAID` (instant handover). Online: từ `READY` +
  `{pickupCode?}` (PROPOSED, storage TBD Q6). → `DELIVERED`.
- Đổi trả sau giao: M17 `COMPLETED` → order `REFUNDED` (100%) hoặc cộng dồn
  `total_refunded_amount` (một phần, giữ `DELIVERED`) — effects, không endpoint.
- **Status chung:** `200` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Reserve/commit kho bằng optimistic lock (`version` ERD) — chống oversell món cuối.
2. `CANCELLED` bất biến (RULE-14-08) — test bắt buộc.
3. Partial return không đổi state (giữ `DELIVERED` + cộng dồn) — D-03.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `orderNumber` format? | **DECIDED (BE)** — `"ORD-" + yyyyMMdd + "-" + 8 hex từ UUID`, cùng format `PurchaseRequest`/`PurchaseOrder` (M13) | ERD có cột |
| Q4 | POS ai được tạo đơn (receptionist?) + phiên thu ngân 3 phút enforce sao? | **Một phần DECIDED (BE)** — actor = Receptionist/StoreManager/OrgAdmin/SuperAdmin (`RoleScopeGuard#assertCanOperateStoreOrder`, store-scoped). Khóa phiên thu ngân 3 phút (chờ quẹt thẻ/tiền mặt) vẫn TBD — task này POS là 1 lệnh `CreateOrder` đồng bộ trong 1 `@Transactional`, không có khái niệm "phiên" độc lập để khóa | RULE-14-04 (khóa phiên 3 phút) |
| Q5 | Thanh toán từng phần nhiều Payment cho 1 order Online? (Invoice cho phép N Payment) | TBD (PO) | RULE-16-01 (invoice 1-N payment; order không rõ) |
| Q6 | `pickupCode` sinh/lưu/tra thế nào? | TBD (BE) | RULE-14-06 (mã nhận hàng), ERD thiếu cột |
| Q7 | Sửa giỏ sau checkout + giá chốt theo thời điểm nào? | TBD (PO) | docs không nêu |
| Q8 | System auto-confirm khi nào? | TBD (PO) | `01#14` "Receptionist / System" |
| Q9 | Customer tự cancel-with-refund sau trả có cần duyệt không? | TBD (PO) | `01#14` gán 3 actors |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/order-v1.yaml`](./openapi/order-v1.yaml).
