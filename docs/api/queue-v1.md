# Queue API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Walk-in & Queue Management (Module 07): hàng đợi FIFO tại quầy,
> gọi số, cầu nối Walk-in → Appointment (RULE-07-05).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#7`), `docs/02-business-rules.md`
> (RULE-07-01→08), `docs/03-state-machines.md` (FSM-17), `docs/04-glossary.md` (`07`),
> `docs/05-domain-model.md` (`4.7`), `docs/06-erd.md` (`daily_queues`/`queue_entries`).
> **Contract máy đọc:** [`./openapi/queue-v1.yaml`](./openapi/queue-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ManageQueueOrder` (giữ thứ tự FIFO), `SendTurnNotification` (gửi khi gọi số),
  bridge tạo `Appointment` nội bộ — là effects của các endpoint, không endpoint riêng.
- `CoordinateQueue` (điều phối: mở bàn, điều nhân sự, ưu tiên) **defer khỏi v1**:
  op CONFIRMED nhưng docs không định nghĩa transition/effect cụ thể nào → TBD Q7,
  không phát minh body `{action}` giả.
- Số thứ tự theo ngày, tăng dần theo từng phân loại dịch vụ chuyên môn (RULE-07-01):
  scheme đánh số chi tiết TBD Q6 — PROPOSED `{prefix theo service}` + sequence/ngày.

---

## A. Confirmed Queue API (7 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /stores/{id}/queue-entries` | `RegisterQueueEntry` (→ WAITING) — `01#7`, RULE-07-01/02 |
| 2 | `GET /stores/{id}/queue` | (xem hàng đợi ngày — derived read cho bảng điện tử) |
| 3 | `POST /queue-entries/{id}/call` | `CallQueueEntry` (→ CALLED) — `01#7`, RULE-07-03 |
| 4 | `POST /queue-entries/{id}/start-service` | `StartQueueService` (→ IN_SERVICE + bridge Appointment) — `01#7`, RULE-07-05 |
| 5 | `POST /queue-entries/{id}/complete` | `CompleteQueueEntry` (→ COMPLETED + Appointment COMPLETED) — `01#7`, RULE-07-07 |
| 6 | `POST /queue-entries/{id}/cancel` | `CancelQueueEntry` (→ CANCELLED) — `01#7`, RULE-07-04 |
| 7 | `POST /queue-entries/{id}/no-show` | `MarkQueueNoShow` (→ NO_SHOW sau 3 lần gọi) — `01#7`, RULE-07-06 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| RegisterQueueEntry | Customer / Receptionist | Store `ACTIVE` + đang nhận (daily queue open) | RULE-07-01 (ticket 1 Store; số theo service category), RULE-07-02 (1 vị trí; FIFO; `TRIAGE_EMERGENCY` bypass) | POST | `/stores/{id}/queue-entries` | Bearer | Customer (cho mình) / receptionist | `[*] → WAITING` + `QueueEntryRegistered` | Mỗi call 1 ticket mới | `priority` PROPOSED (A1); scheme số TBD Q6 |
| (view) | Staff / Customer | — | FIFO hiển thị | GET | `/stores/{id}/queue` | Bearer | Trong Store | none | Idempotent | Derived read cho bảng điện tử (A2) |
| CallQueueEntry | Receptionist / Vet / Groomer | `WAITING` | RULE-07-03 (gọi + notify) | POST | `…/call` | Bearer | Staff Store | `WAITING → CALLED` + `called_times+1` | Idempotent (gọi lại chỉ notify) | Gọi lại tăng `called_times` CONFIRMED (đếm tối đa 3) |
| StartQueueService | Vet / Groomer | `CALLED` | RULE-07-05 (bridge: tạo Appointment `WALK_IN`/`IN_PROGRESS` + gắn ticket/staff/resource) | POST | `…/start-service` | Bearer | Staff chuyên môn | `CALLED → IN_SERVICE` + appointment nội bộ | Idempotent (đã bridge trả appointment cũ) | Request mang `staffId` + `resourceId` (A3) |
| CompleteQueueEntry | Vet / Groomer (handover receptionist lập hóa đơn) | `IN_SERVICE` | RULE-07-07 (đồng bộ Appointment → COMPLETED + release) | POST | `…/complete` | Bearer | Staff chuyên môn | `IN_SERVICE → COMPLETED` | Idempotent | Bàn giao invoicing (M15) như check-out |
| CancelQueueEntry | Customer / Receptionist | `WAITING`/`CALLED` | RULE-07-04 (đôn thứ tự sau) | POST | `…/cancel` | Bearer | Owner ticket / receptionist | `→ CANCELLED` | Idempotent | — |
| MarkQueueNoShow | Receptionist | `CALLED` + 3 lần gọi không mặt | RULE-07-06 | POST | `…/no-show` | Bearer | Receptionist | `CALLED → NO_SHOW` + gọi số tiếp theo (hiệu ứng quầy) | Idempotent | Khoảng giãn giữa lần gọi TBD Q8 |

**ASSUMPTIONS dùng chung:** A1 `priority` (`NORMAL`/`URGENT`/`TRIAGE_EMERGENCY`)
cho phép truyền khi register; `TRIAGE_EMERGENCY` chỉ doctor/receptionist gắn
(RULE-07-02) · A2 `GET queue` là derived read cho bảng điện tử (không có op đọc
riêng trong docs) · A3 start-service nhận `staffId`+`resourceId` để gắn bridge
(docs bắt buộc gắn 3 mã: ticket/staff/resource).

---

## C. Detailed endpoint contract

### C1. Register + view (proposed)

- **`POST /stores/{id}/queue-entries`** — Request `{customerId? (receptionist tạo hộ),
  petId?, serviceId (req), priority? (A1, default `NORMAL`)}`.
  Guards: store `ACTIVE` + daily queue đang mở (A4: `daily_queues.is_active`;
  đóng/mở quầy TBD Q9) + service khả dụng tại Store.
  Response `201 {ticketId, queueNumber (scheme TBD Q6), position, status: "WAITING"}`.
  Status: `201` · `400` · `401` · `403` · `404` · `409` store đóng/không nhận.
- **`GET /stores/{id}/queue`** — Response `200 {queueDate, isActive, entries:
  [{ticketId, queueNumber, priority, status, calledTimes, position}]}` sort FIFO
  (emergency trước — RULE-07-02). Query PROPOSED: `status?`, `serviceId?`.

### C2. Ticket lifecycle (proposed)

- **`…/call`** — Từ `WAITING`. Tăng `called_times`, bắn `SendTurnNotification`
  (effect). Gọi lại khi đã `CALLED`: chỉ notify + tăng đếm (đếm cho rule 3 lần).
- **`…/start-service`** — Request `{staffId (req), resourceId (req)}` (A3).
  Từ `CALLED`. Bridge CONFIRMED: tạo `Appointment` nội bộ (`channel=WALK_IN`,
  gắn `queue_entry_id`, `staff`, `resource`, status `IN_PROGRESS`).
  Response `200 {ticketId, status: "IN_SERVICE", appointmentId}`.
- **`…/complete`** — Từ `IN_SERVICE`. Đồng bộ appointment liên kết → `COMPLETED`,
  release resource, handoff invoicing. Response `200 {status: "COMPLETED"}`.
- **`…/cancel`** — Từ `WAITING`/`CALLED`. Tự đôn thứ tự (effect).
- **`…/no-show`** — Guards: `CALLED` + `called_times >= 3` (RULE-07-06 CONFIRMED).
  Chưa đủ 3 lần gọi → `409` PROPOSED `CALL_LIMIT_NOT_REACHED`.
- **Status chung:** `200` · `400` · `401` · `403` · `404` · `409` sai trạng thái.

---

## D. Security & reliability

1. FIFO + duy nhất vị trí (RULE-07-02) — phát số trong transaction (sequence/ngày).
2. Bridge nguyên tử: ticket `IN_SERVICE` + appointment `IN_PROGRESS` cùng transaction;
   appointment có CHECK `WALK_IN ⇔ queue_entry_id NOT NULL` (ERD).
3. `appointment_id` unique mỗi ticket (`uq_queue_entries_appointment_id` ERD) —
   start-service lặp không đẻ appointment thứ hai.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 |Ưu tiên `URGENT` vs `TRIAGE_EMERGENCY`: ai gắn `URGENT`, thứ tự giữa chúng? | TBD (PO) | RULE-07-02 chỉ rõ emergency |
| Q4 | Khách vãng lai chưa có tài khoản: ticket `customer_id=NULL` (ERD cho phép) rồi link sau bằng gì? | TBD (PO) | ERD nullable, ops không nêu |
| Q5 | `serviceId` bắt buộc khi lấy số? Khách chưa biết khám gì thì sao? | TBD (PO) | ERD `service_id` YES |
| Q6 | Scheme số thứ tự (prefix theo service? sequence reset/ngày?) | TBD (BE) — PROPOSED prefix+sequence/ngày | RULE-07-01 + ví dụ `A-001` |
| Q7 | `CoordinateQueue` expose thế nào (defer v1, xem scope freeze)? | TBD (PO) — defer | op thiếu transition/effect |
| Q8 | Giãn cách giữa 3 lần gọi bao lâu? | TBD (PO) | RULE-07-06 |
| Q9 | Đóng/mở quầy (`daily_queues.is_active`) op nào điều khiển? | TBD (PO) | ERD có cờ, ops không có op |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/queue-v1.yaml`](./openapi/queue-v1.yaml).
