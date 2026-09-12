# Appointment API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Appointment & Scheduling (Module 06): giữ chỗ 15 phút,
> vòng đời lịch hẹn (FSM-4.2), kiểm tra xung đột 3 chiều, đổi lịch nguyên tử.
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#6`), `docs/02-business-rules.md`
> (RULE-06-01→14), `docs/03-state-machines.md` (FSM-4.1 BookingHold, FSM-4.2 Appointment),
> `docs/04-glossary.md` (`06`), `docs/05-domain-model.md` (`4.6`),
> `docs/06-erd.md` (`booking_holds`/`appointments`/`appointment_stage_histories`).
> **Contract máy đọc:** [`./openapi/appointment-v1.yaml`](./openapi/appointment-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ExpireHold`, `ReleaseStoreResource`, `ReleaseStaffSlot`, `SendAppointmentReminder`
  là system-internal/effects — không expose API.
- `ManageStoreSchedule`/`CoordinateSchedule` (điều phối lịch toàn Store) thuộc về
  Module 08 Workforce — ở đây chỉ có `AssignStaff` gắn staff vào appointment cụ thể.
- `CheckAvailability` là op của System — expose dạng read `GET …/availability`
  phục vụ FE chọn slot (derived read, không thêm hiệu ứng).
- Walk-in tạo appointment nội bộ qua cầu nối M07 (`channel=WALK_IN` + `queue_entry_id`
  bắt buộc theo CHECK constraint) — không tạo trực tiếp qua `POST /appointments`
  với channel WALK_IN (server từ chối, A1).
- Chính sách cọc/hoàn-phạt cọc (RULE-06-05/09) chưa số hóa → TBD Q8; contract chỉ chốt
  `cancellation_reason` bắt buộc + giải phóng tài nguyên.

---

## A. Confirmed Appointment API (15 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /holds` | `HoldSlot` (TTL 15m) — `01#6`, RULE-06-01 |
| 2 | `POST /holds/{id}/release` | `ReleaseHold` — `01#6`, RULE-06-01 |
| 3 | `POST /appointments` | `BookAppointment` (→ BOOKED) — `01#6`, RULE-06-01/02/10/11 |
| 4 | `GET /appointments` | `ViewAppointment` (list, phân quyền RULE-06-14) |
| 5 | `GET /appointments/{id}` | `ViewAppointment` (detail) |
| 6 | `POST /appointments/{id}/confirm` | `ConfirmAppointment` (→ CONFIRMED) — RULE-06-01/03/10 |
| 7 | `PATCH /appointments/{id}` | `UpdateAppointment` (trước check-in) — RULE-06-03 |
| 8 | `POST /appointments/{id}/reschedule` | `RescheduleAppointment` (nguyên tử → BOOKED) — RULE-06-03/04/10/11 |
| 9 | `POST /appointments/{id}/cancel` | `CancelAppointment` (→ CANCELLED) — RULE-06-05 |
| 10 | `POST /appointments/{id}/check-in` | `CheckInAppointment` (→ CHECKED_IN) — RULE-06-06 |
| 11 | `POST /appointments/{id}/start-service` | `StartAppointmentService` (→ IN_PROGRESS) — RULE-06-06 |
| 12 | `POST /appointments/{id}/check-out` | `CheckOutAppointment` (→ COMPLETED) — RULE-06-06/07 |
| 13 | `POST /appointments/{id}/no-show` | `MarkNoShow` (→ NO_SHOW) — RULE-06-09 |
| 14 | `POST /appointments/{id}/abort` | `AbortAppointment` (→ ABORTED) — RULE-06-08 |
| 15 | `POST /appointments/{id}/assign-staff` | `AssignStaff` — `01#6`, RULE-06-12 |
| 16 | `GET /stores/{id}/availability` | `CheckAvailability` (read) — `01#6`, RULE-06-10/11 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| HoldSlot | Customer / Receptionist | Slot còn trống | RULE-06-01 (TTL 900s) | POST | `/holds` | Bearer | Owner intent | `[*] → HOLDING` + `SlotHeld` (`expires_at=+15m`) | Mỗi call là hold mới (non-idempotent) | Khóa resource+staff+pet 15m |
| ReleaseHold | Customer / Receptionist | Hold còn `HOLDING` | RULE-06-01 | POST | `/holds/{id}/release` | Bearer | Owner hold | `HOLDING → RELEASED` | Idempotent | — |
| BookAppointment | Customer/Receptionist/Caregiver | 4 điều kiện RULE-06-02 | RULE-06-01/02/10/11 (triple guard) | POST | `/appointments` | Bearer | Quyền trên Pet (owner/caregiver ACTIVE) | `[*] → BOOKED` + `AppointmentBooked` (hold dùng → CONFIRMED hold) | Non-idempotent (slot tiêu thụ) | Fail guard nào → 409 + code tương ứng (`RESOURCE_CAPACITY_EXCEEDED` / `PET_SCHEDULE_COLLISION` CONFIRMED) |
| ViewAppointment | Customer/Caregiver/Staff/Manager | — | RULE-06-14 (customer chỉ pet mình; staff all-store) | GET | `/appointments…` | Bearer | Scope theo role | none | Idempotent | — |
| ConfirmAppointment | Receptionist (/System) | `BOOKED` + đủ resource/staff | RULE-06-01/03/10 | POST | `…/confirm` | Bearer | Receptionist (System auto-confirm TBD Q9) | `BOOKED → CONFIRMED` | Idempotent | — |
| UpdateAppointment | Receptionist | Trước check-in | RULE-06-03 | PATCH | `/appointments/{id}` | Bearer | Receptionist | none (đổi service/notes) | Idempotent | Đổi giờ → dùng reschedule, không PATCH |
| RescheduleAppointment | Customer / Receptionist | `BOOKED`/`CONFIRMED` | RULE-06-03/04/10/11 (atomic swap + rollback) | POST | `…/reschedule` | Bearer | Owner booking / receptionist | `BOOKED/CONFIRMED → BOOKED` + `AppointmentRescheduled` | Non-idempotent (giữ slot mới) | Thất bại → giữ nguyên lịch cũ (rollback CONFIRMED) |
| CancelAppointment | Customer / Receptionist | `BOOKED`/`CONFIRMED`/`CHECKED_IN` | RULE-06-05 (`cancellation_reason` bắt buộc + release + cọc theo policy TBD Q8) | POST | `…/cancel` | Bearer | Owner / receptionist | `→ CANCELLED` + `AppointmentCancelled` | Idempotent | — |
| CheckInAppointment | Receptionist | `BOOKED`/`CONFIRMED` | RULE-06-06 | POST | `…/check-in` | Bearer | Receptionist | `→ CHECKED_IN` | Idempotent | Từ `BOOKED` thẳng được (FSM CONFIRMED) |
| StartAppointmentService | Vet / Groomer | `CHECKED_IN` | RULE-06-06 (+ chuyên môn 09/11) | POST | `…/start-service` | Bearer | Staff chuyên môn được phân công | `CHECKED_IN → IN_PROGRESS` | Idempotent | — |
| CheckOutAppointment | Receptionist | `IN_PROGRESS` + phiên chuyên môn xong | RULE-06-06/07 (cấm nhảy `CHECKED_IN→COMPLETED`) | POST | `…/check-out` | Bearer | Receptionist | `IN_PROGRESS → COMPLETED` + handoff `CreateInvoice` | Idempotent | COMPLETED/CANCELLED bất biến |
| MarkNoShow | Receptionist (/System) | Quá grace period, `BOOKED`/`CONFIRMED` | RULE-06-09 (release + trừ cọc theo policy TBD Q8) | POST | `…/no-show` | Bearer | Receptionist | `→ NO_SHOW` | Idempotent | Grace period PROPOSED 15m (docs "ví dụ", TBD Q7) |
| AbortAppointment | Vet / Groomer | `IN_PROGRESS` + nguy cơ | RULE-06-08 (`abort_reason` bắt buộc + incident + hoàn phần chưa làm) | POST | `…/abort` | Bearer | Staff chuyên môn | `IN_PROGRESS → ABORTED` + `AppointmentAborted` | Idempotent | Kích hoạt incident M21 |
| AssignStaff | StoreManager | Staff thuộc Store + rảnh + đúng role | RULE-06-12 (+ va chạm lịch M08) | POST | `…/assign-staff` | Bearer | StoreManager | none | Idempotent | — |
| CheckAvailability | (System/FE) | — | RULE-06-10/11 | GET | `/stores/{id}/availability` | Bearer | Trong scope | none (read) | Idempotent | Params PROPOSED (Q10) |

**ASSUMPTIONS dùng chung:** A1 `POST /appointments` từ chối `channel=WALK_IN`
(cầu nối M07 tạo nội bộ; CHECK constraint ERD) · A2 `POST /appointments` nhận
`holdId?` để tiêu thụ hold (docs nối Hold→Book nhưng không chốt cơ chế, TBD Q11) ·
A3 grace period No-Show = 15m (docs "ví dụ: 15 phút").

---

## C. Detailed endpoint contract

### C1. Holds (proposed)

- **`POST /holds`** — Request `{storeId (req), petId (req), serviceId (req),
  staffId?, resourceId?, startTime (req), endTime (req)}`.
  Guards: slot trống (staff + resource + pet theo RULE-06-10/11 — hold đã khóa thì
  hold sau → `409`). Response `201 {holdId, status: "HOLDING", expiresAt (+15m)}`.
  Status: `201` · `400` · `401` · `403` · `409` (`RESOURCE_CAPACITY_EXCEEDED` /
  `PET_SCHEDULE_COLLISION` CONFIRMED).
- **`POST /holds/{id}/release`** — Response `200 {holdId, status: "RELEASED"}`.
  Hết hạn tự `EXPIRED` (job nền, không endpoint).

### C2. Booking (proposed)

- **`POST /appointments`** — Request `{storeId, petId, serviceId, staffId?,
  startTime, endTime, channel? (`ONLINE`/`PHONE`, default `ONLINE`; `WALK_IN` bị từ
  chối — A1), holdId? (A2), notes?}`. Guards 4 điều kiện RULE-06-02 CONFIRMED:
  giờ mở cửa (RULE-03-07) · service ACTIVE tại Store · quyền trên Pet ·
  triple guard. Response `201 {appointmentId, appointmentNumber, status: "BOOKED"}`.
  Status: `201` · `400` · `401` · `403` (pet ngoài quyền) · `404` · `409` guard-fail.
- **`GET /appointments`** — Scope RULE-06-14 CONFIRMED: customer/caregiver chỉ pet
  mình; staff/manager all-store. Query PROPOSED: `page`, `pageSize`, `status?`,
  `petId?`, `from?`, `to?`.

### C3. Lifecycle actions (proposed)

- **`…/confirm`** — `{}`. Từ `BOOKED`, đủ resource/staff → `CONFIRMED`.
- **`PATCH /appointments/{id}`** — `{serviceId?, notes?, staffId?}` (trước check-in;
  đổi giờ phải reschedule). Status: `200` · `400` · `401` · `403` · `404` · `409`.
- **`…/reschedule`** — `{newStartTime (req), newEndTime (req)}`. Atomic CONFIRMED:
  khóa slot mới trước, xong mới thả cũ + về `BOOKED`; fail → rollback giữ lịch cũ.
- **`…/cancel`** — `{cancellationReason (req CONFIRMED)}`. Từ
  `BOOKED`/`CONFIRMED`/`CHECKED_IN`. Release resource+staff CONFIRMED; cọc TBD Q8.
- **`…/check-in`**, **`…/start-service`**, **`…/check-out`** — `{}` (check-out có thể
  `{actualEndTime?}` PROPOSED). Check-out handoff sang `CreateInvoice` (M15).
- **`…/no-show`** — `{}`. Quá grace (A3) + `BOOKED`/`CONFIRMED`. Release + phạt cọc TBD Q8.
- **`…/abort`** — `{abortReason (req CONFIRMED)}`. Chỉ `IN_PROGRESS`. Auto tạo
  incident (M21) + hoàn phần chưa làm (M17) — cross-domain effects.
- **`…/assign-staff`** — `{staffId (req)}`. Guards RULE-06-12: thuộc Store, đúng
  vai trò chuyên môn, lịch trống (va chạm lịch chi tiết thuộc M08).
- **Status chung actions:** `200` · `400` · `401` · `403` · `404` · `409` sai trạng thái.

### C4. Availability read (proposed)

- **`GET /stores/{id}/availability`** — Query PROPOSED (Q10): `serviceId (req)`,
  `from (req)`, `to (req)`. Response `200 {slots: [{startTime, endTime, available,
  staffId?, resourceId?}]}` (shape PROPOSED). Không giữ chỗ — chỉ đọc.

---

## D. Security & reliability

1. Triple guard (staff + resource + pet) enforce server-side trong cùng transaction
   khi hold/book (chống double-book).
2. Reschedule atomic + rollback (RULE-06-04) — test bắt buộc.
3. Cấm `CHECKED_IN → COMPLETED` (invariant FSM-4.2 CONFIRMED).
4. `appointmentNumber` unique (ERD); book trùng do đồng thời → 409 `CONCURRENCY_CONFLICT`.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `appointmentNumber` format/sequence theo Store hay toàn hệ thống? | TBD (BE) | ERD (có cột, thiếu format) |
| Q4 | `channel=PHONE` ai tạo (receptionist?) và khác gì ONLINE? | TBD (PO) | ERD có channel, ops không phân biệt |
| Q5 | `actual_start/end_time` ai ghi (staff tự bấm hay receptionist)? | TBD (PO) | ERD có cột, ops không nêu |
| Q6 | Hủy sau check-in có khác hủy trước (phí đến trễ)? | TBD (PO) | RULE-06-05 chung |
| Q7 | Grace period No-Show chốt bao nhiêu (A3 PROPOSED 15m)? | TBD (PO) | docs "ví dụ: 15 phút" |
| Q8 | Chính sách cọc/phạt cọc/hoàn cọc số hóa ở đâu? | TBD (PO) | RULE-06-05/09 nhắc, thiếu số |
| Q9 | System auto-confirm khi nào (hay luôn cần receptionist)? | TBD (PO) | `01#6` ghi "Receptionist / System" |
| Q10 | Params/shape `availability` | TBD (BE/FE) — PROPOSED | op của System, thiếu shape |
| Q11 | `holdId` tiêu thụ khi book bằng cơ chế nào (A2)? | TBD (BE) | docs nối Hold→Book, thiếu cơ chế |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/appointment-v1.yaml`](./openapi/appointment-v1.yaml).
