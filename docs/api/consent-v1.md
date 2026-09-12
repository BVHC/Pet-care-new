# Consent API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Consent & Privacy Management — phần quyền riêng tư dữ liệu
> (Module 22): đồng thuận xử lý dữ liệu, xuất/xóa dữ liệu, chính sách. Đồng thuận
> bệnh án liên Store (OTP 24h / emergency) đã nằm ở [`clinical-v1.md`](./clinical-v1.md),
> không định nghĩa lại (RULE-22-02/03/08 đã cover ở đó).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#22`), `docs/02-business-rules.md`
> (RULE-22-01/04/05/06/07/09/10), `docs/04-glossary.md` (`22`),
> `docs/05-domain-model.md` (`4.22`), `docs/06-erd.md` (không có bảng consent riêng —
> storage TBD Q8).
> **Contract máy đọc:** [`./openapi/consent-v1.yaml`](./openapi/consent-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Cross-store consent (request/verify/revoke/emergency + `ProcessConsentExpiry`)
  thuộc clinical-v1 — ở đây không endpoint trùng.
- `ProcessDataExport` / `ProcessDataDeletion` là jobs hệ thống — endpoint chỉ mở
  request + đọc kết quả, không endpoint process riêng.
- `ManagePrivacyPolicy` / `ManageRetentionPolicy`: ERD không có bảng policy (giống
  M03) nhưng `system_configs` (key/value theo Org) là nơi chứa ứng viên PROPOSED —
  endpoint kèm storage TBD Q8 (khác M03 ở chỗ đã chỉ được nơi chứa có thể).

---

## A. Confirmed Consent API (7 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /privacy/consents` | `GrantConsent` — `01#22`, RULE-22-01 |
| 2 | `POST /privacy/consents/revoke` | `RevokeConsent` — `01#22` |
| 3 | `GET /privacy/consents/me` | (xem đồng thuận của mình — derived read) |
| 4 | `POST /privacy/exports` | `RequestDataExport` — `01#22`, RULE-22-09 |
| 5 | `GET /privacy/exports/{id}` | (đọc kết quả export — derived read) |
| 6 | `POST /privacy/deletion-requests` | `RequestDataDeletion` — `01#22`, RULE-22-04 |
| 7 | `GET /privacy/deletion-requests/{id}` | (đọc kết quả xóa — derived read) |
| 8 | `PUT /organizations/{id}/privacy-policy` | `ManagePrivacyPolicy` — `01#22`, RULE-22-06 |
| 9 | `PUT /organizations/{id}/retention-policy` | `ManageRetentionPolicy` — `01#22`, RULE-22-05 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| GrantConsent | Customer | — | RULE-22-01 (đúng chủ + mục đích + hạn) + dual-gate nền (RULE-22-07 cho mọi truy cập) | POST | `/privacy/consents` | Bearer | Self | + consent ACTIVE (storage TBD Q8) | Idempotent (grant lại trả bản cũ) | `{purpose (MARKETING/RECORD_SHARING/PAYMENT_STORAGE PROPOSED từ ví dụ rule), validUntil?}` |
| RevokeConsent | Customer | Consent còn hiệu lực | (thu hồi) | POST | `/privacy/consents/revoke` | Bearer | Self | → REVOKED | Idempotent | Marketing opt-out M23 đọc từ đây (RULE-23-05) |
| (read) | Customer | Của mình | — | GET | `/privacy/consents/me` | Bearer | Self | none | Idempotent | Derived read |
| RequestDataExport | Customer | — | RULE-22-09 (tổng hợp + mã hóa + link tải có hạn) | POST | `/privacy/exports` | Bearer | Self | `[*] → PROCESSING` (PROPOSED states, TBD Q9) | Non-idempotent (mỗi call 1 gói) | Xong → `COMPLETED` + `downloadUrl` + `expiresAt` |
| (read export) | Customer | Của mình | — | GET | `/privacy/exports/{id}` | Bearer | Self | none | Idempotent | Link hết hạn → xin lại (không gia hạn — A1) |
| RequestDataDeletion | Customer | — | RULE-22-04 (giữ lại invoice/bệnh án đủ hạn luật định) | POST | `/privacy/deletion-requests` | Bearer | Self | `[*] → PROCESSING` → `COMPLETED/PARTIAL` (PROPOSED, TBD Q9) | Non-idempotent | `{scope? (ALL/PARTIAL PROPOSED), reason?}`; response kèm `retained[]` (dữ liệu giữ lại theo luật) |
| ManagePrivacyPolicy | OrgAdmin | Trong Org | RULE-22-06 (đổi → notify user + audit) | PUT | `/organizations/{id}/privacy-policy` | Bearer | OrgAdmin Org mình | (lưu bản mới + effect notify/audit) | Idempotent | `{content (req), version?}` PROPOSED, storage TBD Q8 |
| ManageRetentionPolicy | OrgAdmin | Trong Org + đồng bộ mọi Store | RULE-22-05 | PUT | `/organizations/{id}/retention-policy` | Bearer | OrgAdmin Org mình | (lưu + áp đồng bộ) | Idempotent | `{rules[] (PROPOSED: [{dataType, retainDays}])}`, storage TBD Q8 |

**ASSUMPTIONS dùng chung:** A1 link export hết hạn không gia hạn (RULE-22-09 "có thời
hạn" — xin gói mới) · A2 `purpose` từ 3 ví dụ RULE-22-01 (mở rộng TBD Q10) · A3 policy
lưu dạng versioned content (docs: đổi phải notify + audit → bản mới, không sửa lặng).

---

## C. Detailed endpoint contract

### C1. Consents (proposed)

- **`POST /privacy/consents`** — Request `{purpose (req: `MARKETING`/
  `RECORD_SHARING`/`PAYMENT_STORAGE` PROPOSED — A2), validUntil?}`.
  Response `201 {consentId, purpose, status: "GRANTED", validUntil?}`.
  Status: `201` · `400` · `401`.
- **`POST /privacy/consents/revoke`** — Request `{purpose (req)}`. → `REVOKED`.
  Marketing revoke = opt-out (M23 đọc). Response `200 {purpose, status: "REVOKED"}`.
- **`GET /privacy/consents/me`** — Response `200 {consents: [{purpose, status,
  validUntil?}]}`.

### C2. Export & deletion (proposed)

- **`POST /privacy/exports`** — Request `{}`. Job tổng hợp (JSON/PDF — định dạng gì
  TBD Q11; PROPOSED trả cả 2 link?). Response `202 {exportId, status: "PROCESSING"}`.
- **`GET /privacy/exports/{id}`** — Response `200 {exportId, status:
  "PROCESSING"/"COMPLETED"/"FAILED" (PROPOSED, TBD Q9), downloadUrl?,
  expiresAt?}`. Hết hạn → `410` PROPOSED `EXPORT_LINK_EXPIRED` (xin lại — A1).
- **`POST /privacy/deletion-requests`** — Request `{scope? (`ALL`/`PARTIAL`,
  default ALL — PROPOSED), reason?}`. Job check retention: xóa được thì xóa/ẩn danh,
  dữ liệu luật định giữ lại liệt kê `retained[]` (CONFIRMED nguyên tắc RULE-22-04).
  Response `202 {requestId, status: "PROCESSING"}`.
- **`GET /privacy/deletion-requests/{id}`** — Response `200 {requestId, status:
  "COMPLETED"/"PARTIAL" (PROPOSED, TBD Q9), retained: [{dataType, reason}]}`.

### C3. Policies (proposed)

- **`PUT /organizations/{id}/privacy-policy`** — Request `{content (req),
  version?}` (A3). Effects CONFIRMED: notify users + audit (RULE-22-06).
  Response `200 {version, effectiveAt}`. Chỉ OrgAdmin Org mình.
- **`PUT /organizations/{id}/retention-policy`** — Request `{rules (req: [{dataType,
  retainDays}] PROPOSED)}`. Áp đồng bộ mọi Store (RULE-22-05 CONFIRMED).
  Response `200 {rules}`. Storage cả hai: ứng viên `system_configs`, TBD Q8.
- **Status:** `200` · `400` · `401` · `403` · `404`.

---

## D. Security & reliability

1. Dual-gate (RULE-22-07): mọi truy cập dữ liệu cá nhân/bệnh án check role-permission
   + consent (trừ emergency) — các module khác enforce, ở đây cung cấp consent store.
2. Export mã hóa + link có hạn; không bao giờ trả dữ liệu thô qua kênh thường.
3. Xóa tuân retention pháp lý — xóa lố là lỗi hệ thống (test với dữ liệu luật định).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `purpose` còn giá trị nào (A2: 3)? | TBD (PO) | RULE-22-01 ví dụ |
| Q4 | Consent hết hạn tự EXPIRED hay ở GRANTED mãi (validUntil null)? | TBD (PO) | RULE-22-01 (có hạn) |
| Q5 | Export format JSON/PDF hay cả 2? Dung lượng/giới hạn? | TBD (PO) | RULE-22-09 |
| Q6 | `retained[]` gồm đúng loại nào (invoice/bệnh án + gì nữa)? Thời hạn luật cụ thể? | TBD (PO/Pháp chế) | RULE-22-04 |
| Q7 | Partial deletion chọn theo loại dữ liệu nào? | TBD (PO) | docs không nêu |
| Q8 | Consent/policy/request lưu ở đâu (bảng mới hay `system_configs`)? | TBD (BE) | ERD thiếu |
| Q9 | States export/deletion (A: PROCESSING/COMPLETED/PARTIAL/FAILED)? | TBD (BE) | docs không FSM |
| Q10 | Xem Q3 (purposes) | TBD (PO) | — |
| Q11 | Xem Q5 (format) | TBD (PO) | — |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/consent-v1.yaml`](./openapi/consent-v1.yaml).
Clinical cross-store consent: [`clinical-v1.md`](./clinical-v1.md) (không trùng).
