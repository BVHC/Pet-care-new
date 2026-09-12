# Workforce API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Workforce Management (Module 08): phân ca, nghỉ phép, vắng mặt,
> thay thế. Hồ sơ account/role của staff thuộc [`iam-v1.md`](./iam-v1.md), không định
> nghĩa lại (xem scope freeze).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#8`), `docs/02-business-rules.md`
> (RULE-08-01→07 + Staff Schedule Collision Guard), `docs/04-glossary.md` (`08`),
> `docs/05-domain-model.md` (`4.8`), `docs/06-erd.md` (`staff_work_schedules`/
> `shift_assignments`/`staff_absences`). Không có FSM riêng (status đơn giản CONFIRMED
> theo ma trận đối soát ERD §5).
> **Contract máy đọc:** [`./openapi/workforce-v1.yaml`](./openapi/workforce-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `CreateStaff` thuộc auth-v1; `ManageUser`/role thuộc iam-v1. Ở đây `ManageStaff`
  chỉ bao phủ: list staff tại Store + `staff_code` + phân công Store
  (`AssignStaffToStore`) + lịch/nghỉ/vắng/thay thế.
- `UpdateStaff` (hồ sơ vận hành) giới hạn field workforce (`staffCode`) — field
  cá nhân (name/avatar) thuộc iam-v1 `PATCH /users/{id}`.

---

## A. Confirmed Workforce API (10 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `GET /stores/{id}/staff` | `ManageStaff` (list tại Store) — `01#8`, RULE-08-01/02 |
| 2 | `PATCH /staff/{id}` | `UpdateStaff` (workforce fields) — `01#8` |
| 3 | `POST /staff/{id}/store-assignment` | `AssignStaffToStore` — `01#8`, RULE-08-02 |
| 4 | `POST /stores/{id}/schedules` | `ManageWorkSchedule` (tạo ngày phân ca) — `01#8`, RULE-08-03 |
| 5 | `POST /schedules/{id}/shifts` | `ManageWorkSchedule` (thêm ca) — `01#8`, RULE-08-03 |
| 6 | `GET /stores/{id}/schedules` | (xem lịch Store — StoreManager all) |
| 7 | `GET /staff/{id}/schedule` | `ViewWorkSchedule` — `01#8`, RULE-08-07 |
| 8 | `POST /staff/{id}/leaves` | (xin nghỉ — `ManageLeave` flow) — `01#8`, RULE-08-06 |
| 9 | `POST /leaves/{id}/approve`, `POST /leaves/{id}/reject` | `ManageLeave` (duyệt) — `01#8`, RULE-08-06 |
| 10 | `POST /staff/{id}/absences` | `HandleStaffAbsence` — `01#8`, RULE-08-04 |
| 11 | `POST /absences/{id}/replacement` | `AssignStaffReplacement` — `01#8`, RULE-08-05 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ManageStaff | StoreManager / OrgAdmin | — | RULE-08-01/02 | GET | `/stores/{id}/staff` | Bearer | Manager trong scope | none | Idempotent | — |
| UpdateStaff | StoreManager | Staff thuộc Store | — | PATCH | `/staff/{id}` | Bearer | Manager Store đó | none | Idempotent | Chỉ `staffCode` (A1) |
| AssignStaffToStore | StoreManager | Staff cùng Org + Store tồn tại | RULE-08-02 (điều động trong Org, hiệu lực tại Store phân công) | POST | `/staff/{id}/store-assignment` | Bearer | Manager (target Store) | none | Idempotent | Một staff nhiều Store? ERD `users.store_id` đơn — TBD Q7 |
| ManageWorkSchedule | StoreManager | Không trùng nghỉ phép đã duyệt (RULE-08-03) | Collision guard (leave/absence/shift) | POST | `/stores/{id}/schedules`, `/schedules/{id}/shifts` | Bearer | StoreManager | none | POST shift trùng → 409 | Ca `MORNING`/`AFTERNOON`/`FULL_DAY` CONFIRMED |
| ViewWorkSchedule | Staff / Manager | — | RULE-08-07 (staff chỉ mình; manager all-store) | GET | `/staff/{id}/schedule`, `/stores/{id}/schedules` | Bearer | Owner / manager | none | Idempotent | — |
| ManageLeave | Staff (xin) / Manager (duyệt) | — | RULE-08-06 (chỉ `APPROVED` hiệu lực; khóa giờ + giải phóng slot chưa phân công) | POST | `/staff/{id}/leaves`, `/leaves/{id}/approve|reject` | Bearer | Owner xin; manager duyệt | `PENDING → APPROVED/REJECTED` | Idempotent | Types `SICK`/`VACATION`/`UNPAID` CONFIRMED |
| HandleStaffAbsence | StoreManager | Trong ca | RULE-08-04 (đánh vắng + cảnh báo hẹn/ca + khóa phân công mới) | POST | `/staff/{id}/absences` | Bearer | StoreManager | none (ghi nhận) | Non-idempotent (mỗi ghi nhận 1 bản ghi) | Cảnh báo appointments ảnh hưởng là effect (không endpoint) |
| AssignStaffReplacement | StoreManager | Người thay: cùng Store, cùng role, lịch FREE | RULE-08-05 (3 điều kiện CONFIRMED) | POST | `/absences/{id}/replacement` | Bearer | StoreManager | none | Idempotent | Fail điều kiện nào → 409 + code tương ứng |

**ASSUMPTIONS dùng chung:** A1 `PATCH /staff/{id}` chỉ `staffCode` (field workforce
duy nhất trong ERD `users`; các field khác thuộc IAM) · A2 xin nghỉ do chính staff
(docs: "đơn xin nghỉ phép của nhân viên" + manager duyệt) · A3 approve tự động khóa
giờ nghỉ + giải phóng slot chưa phân công (RULE-08-06 CONFIRMED hiệu ứng).

---

## C. Detailed endpoint contract

### C1. Staff (proposed)

- **`GET /stores/{id}/staff`** — Response `200 {items: [{userId, name, role,
  staffCode}]}`. Status: `200` · `401` · `403` · `404`.
- **`PATCH /staff/{id}`** — Request `{staffCode?}` (A1). Status:
  `200` · `400` · `401` · `403` · `404`.
- **`POST /staff/{id}/store-assignment`** — Request `{storeId (req)}`. Guards:
  cùng Organization (RULE-08-02). Response `200 {userId, storeId}`.
  Đa-Store song song TBD Q7. Status: `200` · `400` · `401` · `403` · `404` · `409`.

### C2. Schedules & leaves (proposed)

- **`POST /stores/{id}/schedules`** — Request `{scheduleDate (req)}`. Response
  `201 {scheduleId, storeId, scheduleDate}`. Trùng ngày → `409` (PROPOSED).
- **`POST /schedules/{id}/shifts`** — Request `{staffId (req), shiftType (req:
  `MORNING`/`AFTERNOON`/`FULL_DAY` CONFIRMED), startTime (req), endTime (req)}`.
  Guards CONFIRMED (collision guard): không giao thoa approved-leave / recorded-absence /
  assigned-shift → vi phạm trả `409` + code tương ứng (`STAFF_ON_LEAVE` /
  `STAFF_ABSENT` / `STAFF_SHIFT_CONFLICT` — codes CONFIRMED trong text RULE-08).
  Response `201 {shiftId, …}`.
- **`GET /stores/{id}/schedules?date=`**, **`GET /staff/{id}/schedule?from&to`** —
  Scope RULE-08-07 CONFIRMED (staff chỉ mình). Response `200 {items: […]}`.
- **`POST /staff/{id}/leaves`** — Request `{absenceType (req: `SICK`/`VACATION`/`UNPAID`
  CONFIRMED), startDate (req), endDate (req)}` (A2). Response `201 {leaveId,
  status: "PENDING"}`.
- **`POST /leaves/{id}/approve`** — Từ `PENDING` → `APPROVED` (+ khóa giờ + giải
  phóng slot — A3). **`POST /leaves/{id}/reject`** — → `REJECTED`.
  Status: `200` · `401` · `403` (chỉ manager) · `404` · `409` sai trạng thái.

### C3. Absence & replacement (proposed)

- **`POST /staff/{id}/absences`** — Request `{absenceDate (req), shiftId?,
  note?}` (shape PROPOSED — ERD không có bảng absence riêng: dùng chung bảng
  `staff_absences` cho cả leave + absence
  (`status` PENDING/APPROVED/REJECTED); absence đột xuất ghi với `status=APPROVED`
  + `approved_by` là manager ghi nhận — A4, TBD Q8). Response `201 {absenceId}`.
- **`POST /absences/{id}/replacement`** — Request `{replacementStaffId (req)}`.
  Guards CONFIRMED (RULE-08-05): (1) cùng Store/điều động hợp lệ; (2) cùng role +
  năng lực; (3) FREE toàn khung. Response `200 {absenceId, replacementStaffId}`.
  Status: `200` · `400` · `401` · `403` · `404` · `409` + code (`STAFF_ON_LEAVE` /
  `STAFF_ABSENT` / `STAFF_SHIFT_CONFLICT` / `STAFF_ROLE_MISMATCH` PROPOSED cho (2)).

---

## D. Security & reliability

1. Mọi phân công ca/hẹn check collision guard server-side (RULE-08-03 + guard text).
2. Nghỉ đã duyệt khóa giờ ngay — appointment modules phải check leave khi assign
   (cross-module dependency, M06 đã ghi nhận va chạm lịch M08).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `shiftType` có đi kèm giờ mặc định không (MORNING = mấy giờ)? | TBD (PO) | ERD có cả type + start/end |
| Q4 | Xin nghỉ cho người khác (manager tạo hộ) được không (A2)? | TBD (PO) | docs: đơn của nhân viên |
| Q5 | Từ chối nghỉ có cần lý do không? | TBD (PO) | docs không nêu |
| Q6 | Cảnh báo appointments khi absence bắn cho ai, kênh nào? | TBD (PO) | RULE-08-04 (effect, thiếu chi tiết) |
| Q7 | Staff nhiều Store song song hay đổi Store (A-`store_id` đơn)? | TBD (PO/BE) | ERD `users.store_id` đơn vs RULE-08-02 điều động |
| Q8 | `staff_absences` dùng chung leave+absence (A4)? Bảng riêng? | TBD (BE) | ERD một bảng, ops hai flows |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/workforce-v1.yaml`](./openapi/workforce-v1.yaml).
