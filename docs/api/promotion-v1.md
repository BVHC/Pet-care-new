# Promotion API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Promotion & Voucher Management (Module 18): chiến dịch Org,
> voucher, validate/áp dụng realtime, hoàn lượt khi hủy. Stateless rule engine —
> không FSM riêng (CONFIRMED).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#18`), `docs/02-business-rules.md`
> (RULE-18-01→08), `docs/04-glossary.md` (`18`), `docs/05-domain-model.md` (`4.18`),
> `docs/06-erd.md` (`promotion_campaigns`/`vouchers`/`voucher_usages`).
> **Contract máy đọc:** [`./openapi/promotion-v1.yaml`](./openapi/promotion-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `UseVoucher`/`TrackVoucherUsage` là effects bên trong apply-discount của M14/M15
  (ghi usage + tăng `used_count`) — không endpoint "use" riêng; chỉ có `validate`
  read + CRUD quản trị.
- `max_usage_per_customer` (RULE-18-05) không có cột ERD → enforce bằng đếm
  `voucher_usages` theo customer, hạn mức lấy ở đâu TBD Q7.
- Cờ stackable (RULE-18-08) không có cột → mặc định 1 voucher/đơn, TBD Q8.
- `ConfigureStorePromotion` không có bảng store-promotion → endpoint + storage TBD Q9.

---

## A. Confirmed Promotion API (8 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /promotions` | `CreatePromotion` (→ DRAFT) — `01#18`, RULE-18-01 |
| 2 | `GET /promotions` | (list trong Org) |
| 3 | `POST /promotions/{id}/activate` | `ManagePromotion` — `01#18` |
| 4 | `POST /promotions/{id}/pause` | `ManagePromotion` — `01#18` |
| 5 | `POST /promotions/{id}/vouchers` | `CreateVoucher` — `01#18`, RULE-18-03 |
| 6 | `PATCH /vouchers/{id}` | `ManageVoucher` — `01#18` |
| 7 | `PUT /stores/{id}/promotions/{pid}` | `ConfigureStorePromotion` — `01#18`, RULE-18-02 |
| 8 | `POST /vouchers/validate` | `ValidateVoucher` — `01#18`, RULE-18-04/05 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CreatePromotion | OrgAdmin | Trong Org | RULE-18-01 (thời gian + điều kiện + nhóm áp dụng + ngân sách) | POST | `/promotions` | Bearer | OrgAdmin Org mình | `[*] → DRAFT` | Non-idempotent | `campaignCode` unique — scope unique TBD Q10 (A1: trong Org) |
| ManagePromotion | OrgAdmin | — | (kích hoạt/tạm dừng) | POST | `…/activate`, `…/pause` | Bearer | OrgAdmin | `DRAFT/PAUSED → ACTIVE`, `ACTIVE → PAUSED` (A2) | Idempotent | Hết hạn auto `EXPIRED` (job, không endpoint) |
| CreateVoucher | OrgAdmin | Trong campaign ACTIVE? (A3) | RULE-18-03 (code, loại, max, min, hạn, đối tượng) | POST | `/promotions/{id}/vouchers` | Bearer | OrgAdmin | `[*] → ACTIVE` (ERD default) | 409 trùng `code` (UK CONFIRMED) | Đối tượng KH (segment) shape TBD Q11 |
| ManageVoucher | OrgAdmin | — | (sửa/ngưng) | PATCH | `/vouchers/{id}` | Bearer | OrgAdmin | → `DISABLED` khi ngưng | Idempotent | Không xóa (đã có usage) — A4 |
| ConfigureStorePromotion | StoreManager | Promotion ACTIVE của Org | RULE-18-02 (bật/tắt tại Store trong khung Org) | PUT | `/stores/{id}/promotions/{pid}` | Bearer | Manager Store mình | none (flag) | Idempotent | `{enabled}` + storage TBD Q9 |
| ValidateVoucher | (System khi checkout) | Đủ 5 điều kiện | RULE-18-04 (ACTIVE + hạn + store + min + đối tượng) + RULE-18-05 (2 caps) + RULE-18-08 (1/đơn) | POST | `/vouchers/validate` | Bearer | System/checkout flow | none (read) | Idempotent | `{code, storeId, orderAmount, orderId?/invoiceId?}` → `{valid, discountAmount?, reason?}` |

**ASSUMPTIONS dùng chung:** A1 `campaignCode` unique trong Org (ERD "duy nhất", thiếu
scope) · A2 activate/pause là 2 transition duy nhất có op hậu thuẫn (EXPIRED auto) ·
A3 voucher tạo trong campaign `ACTIVE` (docs không cấm tạo ở DRAFT — PROPOSED cho
phép mọi trạng thái trừ EXPIRED, TBD Q12) · A4 voucher không xóa khi đã có usage
(derived từ nhu cầu đối soát RULE-18-06).

---

## C. Detailed endpoint contract

### C1. Campaigns (proposed)

- **`POST /promotions`** — Request `{name (req), campaignCode (req), startDate (req),
  endDate (req, > start), budgetCap? (ngân sách CONFIRMED có nhưng ERD thiếu cột —
  TBD Q13), stackable? (TBD Q8)}`. → `DRAFT`. Response `201 {promotionId, status:
  "DRAFT"}`. Status: `201` · `400` · `401` · `403` · `409` trùng code (A1).
- **`…/activate`** — Guards: `DRAFT`/`PAUSED` + trong thời gian hiệu lực.
  → `ACTIVE`. **`…/pause`** — Từ `ACTIVE` → `PAUSED`. Status chuẩn + `409`.
- **`GET /promotions`** — Trong Org. Query PROPOSED: `status?`, `page`, `pageSize`.

### C2. Vouchers (proposed)

- **`POST /promotions/{id}/vouchers`** — Request `{code (req + UK CONFIRMED),
  discountType (req: `PERCENTAGE`/`FIXED_AMOUNT` CONFIRMED), discountValue (req),
  maxDiscountAmount?, minOrderAmount (default 0 ERD), totalUsageLimit (default 100
  ERD), validFrom (req), validUntil (req), eligibleSegment? (TBD Q11)}`.
  → `ACTIVE`. Response `201 {voucherId, code, status: "ACTIVE"}`.
  Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`PATCH /vouchers/{id}`** — `{discountValue?, maxDiscountAmount?,
  validUntil?, status? (`ACTIVE`/`DISABLED`)}`. Không sửa `code`/`discountType`
  khi đã có usage (A5 — derived bảo toàn đối soát). Status chuẩn.
- **`PUT /stores/{id}/promotions/{pid}`** — `{enabled (req)}`. Storage TBD Q9.
  Response `200 {storeId, promotionId, enabled}`.

### C3. Validate (proposed)

- **`POST /vouchers/validate`** — Request `{code (req), storeId (req),
  orderAmount (req), customerId? (mặc định caller), orderId?/invoiceId?}`.
  Checks CONFIRMED (RULE-18-04 5 điểm + 18-05 2 caps + 18-08 1/đơn):
  `ACTIVE` · trong hạn · store bật · `amount ≥ min` · đối tượng (TBD Q11) ·
  `used_count < total_usage_limit` · per-customer count < cap (TBD Q7) ·
  đơn chưa có voucher khác (trừ stackable TBD Q8).
  Response `200 {valid, discountAmount?, reason?}` (`reason` khi invalid —
  PROPOSED codes `VOUCHER_EXPIRED`/`MIN_NOT_MET`/`USAGE_LIMIT`/`NOT_FOR_STORE`…).
  Fail validate không throw 4xx (vẫn `200 valid:false` — A6, để checkout tiếp tục
  không voucher). Status: `200` · `400` (thiếu param) · `401`.

---

## D. Security & reliability

1. Áp voucher + ghi usage + tăng `used_count` cùng transaction với discount (M14/M15)
   — chống vượt cap đồng thời (optimistic/unique `uq_voucher_customer_usage` ERD).
2. Hủy đơn trước hoàn tất → hoàn lượt nếu còn hạn (RULE-18-07 — effect M14/M15).
3. Không bao giờ trả logic cap nội bộ ra ngoài ngoài `reason` tối thiểu.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `budgetCap` lưu ở đâu (RULE-18-01 có, ERD thiếu)? | TBD (BE) | ERD thiếu cột |
| Q4 | Nhóm SP/DV áp dụng lưu ở đâu? | TBD (BE) | RULE-18-01 |
| Q5 | `eligibleSegment` (đối tượng KH) định nghĩa ở đâu? | TBD (PO) — xem Q11 | RULE-18-03/04 |
| Q6 | Voucher dùng cho service grooming tại chỗ (không order/invoice)? | TBD (PO) | RULE-18-04 (order/invoice) |
| Q7 | `max_usage_per_customer` lấy ở đâu (đếm thì có, hạn mức thì không)? | TBD (PO) | ERD thiếu cột |
| Q8 | Stackable lưu ở đâu (mặc định 1/đơn)? | TBD (PO) | ERD thiếu cột |
| Q9 | Store-promotion mapping lưu ở đâu? | TBD (BE) | ERD thiếu bảng |
| Q10 | `campaignCode` unique scope gì (A1: trong Org)? | TBD (PO) | ERD "duy nhất" |
| Q11 | Shape `eligibleSegment`? | TBD (PO) | — |
| Q12 | Tạo voucher ở campaign DRAFT được không (A3)? | TBD (PO) | docs không cấm |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/promotion-v1.yaml`](./openapi/promotion-v1.yaml).
