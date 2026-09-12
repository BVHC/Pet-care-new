# Vaccination API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Vaccination Management (Module 10): lô vaccine (FEFO), tiêm an
> toàn quét mã vạch, lịch nhắc lại. Tiêm định kỳ KHÔNG cần bệnh án EMR; tiêm điều trị
> gắn `MedicalRecord`/`Treatment` (ranh giới RULE-10-07, xem clinical-v1).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#10`), `docs/02-business-rules.md`
> (RULE-10-01→07 + Safety Guard invariant), `docs/04-glossary.md` (`10`),
> `docs/05-domain-model.md` (`4.10`), `docs/06-erd.md` (`vaccine_batches`/
> `vaccinations`/`vaccination_schedules`). Không có FSM riêng (FEFO batch CONFIRMED).
> **Contract máy đọc:** [`./openapi/vaccination-v1.yaml`](./openapi/vaccination-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- `ManageVaccine` (danh mục vaccine) = quản trị product trong M05 (vì
  `vaccine_batches.vaccine_id → products.id`) — không endpoint riêng ở đây. Lưu ý:
  category ERD (`FOOD`/`MEDICINE`/…) không có `VACCINE` → TBD Q8.
- `SendVaccineReminder` là effect hệ thống (M23) sau tiêm/lập lịch — không endpoint.
- `VaccineBatch.status == ACTIVE` trong guard text là trạng thái suy ra
  (còn hạn + còn liều), ERD không có cột status → check derived, không field mới.
- `manufacturer` xuất hiện trong guard text + RULE-10-05 nhưng ERD không có cột →
  TBD Q9, v1 không thu/không trả trường này.

---

## A. Confirmed Vaccination API (7 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /stores/{id}/vaccine-batches` | `ManageVaccineBatch` — `01#10`, RULE-10-02 |
| 2 | `GET /stores/{id}/vaccine-batches` | `ManageVaccineExpiry` (giám sát) — `01#10`, RULE-10-02/04 |
| 3 | `GET /pets/{id}/vaccination-schedule` | `CheckVaccinationSchedule` + `ViewVaccinationSchedule` — `01#10`, RULE-10-01/03 |
| 4 | `POST /vaccinations/administer` | `AdministerVaccine` (quét mã + guard) — `01#10`, RULE-10-04/06 |
| 5 | `GET /pets/{id}/vaccinations` | (lịch sử mũi tiêm) |
| 6 | `POST /vaccinations/{id}/schedule-next` | `ScheduleNextVaccination` (thủ công) — `01#10`, RULE-10-04 |
| 7 | (auto) | `RecordVaccination` — là hiệu ứng trong administer (RULE-10-05), không endpoint riêng |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ManageVaccineBatch | InventoryStaff | Trong Store | RULE-10-02 (batch + NSX + HSD) | POST | `/stores/{id}/vaccine-batches` | Bearer | `INVENTORY_STAFF` | none | 409 trùng batch (A1) | `quantityInitial` = số liều nhập |
| ManageVaccineExpiry | InventoryStaff | — | RULE-10-02/04 (cấm dùng hết hạn/thu hồi) | GET | `/stores/{id}/vaccine-batches` | Bearer | Staff Store | none (read) | Idempotent | Filters PROPOSED: `expiredOnly?`, `expiringBefore?` |
| CheckVaccinationSchedule | Veterinarian | Trước tiêm | RULE-10-03 (tiền sử + phác đồ tuổi/loài + sàng lọc) | GET | `/pets/{id}/vaccination-schedule` | Bearer | Vet | none (read) | Idempotent | Phác đồ chuẩn theo tuổi/loài chưa số hóa → TBD Q7 |
| ViewVaccinationSchedule | Customer | Pet mình | RULE-10-01 | GET | `/pets/{id}/vaccinations`, `…/vaccination-schedule` | Bearer | Owner / caregiver | none | Idempotent | — |
| AdministerVaccine | Veterinarian | Sàng lọc đạt + guard 4 điểm pass | RULE-10-04/06 (store-match, còn hạn, ≥1 liều, hợp loài) | POST | `/vaccinations/administer` | Bearer | Vet | Ghi `VaccinationRecord` + trừ 1 liều + auto lịch nhắc | Non-idempotent (mỗi call 1 mũi — cấm retry mù, A2) | `scannedBarcode` → batch+vaccine (guard text); fail → `VACCINE_BATCH_EXPIRED` CONFIRMED |
| RecordVaccination | (effect) | Tiêm xong | RULE-10-05 (mã lô + HSD + bác sĩ + vị trí) | — | (trong administer) | — | — | + `VaccinationRecord` | — | `manufacturer` thiếu cột → Q9 |
| ScheduleNextVaccination | (auto + thủ công) | Sau tiêm | RULE-10-04/06 | POST | `/vaccinations/{id}/schedule-next` | Bearer | Vet | + lịch nhắc | Idempotent (1 lịch/mũi, A3) | `{nextDueDate}` PROPOSED (docs không cho shape) |

**ASSUMPTIONS dùng chung:** A1 `batchNumber` unique trong Store (ERD không UK tường
minh — derived chống nhập trùng) · A2 administer non-idempotent có chủ ý: mỗi call
thành công = 1 mũi thật, client cấm retry cùng barcode khi timeout (kết quả unknown →
tra lịch sử trước khi tiêm lại) · A3 1 lịch nhắc/mũi tiêm (ghi đè khi lập lại).

---

## C. Detailed endpoint contract

### C1. Batches (proposed)

- **`POST /stores/{id}/vaccine-batches`** — Request `{vaccineId (req: product M05),
  batchNumber (req), manufactureDate?, expiryDate (req), quantityInitial (req, ≥1)}`.
  Chỉ `INVENTORY_STAFF`. Response `201 {batchId, batchNumber, quantityRemaining}`.
  Status: `201` · `400` · `401` · `403` · `404` (product/vaccine) · `409` trùng batch (A1).
- **`GET /stores/{id}/vaccine-batches`** — Query PROPOSED: `expiredOnly?`,
  `expiringBefore? (date)`, `vaccineId?`. Response `200 {items: [{batchId,
  batchNumber, expiryDate, quantityInitial, quantityRemaining, expired (derived)}]}`.
  Cảnh báo low/expiry (RULE-12-12) là job nền M12 — ở đây chỉ đọc số liệu.

### C2. Schedules & history (proposed)

- **`GET /pets/{id}/vaccination-schedule`** — Vet: full (history + upcoming + tiền sử
  dị ứng theo RULE-10-01/03); customer: pets mình (RULE-10-01). Response
  `200 {history: [...], upcoming: [{scheduleId, vaccineId, nextDueDate}]}`.
  Phác đồ khuyến nghị theo tuổi/loài TBD Q7 (hiện chỉ trả lịch đã có).
- **`GET /pets/{id}/vaccinations`** — Lịch sử mũi tiêm (mã lô + HSD + bác sĩ + liều).
  Response `200 {items: [...]}` + page PROPOSED.

### C3. Administer (proposed)

- **`POST /vaccinations/administer`** — Request `{petId (req), storeId (req),
  scannedBarcode (req CONFIRMED — giải mã ra batch+vaccine), doseVolumeMl?
  (default 1.00 ERD), appointmentId?}`. Chỉ vet. Guard CONFIRMED (RULE-10-06, theo
  thứ tự): (1) batch thuộc Store; (2) còn hạn (`expiryDate > today`, ngược lại
  `VACCINE_BATCH_EXPIRED` + bắn `TriggerExpiryWarning`); (3) `quantityRemaining ≥ 1`;
  (4) hợp loài/thể trạng (phác đồ TBD Q7 — fail → `VACCINE_INCOMPATIBLE` PROPOSED).
  Pass 100% → ghi record + trừ 1 liều + auto lịch nhắc + effect nhắc M23 (chain
  RULE-10-06 steps 4–6). Response `201 {vaccinationId, petId, batchNumber,
  expiryDate, nextDueDate?}`. Status: `201` · `400` · `401` · `403` (non-vet) ·
  `404` (batch/pet) · `409` guard-fail · `410` hết hạn (`VACCINE_BATCH_EXPIRED`).
- **`POST /vaccinations/{id}/schedule-next`** — Request `{nextDueDate (req)}`
  (PROPOSED shape). Response `200 {scheduleId, vaccinationId, nextDueDate}` (A3).
  Status: `200` · `400` · `401` · `403` · `404`.

---

## D. Security & reliability

1. Guard 4 điểm chạy trong cùng transaction với trừ liều (không tiêm 2 lần cùng lô
   hết hàng cùng lúc → 409 khi `quantityRemaining` về 0).
2. Liều hết hạn/thu hồi không bao giờ tiêm được — check server-side, không dựa vào FE lọc.
3. Retry administer khi timeout: tra `GET vaccinations` trước (A2) — cấm đẻ key mới.

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `scannedBarcode` encode gì (batch thuần hay batch+vaccine)? Ai cấp mã? | TBD (PO/BE) | guard text: giải mã ra cả 2 |
| Q4 | `doseVolumeMl` mặc định theo vaccine hay luôn 1.00? | TBD (PO) | ERD default 1.00 |
| Q5 | Khám sàng lọc fail thì ghi ở đâu (có từ chối tiêm + lý do)? | TBD (PO) | RULE-10-03 bắt buộc khám, thiếu flow fail |
| Q6 | Liều cần nhiều hơn 1 đơn vị (puppy series cùng ngày)? | TBD (PO) | RULE-10-05 "trừ 1 liều" |
| Q7 | Phác đồ chuẩn tuổi/loài số hóa ở đâu (để check tương thích)? | TBD (PO) | RULE-10-03/06 |
| Q8 | Vaccine là `category` nào trong products (không có VACCINE)? | TBD (PO) | ERD categories |
| Q9 | `manufacturer` lưu ở đâu (guard text + RULE-10-05 cần, ERD thiếu cột)? | TBD (BE) | ERD thiếu |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/vaccination-v1.yaml`](./openapi/vaccination-v1.yaml).
