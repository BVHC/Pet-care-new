# Incident API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Incident Management (Module 21): ghi nhận (thủ công + auto từ
> abort/override), phân loại, điều tra, chuyển cấp, khắc phục, đóng (bất biến).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#21`), `docs/02-business-rules.md`
> (RULE-21-01→08 + severity matrix + trigger hooks), `docs/03-state-machines.md`
> (FSM-14), `docs/04-glossary.md` (`21`), `docs/05-domain-model.md` (`4.21`),
> `docs/06-erd.md` (`incident_reports`).
> **Contract máy đọc:** [`./openapi/incident-v1.yaml`](./openapi/incident-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Auto-incidents (từ `AbortAppointment`/`AbortGrooming`/`EmergencyOverrideAccess`)
  là effects hệ thống — không endpoint riêng; các endpoint dưới cho ghi nhận thủ công
  và xử lý, dùng chung lifecycle.
- `SendIncidentNotification` là effect M23 (bắt buộc với HIGH/CRITICAL) — không endpoint.
- `CLOSED` bất biến tuyệt đối (RULE-21-08): không có endpoint sửa/mở lại trong v1
  (mở lại cần phê duyệt kiểm toán đặc biệt — TBD Q8, không phát minh flow).

---

## A. Confirmed Incident API (8 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /incidents` | `RecordIncident` (+ clinical/grooming thủ công) — `01#21`, RULE-21-01/02/03 |
| 2 | `GET /incidents` | (worklist theo scope — derived read) |
| 3 | `GET /incidents/{id}` | (detail) |
| 4 | `POST /incidents/{id}/classify` | `ClassifyIncident` (severity, trước điều tra) — `01#21`, RULE-21-04 |
| 5 | `POST /incidents/{id}/investigate` | `InvestigateIncident` — `01#21`, RULE-21-07 |
| 6 | `POST /incidents/{id}/escalate` | `EscalateIncident` — `01#21`, RULE-21-06 |
| 7 | `POST /incidents/{id}/handle` | `HandleIncident` (khắc phục → RESOLVED) — `01#21`, RULE-21-07 |
| 8 | `POST /incidents/{id}/close` | `CloseIncident` (bất biến) — `01#21`, RULE-21-08 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| RecordIncident (+Clinical/Grooming thủ công) | Receptionist / Vet / Groomer | Gắn Store (+ pet khi liên quan) | RULE-21-01 (đúng nguồn); auto-cases severity tối thiểu HIGH (hooks) | POST | `/incidents` | Bearer | Staff Store | `[*] → RECORDED` | Non-idempotent | `{storeId, petId?, category, description}` — category CONFIRMED 3 + TBD ops (A1); `incidentNumber` BE sinh (A2) |
| (read) | Staff / Manager | Trong scope | — | GET | `/incidents…` | Bearer | Scope | none | Idempotent | Filters PROPOSED |
| ClassifyIncident | StoreManager | `RECORDED` | RULE-21-04 (xong trước điều tra; 4 mức + SLA matrix) | POST | `…/classify` | Bearer | StoreManager | `RECORDED → CLASSIFIED` | Idempotent (phân lại ghi đè — A3) | `{severity (req)}` — SLA theo matrix là effect nhắc việc |
| InvestigateIncident | StoreManager | `CLASSIFIED` | RULE-21-07 (ghi nguyên nhân) | POST | `…/investigate` | Bearer | StoreManager | `CLASSIFIED → UNDER_INVESTIGATION` | Idempotent (bổ sung kết quả — A4) | `{findings (req)}` |
| EscalateIncident | StoreManager | Vượt thẩm quyền/tranh chấp | RULE-21-06 (lên Org/Platform Admin) | POST | `…/escalate` | Bearer | StoreManager | `… → ESCALATED` | Idempotent | `{toScope (req: ORGANIZATION/PLATFORM), reason?}` |
| HandleIncident | StoreManager | Biện pháp xong | RULE-21-07 (bồi thường/miễn giảm/can thiệp/đào tạo) | POST | `…/handle` | Bearer | StoreManager (+ OrgAdmin khi ESCALATED — A5) | `UNDER_INVESTIGATION/ESCALATED → RESOLVED` (A6: RESOLVED suy từ "khắc phục xong" trước close) | Idempotent | `{actions (req), compensation?}` |
| CloseIncident | StoreManager / OrgAdmin | Mọi khắc phục xong + nghiệm thu | RULE-21-08 (CLOSED bất biến; CRITICAL do OrgAdmin — matrix) | POST | `…/close` | Bearer | Manager / OrgAdmin (CRITICAL: OrgAdmin) | `RESOLVED(/ESCALATED) → CLOSED` | Idempotent | `{acceptanceNote?}` |

**ASSUMPTIONS dùng chung:** A1 `category` nhận 3 giá trị ERD CONFIRMED
(`CLINICAL_EMERGENCY`/`GROOMING_INJURY`/`BREAK_GLASS_OVERRIDE`); sự cố quầy thuần
vận hành map vào đâu TBD Q7 (PROPOSED: nhận thêm `OPERATIONAL`? Không — để TBD,
không phát minh enum) · A2 `incidentNumber` BE sinh (ERD UK, thiếu format) ·
A3 classify lại cho phép trước điều tra · A4 investigate bổ sung findings ·
A5 ESCALATED xử lý bởi cấp nhận chuyển · A6 `RESOLVED` là state suy ra từ "khắc phục
xong" (FSM-14 có trong matrix/glossary — chuyển rõ ràng để close có guard).

---

## C. Detailed endpoint contract

### C1. Record & read (proposed)

- **`POST /incidents`** — Request `{storeId (req), petId?, category (req — xem A1),
  description (req)}`. Reporter suy từ caller (receptionist/vet/groomer — A7:
  không cần khai reporterType). Severity khởi tạo `MEDIUM` (ERD default) trừ
  auto-cases (system set tối thiểu HIGH — hooks CONFIRMED).
  Response `201 {incidentId, incidentNumber, status: "RECORDED"}`.
  Status: `201` · `400` · `401` · `403` · `404`.
- **`GET /incidents`** — Scope: store/org/platform theo role. Query PROPOSED:
  `status?`, `severity?`, `storeId?`, `page`, `pageSize`. **`GET /incidents/{id}`** —
  detail + history (history từ đâu TBD Q9 — PROPOSED đọc incident + audit M25).

### C2. Handle flow (proposed)

- **`…/classify`** — `{severity (req: `LOW`/`MEDIUM`/`HIGH`/`CRITICAL` CONFIRMED)}`.
  Từ `RECORDED`. HIGH/CRITICAL → effect notify bắt buộc (RULE-21-05 + matrix:
  Customer ± Org/Platform Admin theo mức).
- **`…/investigate`** — `{findings (req)}`. Từ `CLASSIFIED`.
- **`…/escalate`** — `{toScope (req), reason?}`. Lên Org/Platform.
- **`…/handle`** — `{actions (req), compensation?}`. → `RESOLVED` (A6).
- **`…/close`** — `{acceptanceNote?}`. Từ `RESOLVED` (hoặc `ESCALATED` đã xử lý —
  A5). CRITICAL chỉ OrgAdmin (matrix CONFIRMED). → `CLOSED` bất biến.
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409` sai trạng thái/
  sai thẩm quyền mức độ.

---

## D. Security & reliability

1. Auto-incident từ abort/override không bao giờ mất (cùng transaction với action gốc).
2. CLOSED bất biến — không endpoint sửa; audit mọi transition (M25).
3. Notify HIGH/CRITICAL bắt buộc đúng ma trận người nhận (test theo mức).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `incidentNumber` format? | TBD (BE) | ERD UK |
| Q4 | Bồi thường (`compensation`) shape + có nối refund M17 không? | TBD (PO) | RULE-21-07 (bồi thường) |
| Q5 | SLA matrix (1/4/12/24h) enforce nhắc việc ở đâu? | TBD (PO/BE) | matrix CONFIRMED thời gian |
| Q6 | Điều tra có phân công investigator không? | TBD (PO) | docs không nêu |
| Q7 | Category sự cố quầy thuần vận hành (A1)? | TBD (PO) | ERD 3 giá trị thiếu ops-case |
| Q8 | Mở lại CLOSED cần phê duyệt kiểm toán thế nào (không endpoint v1)? | TBD (PO) | RULE-21-08 |
| Q9 | History điều tra lưu ở đâu (bản ghi con hay audit)? | TBD (BE) | docs không nêu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/incident-v1.yaml`](./openapi/incident-v1.yaml).
