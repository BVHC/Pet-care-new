# Grooming API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Grooming Management (Module 11): phiên spa, kiểm tra thể trạng,
> phụ phí phát sinh + Surcharge Invoice (D-02), dừng khẩn cấp.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#11`), `docs/02-business-rules.md`
> (RULE-11-01→06 + surcharge/abort invariants), `docs/03-state-machines.md` (FSM-15),
> `docs/04-glossary.md` (`11`), `docs/05-domain-model.md` (`4.11`),
> `docs/06-erd.md` (`grooming_sessions`/`health_inspection_reports`/
> `grooming_service_lines`).
> **Contract máy đọc:** [`./openapi/grooming-v1.yaml`](./openapi/grooming-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Đặt lịch grooming đi qua [`appointment-v1.md`](./appointment-v1.md) (dịch vụ
  grooming như mọi service) — ở đây không endpoint book riêng.
- `ConfirmAdditionalService` kích hoạt tạo Surcharge Invoice **nội bộ** (effect thuộc
  M15, invoice `DRAFT`/`ISSUED` gắn session) — không endpoint invoice riêng ở đây;
  response trả `surchargeInvoiceId`.
- Hóa đơn gốc đã `PAID` không bao giờ bị chèn thêm line (D-01/D-02 CONFIRMED).

---

## A. Confirmed Grooming API (11 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /grooming-sessions` | `CheckInGrooming` (mở phiên → WAITING) — `01#11`, RULE-11-01 |
| 2 | `GET /grooming-sessions/{id}` | (theo dõi tiến độ — chủ Pet per RULE-11-04) |
| 3 | `POST /grooming-sessions/{id}/inspect` | `InspectPet` (→ WAITING đạt / REJECTED loại) — `01#11`, RULE-11-02 |
| 4 | `POST /grooming-sessions/{id}/start` | `PerformGrooming` (→ IN_PROGRESS) — `01#11`, RULE-11-04 |
| 5 | `PATCH /grooming-sessions/{id}` | `UpdateGroomingResult` — `01#11`, RULE-11-04/05 |
| 6 | `POST /grooming-sessions/{id}/add-ons` | `AddGroomingService` (→ AWAITING_CUSTOMER_APPROVAL) — `01#11`, RULE-11-03 |
| 7 | `POST /grooming-sessions/{id}/add-ons/{lineId}/confirm` | `ConfirmAdditionalService` (→ IN_PROGRESS + Surcharge Invoice) — `01#11`, RULE-11-03 |
| 8 | `POST /grooming-sessions/{id}/add-ons/{lineId}/reject` | `RejectAdditionalService` (→ IN_PROGRESS tiếp tục gói gốc) — `01#11`, RULE-11-03 |
| 9 | `POST /grooming-sessions/{id}/complete` | `CompleteGrooming` (→ COMPLETED, bất biến) — `01#11`, RULE-11-05 |
| 10 | `POST /grooming-sessions/{id}/cancel` | `CancelGrooming` (WAITING → CANCELLED) — `01#11`, RULE-11-01 |
| 11 | `POST /grooming-sessions/{id}/abort` | `AbortGrooming` (IN_PROGRESS → ABORTED) — `01#11`, RULE-11-06 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| CheckInGrooming | Receptionist (/Groomer) | Appointment hợp lệ (booked/bridged) | RULE-11-01 | POST | `/grooming-sessions` | Bearer | Receptionist | `[*] → WAITING` (1:1 appointment — ERD) | 409 nếu appointment đã có session (UK 1:1) | Session gắn appointment+store+pet+groomer (ERD) |
| (track) | Customer / Groomer | — | RULE-11-04 (chủ theo dõi tiến độ) | GET | `/grooming-sessions/{id}` | Bearer | Owner / staff | none | Idempotent | Derived read |
| InspectPet | Groomer | `WAITING` | RULE-11-02 (biên bản da/lông/tai/mắt/tính khí; fail → hội chẩn/điều trị trước hoặc REJECTED) | POST | `…/inspect` | Bearer | Groomer | `WAITING` (đạt) / `→ REJECTED` + `GroomingRejected` (loại) | Idempotent (ghi đè biên bản gần nhất, A1) | `is_accepted=false` + lý do → REJECTED |
| PerformGrooming | Groomer | Đã inspect đạt | RULE-11-04 | POST | `…/start` | Bearer | Groomer | `WAITING → IN_PROGRESS` + `GroomingStarted` | Idempotent | Chưa inspect → 409 PROPOSED |
| UpdateGroomingResult | Groomer | `IN_PROGRESS` (không sau COMPLETED) | RULE-11-04/05 | PATCH | `/grooming-sessions/{id}` | Bearer | Groomer | none | Idempotent | Shape PROPOSED: `{progressNote?, photoUrl?}` (ảnh hoàn thiện RULE-11-05) |
| AddGroomingService | Groomer | `IN_PROGRESS` | RULE-11-03 (→ chờ duyệt + notify customer) | POST | `…/add-ons` | Bearer | Groomer | `IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL` + line PENDING | Non-idempotent (mỗi call 1 line) | `{serviceId, price, note?}` (A2) |
| ConfirmAdditionalService | Customer | Line PENDING | RULE-11-03 (auto Surcharge Invoice DRAFT/ISSUED, không sửa hóa đơn gốc) | POST | `…/add-ons/{lineId}/confirm` | Bearer | Chủ Pet | line → APPROVED; session → `IN_PROGRESS` (khi hết line pending — A3) | Idempotent | Trả `surchargeInvoiceId` |
| RejectAdditionalService | Customer | Line PENDING | RULE-11-03 (tiếp tục gói gốc) | POST | `…/add-ons/{lineId}/reject` | Bearer | Chủ Pet | line → REJECTED; session → `IN_PROGRESS` (A3) | Idempotent | — |
| CompleteGrooming | Groomer | `IN_PROGRESS` + hết công đoạn | RULE-11-05 (release bàn + handover receptionist; COMPLETED bất biến) | POST | `…/complete` | Bearer | Groomer | `→ COMPLETED` + `GroomingCompleted` | Idempotent | Sau COMPLETED: cấm add-on/sửa (409) |
| CancelGrooming | Customer / Receptionist | `WAITING` (trước phục vụ) | RULE-11-01 | POST | `…/cancel` | Bearer | Owner / receptionist | `WAITING → CANCELLED` | Idempotent | Hủy sau khi start → dùng abort flow |
| AbortGrooming | Groomer / StoreManager | `IN_PROGRESS` + sự cố | RULE-11-06 (`abort_reason` + incident HIGH + notify + hoàn phần chưa làm) | POST | `…/abort` | Bearer | Groomer / StoreManager | `IN_PROGRESS → ABORTED` + `GroomingAborted` | Idempotent | Effects M21 (incident) + M17 (refund) |

**ASSUMPTIONS dùng chung:** A1 inspect ghi đè biên bản gần nhất (ERD 1:1
`health_inspection_reports` — không lịch sử nhiều bản) · A2 add-on line mang
`is_addon=true` + `price` + `customer_approved` (ERD `grooming_service_lines`) ·
A3 session về `IN_PROGRESS` khi không còn line PENDING (nhiều add-ons song song được).

---

## C. Detailed endpoint contract

### C1. Session (proposed)

- **`POST /grooming-sessions`** — Request `{appointmentId (req — 1:1 ERD),
  groomerId? (mặc định caller)}`. Guards: appointment hợp lệ + chưa có session
  (409 nếu có). Response `201 {sessionId, appointmentId, status: "WAITING",
  hasSurcharge: false}`. Status: `201` · `400` · `401` · `403` · `404` · `409`.
- **`GET /grooming-sessions/{id}`** — Owner/staff. Response session + inspection +
  lines + `surchargeInvoiceId?`. Status: `200` · `401` · `403` · `404`.
- **`PATCH /grooming-sessions/{id}`** — Request `{progressNote?, photoUrl?}`
  (PROPOSED). Chỉ `IN_PROGRESS`; sau COMPLETED → `409`. Status chuẩn.

### C2. Inspect & start (proposed)

- **`…/inspect`** — Request `{skinCoatCondition (req), earEyeCondition (req),
  temperament (req: `CALM`/`AGGRESSIVE`/`FEARFUL` CONFIRMED), isAccepted (req),
  rejectionReason? (bắt buộc khi `isAccepted=false` — derived)}`.
  `isAccepted=true` → ở `WAITING`; `false` → `REJECTED` + `GroomingRejected`.
  Response `200 {sessionId, status, inspectionId}`.
- **`…/start`** — Guards: đã inspect đạt (chưa inspect → `409` PROPOSED
  `INSPECTION_REQUIRED`). Response `200 {status: "IN_PROGRESS"}`.

### C3. Add-ons (proposed)

- **`POST …/add-ons`** — Request `{serviceId (req), price (req), note?}` (A2).
  Từ `IN_PROGRESS` → `AWAITING_CUSTOMER_APPROVAL` + notify customer (effect M23).
  Response `201 {lineId, status: "PENDING"}`.
- **`POST …/add-ons/{lineId}/confirm`** — Chỉ chủ Pet. Effect CONFIRMED: tạo/tìm
  Surcharge Invoice (`DRAFT`/`ISSUED`, gắn session) — thuộc M15, ở đây chỉ nhận id.
  Response `200 {lineId, approved: true, surchargeInvoiceId, sessionStatus}`.
- **`POST …/add-ons/{lineId}/reject`** — Response `200 {lineId, approved: false,
  sessionStatus}` (tiếp tục gói gốc CONFIRMED).
- Line đã xử lý gọi lại → 200 idempotent; sai trạng thái → `409`.

### C4. Finish & abort (proposed)

- **`…/complete`** — Từ `IN_PROGRESS` (hết line pending — còn chờ duyệt → `409`
  PROPOSED). Release bàn + handover receptionist (effects). COMPLETED bất biến.
- **`…/cancel`** — Từ `WAITING`. Response `200 {status: "CANCELLED"}`.
- **`…/abort`** — Request `{abortReason (req CONFIRMED)}`. Từ `IN_PROGRESS`.
  Effects CONFIRMED: `GroomingIncident` (HIGH) + notify customer + hoàn phần chưa
  làm (M17). Response `200 {status: "ABORTED"}`.
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409`.

---

## D. Security & reliability

1. Add-on chỉ thành tiền qua Surcharge Invoice độc lập — cấm cộng line vào hóa đơn
   gốc (D-01/D-02, server enforce).
2. COMPLETED bất biến: block add-on/sửa sau hoàn tất ở tầng API.
3. Abort luôn để lại incident + notify + refund yêu cầu (thiếu là lỗi hệ thống).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 |Ưu tiên inspect: groomer tự REJECT hay bắt buộc hội chẩn vet trước (RULE-11-02 "có quyền yêu cầu")? | TBD (PO) | RULE-11-02 |
| Q4 | `price` add-on do ai chốt (groomer đề xuất, ai duyệt giá)? | TBD (PO) | RULE-11-03 |
| Q5 | Từ chối add-on rồi groomer đề xuất lại được không (line mới)? | TBD (PO) — PROPOSED được (line mới) | docs không cấm |
| Q6 | Surcharge Invoice `DRAFT` hay `ISSUED` ngay khi confirm? | TBD (PO) | RULE-11-03 "DRAFT / ISSUED" |
| Q7 | Session cho walk-in không appointment? (ERD bắt buộc 1:1 — trả lời: không, bridge M07 tạo appointment trước) | DECIDED (ERD) | ERD NOT NULL |
| Q8 | Pet hung dữ mãn tính có flag cấm book grooming không? | TBD (PO) | docs không nêu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/grooming-v1.yaml`](./openapi/grooming-v1.yaml).
