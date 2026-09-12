# Refund API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Refund Management (Module 17): yêu cầu hoàn theo Payment gốc,
> Maker-Checker, chi tiền mặt/cổng, retry 3 lần, xử lý thủ công.
> Mỗi Refund gắn đúng 1 Payment gốc (quyết định nghiệp vụ CONFIRMED).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#17`), `docs/02-business-rules.md`
> (RULE-17-01→10 + window/retry invariants), `docs/03-state-machines.md` (FSM-8),
> `docs/04-glossary.md` (`17`), `docs/05-domain-model.md` (`4.17`),
> `docs/06-erd.md` (`refunds`/`refund_execution_logs`).
> **Contract máy đọc:** [`./openapi/refund-v1.yaml`](./openapi/refund-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `FailRefund` là system ghi nhận lỗi cổng (không endpoint riêng — thất bại khi process
  tự chuyển `FAILED`).
- `ReconcileRefund` (đối soát) là backoffice batch — defer, không endpoint.
- `SendRefundNotification` là effect M23 khi `COMPLETED`/quyết định — không endpoint.
- `UNDER_REVIEW` đã bỏ CONFIRMED (thẳng `REQUESTED → APPROVED/REJECTED`); `FAILED`
  là non-terminal (retry ×3 hoặc manual).

---

## A. Confirmed Refund API (9 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /refunds` | `RequestRefund` (customer) / `CreateRefundRequest` (receptionist) — `01#17`, RULE-17-01/02/03 |
| 2 | `GET /refunds` | (tra cứu của mình / worklist — derived read) |
| 3 | `GET /refunds/{id}` | (detail) |
| 4 | `POST /refunds/{id}/approve` | `ApproveRefund` — `01#17`, RULE-17-04 |
| 5 | `POST /refunds/{id}/reject` | `RejectRefund` — `01#17`, RULE-17-04 |
| 6 | `POST /refunds/{id}/process` | `ProcessRefund` (chi tiền mặt / gọi cổng) — `01#17`, RULE-17-05 |
| 7 | `POST /refunds/{id}/complete` | `CompleteRefund` (+ đồng bộ đa-aggregate) — `01#17`, RULE-17-02/05/09 |
| 8 | `POST /refunds/{id}/retry` | `RetryRefund` (≤3) — `01#17`, RULE-17-07 |
| 9 | `POST /refunds/{id}/resolve-manual` | `ResolveRefundManually` (chứng từ ngân hàng) — `01#17`, RULE-17-08 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| RequestRefund / CreateRefundRequest | Customer / Receptionist | Payment `SUCCESS` + trong 30 ngày + còn hạn mức | RULE-17-01/02/03 (`REFUND_REQUEST_WINDOW_EXPIRED` CONFIRMED; ngoại lệ OrgAdmin — A1) | POST | `/refunds` | Bearer | Owner payment / receptionist | `[*] → REQUESTED` + `RefundRequested` | Non-idempotent (mỗi call 1 request; trùng lặp gộp ở duyệt) | `{paymentId, amount, reason}` — amount ≤ remaining CONFIRMED |
| (read) | Customer / Staff | — | — | GET | `/refunds…` | Bearer | Owner / scope | none | Idempotent | Derived reads (A2) |
| ApproveRefund | StoreManager | `REQUESTED` + maker-checker | RULE-17-04 (`created_by != approved_by` → `MAKER_CHECKER_VIOLATION` CONFIRMED) | POST | `…/approve` | Bearer | StoreManager | `REQUESTED → APPROVED` | Idempotent | — |
| RejectRefund | StoreManager | `REQUESTED` | RULE-17-04 (kèm lý do) | POST | `…/reject` | Bearer | StoreManager | `REQUESTED → REJECTED` (terminal) | Idempotent | `{reason?}` |
| ProcessRefund | Receptionist/Manager (cash) / FinanceStaff (gateway) | `APPROVED` | RULE-17-05 (phân kênh + nhân sự) | POST | `…/process` | Bearer | Theo kênh (server check) | `APPROVED → PROCESSING` (+ gọi cổng / chi quầy) | Idempotent (đã PROCESSING trả lại) | Sai kênh/role → 403 PROPOSED |
| CompleteRefund | Staff/System | Tiền đã đến tay / callback cổng OK | RULE-17-02/05/09 (đồng bộ invoice+payment+order) | POST | `…/complete` | Bearer | Theo kênh | `PROCESSING → COMPLETED` + `RefundCompleted` | Idempotent | `{note?}` (cash: ký phiếu chi — effect) |
| (FailRefund) | System | Lỗi cổng | RULE-17-06/07 | — | (trong process/retry) | — | — | `PROCESSING → FAILED` + `RefundFailed` | — | Không endpoint |
| RetryRefund | FinanceStaff (/System) | `FAILED` + `retry_count < 3` | RULE-17-07 (3 lần → khóa auto + `requires_manual_resolution`) | POST | `…/retry` | Bearer | FinanceStaff | `FAILED → PROCESSING` (đếm +1) | Idempotent theo lần thử | Hết 3 vẫn fail → `FAILED` khóa retry |
| ResolveRefundManually | FinanceStaff / StoreManager | `FAILED` (khóa auto) | RULE-17-08 (mã ngân hàng/chứng từ bắt buộc) | POST | `…/resolve-manual` | Bearer | Finance/Manager | `FAILED → COMPLETED` | Idempotent | `{bankReference (req), proofUrl?, note?}` |

**ASSUMPTIONS dùng chung:** A1 ngoại lệ 30 ngày của OrgAdmin (RULE-17-03 CONFIRMED tồn
tại) thể hiện qua `approve` kèm `{exceptionReason?}` — chỉ `ORGANIZATION_ADMIN`
(TBD Q8) · A2 reads cho customer (của mình) + staff worklist (scope) — derived tối
thiểu · A3 `refundNumber` BE sinh (ERD UK, thiếu format — như các number).

---

## C. Detailed endpoint contract

### C1. Request (proposed)

- **`POST /refunds`** — Request `{paymentId (req), amount (req, >0), reason (req)}`.
  Guards CONFIRMED: payment `SUCCESS` (chưa → `409`); trong 30 ngày
  (`REFUND_REQUEST_WINDOW_EXPIRED` CONFIRMED RULE-17-03, trừ ngoại lệ A1);
  `amount ≤ remaining` = `Total − Σ completed` (RULE-17-02 CONFIRMED, sai → `400`).
  Điều kiện hàng hóa (nguyên tem/hỏng do NCC) và công thức gói (M20) do người tạo
  tự đảm bảo ở v1 — validate sâu TBD Q9. Response `201 {refundId, refundNumber,
  paymentId, amount, status: "REQUESTED"}`.
  Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`GET /refunds`** — Query PROPOSED: `page`, `pageSize`, `status?`, `mine?`.
  **`GET /refunds/{id}`** — Owner/staff scope. Status chuẩn.

### C2. Decide (proposed)

- **`…/approve`** — Từ `REQUESTED`. Maker-checker CONFIRMED. Kèm
  `{exceptionReason?}` cho case quá 30 ngày (A1 — chỉ OrgAdmin, sai role → 403).
  Response `200 {status: "APPROVED"}`.
- **`…/reject`** — `{reason?}` → `REJECTED` terminal. Status: `200` · `401` ·
  `403` · `404` · `409`.

### C3. Execute (proposed)

- **`…/process`** — Từ `APPROVED`. Server rẽ kênh theo payment gốc: CASH →
  receptionist/manager chi quầy; ONLINE → finance gọi API cổng (RULE-17-05).
  Sai role/kênh → `403` PROPOSED. Thất bại cổng → ở yên `PROCESSING` + `retryCount+1`?
  Không — thất bại attempt ghi `FAILED` + log execution (`refund_execution_logs`),
  retry mở lại (luồng Q10 chốt: attempt-fail → `FAILED` ngay, retry → `PROCESSING`).
- **`…/complete`** — Từ `PROCESSING`. Guards: tiền đã chi/callback OK (xác minh
  ngoài hệ thống với cash — tin staff + ký phiếu). Effects CONFIRMED (RULE-17-09):
  invoice `+= total_refunded_amount` (giữ `PAID`) + payment `PARTIALLY_REFUNDED`/
  `REFUNDED` + order cộng dồn/`REFUNDED` (100%). Response `200 {status: "COMPLETED"}`.
- **`…/retry`** — Từ `FAILED`, `retryCount < 3`. Mỗi retry +1 đếm; sau lần 3 vẫn fail
  → `FAILED` + `requiresManualResolution: true` + khóa retry (RULE-17-07 CONFIRMED).
  Response `200 {status: "PROCESSING", retryCount}` hoặc `{status: "FAILED",
  requiresManualResolution: true}`.
- **`…/resolve-manual`** — Từ `FAILED` (khóa auto). `{bankReference (req CONFIRMED),
  proofUrl?, note?}`. → `COMPLETED` (đồng bộ như complete).
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Hạn mức còn lại check trong transaction tạo request (không vượt Total).
2. Maker-checker + 30-day window + retry-cap là 3 guard test bắt buộc.
3. Đồng bộ đa-aggregate khi COMPLETED cùng transaction (RULE-17-09) — lệch sổ là lỗi hệ thống.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `refundNumber` format? | TBD (BE) | ERD UK |
| Q4 | Trùng request (customer bấm 2 lần) có gộp/dedup không? | TBD (PO) — v1 không dedup | docs không nêu |
| Q5 | Hoàn một phần nhiều lần: mỗi lần 1 request hay 1 request nhiều đợt? (A: mỗi lần 1 request) | TBD (PO) | RULE-17-02 (lũy kế theo payment) |
| Q6 | Ai chịu phí cổng hoàn (trừ vào amount hay công ty chịu)? | TBD (PO) | docs không nêu |
| Q7 | `requires_manual_resolution` đọc ở đâu (field trong detail)? (A: có — đã đưa vào response) | DECIDED (hiển nhiên từ rule) | RULE-17-07 |
| Q8 | Ngoại lệ 30 ngày: OrgAdmin duyệt ở approve (A1) hay flow riêng? | TBD (PO) | RULE-17-03 |
| Q9 | Validate điều kiện hàng hóa/gói khi tạo request ở mức nào? | TBD (PO) | window invariant (3)(4) |
| Q10 | Attempt-fail → FAILED ngay hay ở PROCESSING với error? (A: FAILED + log, retry mở lại) | TBD (BE) | RULE-17-06/07 |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/refund-v1.yaml`](./openapi/refund-v1.yaml).
