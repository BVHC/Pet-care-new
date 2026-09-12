# Invoice API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Billing & Invoice Management (Module 15): hóa đơn thường +
> phụ phí (D-02), phát hành/hủy, bất biến tất toán (D-01).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#15`), `docs/02-business-rules.md`
> (RULE-15-01→08), `docs/03-state-machines.md` (FSM-6), `docs/04-glossary.md` (`15`),
> `docs/05-domain-model.md` (`4.15`), `docs/06-erd.md` (`invoices`/`invoice_items`).
> **Contract máy đọc:** [`./openapi/invoice-v1.yaml`](./openapi/invoice-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Tất toán (`PAID`) là effect của M16 (`PaymentSucceeded` khi đủ 100%) — không endpoint.
- Hoàn tiền là M17 — invoice giữ nguyên `PAID` + cộng dồn `total_refunded_amount`
  (D-01 CONFIRMED), không endpoint hoàn ở đây.
- `ReconcileInvoice` (đối soát định kỳ) là backoffice batch — defer, không endpoint
  (docs không định nghĩa input/output; giống CoordinateQueue).
- Thuế (`tax_amount` ERD): cách tính chưa có rule → TBD Q7; v1 nhận `taxAmount?`
  truyền vào (không tự tính).

---

## A. Confirmed Invoice API (9 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /invoices` | `CreateInvoice` (→ DRAFT) — `01#15`, RULE-15-01 |
| 2 | `GET /invoices` | `ViewInvoice` (list của mình) — `01#15`, RULE-15-01 |
| 3 | `GET /invoices/{id}` | `ViewInvoice` (detail) |
| 4 | `POST /invoices/{id}/items` | `AddServiceToInvoice` / `AddProductToInvoice` — `01#15`, RULE-15-02 |
| 5 | `POST /invoices/{id}/discount` | `ApplyDiscount` — `01#15`, RULE-15-02 |
| 6 | `POST /invoices/{id}/issue` | `IssueInvoice` (→ ISSUED) — `01#15`, RULE-15-03 |
| 7 | `POST /invoices/surcharge` | `IssueSurchargeInvoice` (độc lập, D-02) — `01#15`, RULE-15-05 |
| 8 | `POST /invoices/{id}/discard` | `DiscardInvoice` (DRAFT → CANCELLED) — `01#15`, RULE-15-04 |
| 9 | `POST /invoices/{id}/void` | `VoidInvoice` (ISSUED → VOID) — `01#15`, RULE-15-04 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreateInvoice | Receptionist / FinanceStaff | Customer + Store + refs hợp lệ | RULE-15-01 (1 customer + 1 store; items thuộc catalog mở tại Store) | POST | `/invoices` | Bearer | Staff Store | `[*] → DRAFT` + `InvoiceCreated` | Non-idempotent | `{customerId, storeId, appointmentId?, orderId?, packageId?, type?}` PROPOSED (gốc liên kết TBD Q6) |
| ViewInvoice | Customer / Staff | Của mình / trong Store | RULE-15-01 | GET | `/invoices…` | Bearer | Owner / staff | none | Idempotent | D-01: `PAID` giữ nguyên khi hoàn (đọc thấy `totalRefundedAmount`) |
| AddService/ProductToInvoice | Receptionist | `DRAFT` | RULE-15-02 (total = Σ items − discount) | POST | `…/items` | Bearer | Staff Store | none (tính lại total) | Non-idempotent (mỗi call 1 line) | Giá snapshot từ catalog hiệu dụng (A1) |
| ApplyDiscount | Receptionist | `DRAFT` | RULE-15-02 (discount ≤ total) | POST | `…/discount` | Bearer | Staff Store | none | Idempotent (ghi đè mức giảm — A2) | `{voucherCode?/discountAmount?}` — voucher validate M18 |
| IssueInvoice | FinanceStaff / Receptionist | `DRAFT` hoàn chỉnh | RULE-15-03 (chỉ `ISSUED` mới được trả) | POST | `…/issue` | Bearer | Staff Store | `DRAFT → ISSUED` | Idempotent | Hóa đơn rỗng (0 items / total 0) được issue? TBD Q8 |
| IssueSurchargeInvoice | Receptionist / FinanceStaff | Customer duyệt phát sinh (M11) | RULE-15-05/D-02 (độc lập, không sửa gốc) | POST | `/invoices/surcharge` | Bearer | Staff Store | `[*] → DRAFT(/ISSUED)` + link session | Non-idempotent | `{appointmentId?, groomingSessionId?, items[]}` (A3) |
| DiscardInvoice | Receptionist / FinanceStaff | `DRAFT` tạo sai | RULE-15-04 | POST | `…/discard` | Bearer | Staff Store | `DRAFT → CANCELLED` | Idempotent | — |
| VoidInvoice | FinanceStaff | `ISSUED` chưa trả | RULE-15-04/07 (`PAID` cấm tuyệt đối) | POST | `…/void` | Bearer | FinanceStaff (A4) | `ISSUED → VOID` | Idempotent | `PAID` gọi → 409 CONFIRMED |

**ASSUMPTIONS dùng chung:** A1 giá line snapshot từ catalog hiệu dụng tại Store lúc thêm
(docs: total tự tính, thiếu nguồn giá) · A2 discount ghi đè (1 mức/hoá đơn; cộng dồn
thuộc M18 stackable — M18 chốt) · A3 surcharge request mang session link + items;
`DRAFT` hay `ISSUED` ngay TBD (như grooming Q6) — PROPOSED `ISSUED` khi đủ items ·
A4 void chỉ FinanceStaff (docs "FinanceStaff / Receptionist" cho issue, void ghi
FinanceStaff ở invariant — lấy chặt hơn, TBD Q9).

---

## C. Detailed endpoint contract

### C1. Draft (proposed)

- **`POST /invoices`** — Request `{customerId (req), storeId (req), appointmentId?,
  orderId?, packageId? (gốc liên kết TBD Q6), type? (`SERVICE`/`PACKAGE`/`SURCHARGE`
  — PROPOSED từ `InvoiceType` ERD, default theo gốc), taxAmount? (TBD Q7)}`.
  Response `201 {invoiceId, invoiceNumber, status: "DRAFT", totalAmount: 0}`.
  (`invoiceNumber` BE sinh — như các number khác.)
  Status: `201` · `400` · `401` · `403` · `404`.
- **`POST …/items`** — Request `{itemType (req: `SERVICE`/`PRODUCT`), refId (req:
  service/product id), quantity (req, >0), price? (mặc định catalog — A1)}`.
  Guards: `DRAFT` + ref thuộc catalog mở tại Store (RULE-15-01). Tính lại total.
  Response `200 {invoiceId, totalAmount, items: [...]}`.
- **`POST …/discount`** — Request `{voucherCode?, discountAmount?}` (một trong hai;
  voucher validate M18). Guard: `discount ≤ total` (RULE-15-02 CONFIRMED, sai → `400`).
  Response `200 {invoiceId, discountAmount, totalAmount}` (ghi đè — A2).

### C2. Issue & surcharge (proposed)

- **`POST …/issue`** — Từ `DRAFT`. Response `200 {status: "ISSUED"}`.
  Hóa đơn rỗng TBD Q8. Status: `200` · `401` · `403` · `404` · `409`.
- **`POST /invoices/surcharge`** — Request `{appointmentId?, groomingSessionId?,
  customerId (req), storeId (req), items (req, min 1)}` (A3). Tạo invoice độc lập
  `type=SURCHARGE` gắn session, không đụng hóa đơn gốc (D-02 CONFIRMED).
  Response `201 {invoiceId, status, surchargeOf?}`.
- **`GET /invoices`, `GET /invoices/{id}`** — Customer: của mình; staff: trong Store.
  Response gồm `totalRefundedAmount` (D-01 hiển thị). Query PROPOSED: `page`,
  `pageSize`, `status?`, `customerId?` (staff).

### C3. Cancel paths (proposed)

- **`…/discard`** — Từ `DRAFT` → `CANCELLED` (tạo sai).
- **`…/void`** — Từ `ISSUED` → `VOID` (A4: FinanceStaff). `PAID` → `409` CONFIRMED
  (RULE-15-04/07); muốn hoàn → M17.
- **Status chung:** `200` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Bất biến tất toán (D-01) + cấm void PAID — test bắt buộc.
2. Chỉ `ISSUED` nhận thanh toán (RULE-15-03) — M16 check state trước khi tạo payment.
3. Surcharge không bao giờ trỏ về invoice gốc (link session, không parent invoice).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `invoiceNumber` format? | TBD (BE) | ERD có cột |
| Q4 | Hóa đơn gói (`PACKAGE`) tạo khi nào — mua gói (M20) hay dùng lượt? | TBD (PO) | `InvoiceType` ERD có, ops không nêu flow |
| Q5 | Nhiều appointment/order trên 1 hóa đơn được không? | TBD (PO) | ERD cho 2 FK nullable |
| Q6 | Gốc liên kết bắt buộc loại nào (appointment xor order xor package)? | TBD (PO) | ERD all-nullable |
| Q7 | Thuế tính thế nào (`taxAmount` ai tính)? | TBD (PO) | ERD có cột, thiếu rule |
| Q8 | Hóa đơn rỗng có được issue? | TBD (PO) | docs không nêu |
| Q9 | Void có cần receptionist cũng được (A4 lấy FinanceStaff)? | TBD (PO) | `01#15` vs invariant |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/invoice-v1.yaml`](./openapi/invoice-v1.yaml).
