# Audit API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain System Administration & Audit (Module 25): tra cứu nhật ký
> kiểm toán bất biến theo scope. Ghi log là trách nhiệm hệ thống (mọi module ghi
> qua `RecordAuditLog` + Track chuyên biệt) — ở đây CHỈ CÓ READ, đúng bản chất
> append-only (INSERT/SELECT, cấm UPDATE/DELETE — RULE-25-02).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#25`), `docs/02-business-rules.md`
> (RULE-25-01→07 + immutability invariant), `docs/04-glossary.md` (`25`),
> `docs/05-domain-model.md` (`4.25`), `docs/06-erd.md` (`audit_logs`).
> Không FSM (immutable log CONFIRMED).
> **Contract máy đọc:** [`./openapi/audit-v1.yaml`](./openapi/audit-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Không có write endpoint nào: `RecordAuditLog` + 4 `Track*` là tagging/writes của
  hệ thống — thể hiện qua filters trên read (entity/action), không endpoint riêng.
- `system_configs` (TenantConfig) không có op nào trong `01#25` → không endpoint
  (cấu hình do vận hành quản lý ngoài API, TBD Q6).
- Không endpoint purge/xóa log — RULE-25-02 cấm tuyệt đối kể cả SUPER_ADMIN/DBA.

---

## A. Confirmed Audit API (2 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /audit-logs` | `ViewAuditLog` + 4 `Track*` (qua filters) — `01#25`, RULE-25-01/03/04/05/06/07 |
| 2 | `GET /audit-logs/{id}` | (detail 1 bản ghi — derived read) |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ViewAuditLog | SuperAdmin / OrgAdmin / StoreManager | Trong scope | RULE-25-07: platform all / org / store; Finance+Inventory chỉ log chuyên môn (server filter theo role — A1) | GET | `/audit-logs` | Bearer | Scope + specialty | none | Idempotent | Filters PROPOSED: `entityType?`, `action?`, `actorId?`, `from?`, `to?`, `page`, `pageSize` |
| (Track* reads) | Finance / Inventory / Medical | Chuyên môn mình | RULE-25-03/04/05/06 (permission / medical / payment-refund / inventory) | GET | `/audit-logs` (cùng endpoint + filter) | Bearer | Specialty (A1) | none | Idempotent | `entityType`/`action` presets TBD Q7 (PROPOSED bộ lọc gợi ý) |
| (detail) | Theo scope | — | — | GET | `/audit-logs/{id}` | Bearer | Scope | none | Idempotent | Full snapshots before/after (ERD) |

**ASSUMPTIONS dùng chung:** A1 specialty filter: Finance → payment/invoice/refund
actions; Inventory → stock actions; medical → record/consent actions (suy trực tiếp
từ RULE-25-03→06 + 25-07) · A2 sort `created_at` desc (hiển nhiên cho log).

---

## C. Detailed endpoint contract

### C1. Reads (proposed)

- **`GET /audit-logs`** — Query PROPOSED: `entityType?`, `action?`, `actorId?`,
  `from?`, `to?`, `page`, `pageSize`. Scope CONFIRMED (RULE-25-07): super toàn bộ;
  org trong Org; store trong Store; finance/inventory auto-lọc chuyên môn (A1).
  Response `200 {items: [{logId, actorId?, actorRole?, action, entityType, entityId,
  scopeType?, scopeId?, previousState? (snapshot_before), newState? (snapshot_after),
  ipAddress?, clientChannel?, timestamp}], page, pageSize, total}` — fields suy
  trực tiếp từ ERD `audit_logs` + danh mục bắt buộc RULE-25-01 (map tên: A3).
  Status: `200` · `400` · `401` · `403`.
- **`GET /audit-logs/{id}`** — Detail full snapshots. Status: `200` · `401` ·
  `403` (ngoài scope) · `404`.

---

## D. Security & reliability

1. Append-only ở tầng DB (REVOKE UPDATE/DELETE/TRUNCATE — invariant) + không endpoint
   write ở tầng API — 2 lớp phòng thủ.
2. Mọi action nhạy cảm (emergency, maker-checker, phân quyền) bắt buộc có log —
   các module khác chịu trách nhiệm ghi; thiếu log là lỗi hệ thống.
3. Không log secret thô (password/OTP/CVV) — RULE-23-06 áp dụng chéo.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | Map tên field RULE-25-01 (`log_id`, `client_channel`…) sang ERD (`id`, `user_agent`…) — A3 chốt map 1:1, đúng không? | TBD (BE) | RULE vs ERD lệch tên |
| Q4 | Specialty presets cho finance/inventory (A1) liệt kê chính xác actions nào? | TBD (PO) | RULE-25-07 |
| Q5 | Giữ log bao lâu (retention audit)? Ai được archive? | TBD (PO) | docs không nêu (bất biến ≠ giữ mãi) |
| Q6 | `system_configs` quản trị qua API không (không op)? | TBD (PO) | `01#25` không có |
| Q7 | Bộ lọc gợi ý theo Track* (A: presets)? | TBD (BE) | docs không shape |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/audit-v1.yaml`](./openapi/audit-v1.yaml).
