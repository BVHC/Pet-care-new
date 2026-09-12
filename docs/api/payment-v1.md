# Payment API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Payment Management (Module 16): thanh toán Online qua cổng +
> tiền mặt tại quầy, webhook idempotent, hủy phiên chờ. Mô hình: 1 Invoice nhận
> nhiều Payment; mỗi Payment cấn trừ đúng 1 Invoice (RULE-16-01).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#16`), `docs/02-business-rules.md`
> (RULE-16-01→07), `docs/03-state-machines.md` (FSM-7), `docs/04-glossary.md` (`16`),
> `docs/05-domain-model.md` (`4.16`), `docs/06-erd.md` (`payments`).
> **Contract máy đọc:** [`./openapi/payment-v1.yaml`](./openapi/payment-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `VerifyPayment` (chuyển hướng/xác thực sang cổng) là bước hệ thống nội bộ trong
  luồng Online — không endpoint riêng; thể hiện qua `gatewayUrl?` trả về khi tạo payment.
- `ReceivePaymentCallback` PHẢI có endpoint vì cổng ngoài gọi vào
  (`POST /payments/callbacks`, verify HMAC + idempotent theo `transaction_id`/key).
- `ReconcilePayment` (đối soát định kỳ) là backoffice batch — defer, không endpoint.
- Trạng thái hoàn (`PARTIALLY_REFUNDED`/`REFUNDED`) là effects của M17 — không endpoint.

---

## A. Confirmed Payment API (5 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /payments` | `MakePayment` (Online → PENDING) — `01#16`, RULE-16-01/02 |
| 2 | `POST /payments/cash` | `RecordCashPayment` (→ SUCCESS tức thì, cùng transaction POS) — `01#16`, RULE-16-01/02/04 |
| 3 | `POST /payments/callbacks` | `ReceivePaymentCallback` (verify HMAC + idempotent) — `01#16`, RULE-16-03/04 |
| 4 | `POST /payments/{id}/cancel` | `CancelPayment` (PENDING/PROCESSING Online) — `01#16`, RULE-16-05 |
| 5 | `GET /payments/{id}` | (tra cứu 1 payment — derived read cho đối soát/thu ngân, A1) |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| MakePayment | Customer | Invoice `ISSUED` + còn dư (`Σ SUCCESS < Total`) | RULE-16-01/02 (Online PENDING→PROCESSING) | POST | `/payments` | Bearer | Owner invoice | `[*] → PENDING` | `idempotencyKey?` PROPOSED (cột UK ERD; không key → không đảm bảo dedup, A2) | `{invoiceId, amount, provider?, idempotencyKey?}`; `provider` VNPay/MoMo/ZaloPay/thẻ PROPOSED (TBD Q6) |
| RecordCashPayment | Receptionist | Invoice `ISSUED` + phiên thu ngân | RULE-16-01/02/04 (→ SUCCESS ngay trong `@Transactional` + chain Invoice PAID/Order/gói) | POST | `/payments/cash` | Bearer | Receptionist | `[*] → SUCCESS` + `PaymentSucceeded` | `transactionCode` unique (ERD UK) chống thu trùng | `{invoiceId, amount}` — tiền thừa/thối TBD Q7 |
| ReceivePaymentCallback | System (cổng gọi) | Chữ ký HMAC + khớp mã/tiền | RULE-16-03/04 (verify + idempotent đúng 1 lần) | POST | `/payments/callbacks` | Gateway signature (A3) | — (system) | `PROCESSING → SUCCESS/FAILED` + `PaymentSucceeded` khi thành công | Theo `transaction_id` + key (nhận lặp xử lý 1 lần) | `{provider, transactionId, status, amount, signature}` |
| CancelPayment | Customer / System | `PENDING`/`PROCESSING` + kênh Online | RULE-16-05 (CASH không qua đây) | POST | `/payments/{id}/cancel` | Bearer | Owner / system | `→ CANCELLED` | Idempotent | CASH gọi → 409 PROPOSED |
| (read) | Staff | — | — | GET | `/payments/{id}` | Bearer | Scope | none | Idempotent | Derived read (A1) |

**ASSUMPTIONS dùng chung:** A1 read 1 payment phục vụ thu ngân/đối soát (không op đọc
riêng trong docs — derived tối thiểu) · A2 `idempotencyKey` optional: có key → dedup
theo UK; không key → mỗi call 1 payment (đúng cột nullable ERD) · A3 callback auth
bằng chữ ký cổng (không Bearer user) + giới hạn IP/allowlists TBD Q8.

---

## C. Detailed endpoint contract

### C1. Create (proposed)

- **`POST /payments`** — Request `{invoiceId (req), amount (req, >0),
  provider? (`VNPAY`/`MOMO`/`ZALOPAY`/`CARD` PROPOSED, TBD Q6), idempotencyKey? (A2)}`.
  Guards CONFIRMED: invoice `ISSUED` (chưa → `409`); `Σ SUCCESS + amount ≤ Total`
  (vượt → `409` RULE-16-01). Response `201 {paymentId, transactionCode,
  status: "PENDING", gatewayUrl?}` (`gatewayUrl` là bước VerifyPayment thể hiện ra —
  PROPOSED). Status: `201` · `400` · `401` · `403` (không phải owner) · `404` · `409`.
- **`POST /payments/cash`** — Request `{invoiceId (req), amount (req)}`.
  Chỉ receptionist, trong phiên POS. Effect CONFIRMED (RULE-16-02/04): `SUCCESS` +
  chain tất toán (invoice đủ 100% → `PAID`, order/gói tiếp). Response
  `201 {paymentId, status: "SUCCESS"}`. Tiền thừa/thối TBD Q7.
  Status: `201` · `400` · `401` · `403` · `404` · `409` (invoice không ISSUED/vượt total).

### C2. Callback (proposed)

- **`POST /payments/callbacks`** — Request `{provider (req), transactionId (req),
  status (req: `SUCCESS`/`FAILED`), amount (req), signature (req)}`.
  Guards CONFIRMED (RULE-16-03): verify HMAC → sai chữ ký từ chối (không lộ chi tiết,
  `401` PROPOSED); khớp mã + tiền (lệch → `409` PROPOSED `AMOUNT_MISMATCH`);
  idempotent theo transaction/key (nhận lặp → trả kết quả cũ, không ghi trùng).
  `SUCCESS` → payment `SUCCESS` + `PaymentSucceeded` (chain); `FAILED` → `FAILED`.
  Response `200 {received: true}` (shape tối thiểu, không lộ nội bộ).
  Status: `200` · `400` · `401` (chữ ký) · `404` (không thấy payment) · `409` (lệch tiền).

### C3. Cancel & read (proposed)

- **`POST /payments/{id}/cancel`** — Guards: `PENDING`/`PROCESSING` + kênh Online
  (RULE-16-05 CONFIRMED). CASH → `409` (kênh này không có trạng thái chờ).
  Response `200 {paymentId, status: "CANCELLED"}`.
- **`GET /payments/{id}`** — Response payment + invoice link. Scope: owner/staff (A1).
- **Status:** `200` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Webhook: HMAC bắt buộc + kiểm mã/tiền + idempotent đúng 1 lần (RULE-16-03) —
   test giả mạo/lặp là bắt buộc.
2. Không trừ tiền lặp khi mạng chập chờn: key UK + transaction UK (ERD) trong cùng
   transaction ghi payment.
3. Tổng SUCCESS không vượt Total (RULE-16-01) — check trước mọi settle.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | Thanh toán từng phần nhiều Payment/1 invoice có cho không (RULE-16-01 cho phép tổng lũy kế)? | TBD (PO) — như order Q5 | RULE-16-01 |
| Q4 | `transactionCode` format + ai sinh? | TBD (BE) | ERD UK |
| Q5 | `gatewayUrl` trả ở create hay FE tự dựng? (A: trả về — PROPOSED) | TBD (BE/FE) | VerifyPayment |
| Q6 | Danh sách cổng (`provider` values) chốt? | TBD (PO) | RULE-16-02 ví dụ |
| Q7 | Tiền mặt thừa/thiếu (thối lại) ghi ở đâu? | TBD (PO) | docs không nêu |
| Q8 | Callback allowlist IP + xoay secret HMAC? | TBD (BE/Ops) | RULE-16-03 |
| Q9 | Timeout cổng bao lâu thì được cancel (ai bấm)? | TBD (PO) | RULE-16-05 (timeout) |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/payment-v1.yaml`](./openapi/payment-v1.yaml).
