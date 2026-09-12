# Clinical API v1 — Thiết kế từ `docs/` (Source of Truth)

> **Phạm vi:** Domain Veterinary / Clinical Management (Module 09): bệnh án điện tử,
> khám/chẩn đoán/kê đơn/tái khám + đồng thuận xem bệnh án liên Store 2 cơ chế
> (OTP chuẩn / Emergency Override).
> **Nguồn chân lý:** `docs/01-business-operations.md` (`01#9`), `docs/02-business-rules.md`
> (RULE-09-01→04 + RULE-09-06/08 theo domain), `docs/03-state-machines.md` (không FSM
> riêng cho MedicalRecord CONFIRMED theo ma trận ERD §5; consent FSM-16),
> `docs/04-glossary.md` (`09`), `docs/05-domain-model.md` (`4.9`),
> `docs/06-erd.md` (`medical_records`/`diagnoses`/`prescriptions`/
> `prescription_items`/`cross_store_consents`).
> **Contract máy đọc:** [`./openapi/clinical-v1.yaml`](./openapi/clinical-v1.yaml).

**Legend:** `CONFIRMED` = có trong docs · `ASSUMPTION (A#)` = suy luận ít-nhất ·
`TBD (Q#)` = cần PO quyết định (xem mục E). Mọi method/path/shape là **PROPOSED**.
Envelope DECIDED theo convention `04-exception-handling`.

**Đóng băng phạm vi:**
- Tiêm vaccine **định kỳ** thuộc Module 10 — ở đây chỉ nhận `vaccine_id` khi là
  vaccine điều trị gắn `Treatment` (ranh giới RULE-09-08).
- Xem lịch sử liên Store bắt buộc qua consent (OTP 24h) hoặc emergency — không có
  "xem trực tiếp" cross-store (RULE-09-02 CONFIRMED).
- `is_locked`/`locked_at` trong ERD ghi "đóng băng sau 24h (RULE-09-06)" nhưng
  RULE-09-06 hiện hành là nghĩa vụ tái khám + ma trận ERD chốt "không có trạng thái
  khóa bất biến theo giờ" → mâu thuẫn nội bộ docs, TBD Q9; v1 cho sửa không khóa.

---

## A. Confirmed Clinical API (13 endpoints)

| # | Endpoint (proposed) | Business operation (CONFIRMED) |
|---|---|---|
| 1 | `POST /medical-records` | `ExaminePet` + `CreateMedicalRecord` (mở phiên khám) — `01#9`, RULE-09-01 |
| 2 | `GET /medical-records/{id}` | `ViewMedicalHistory` (1 bệnh án) — `01#9` |
| 3 | `GET /pets/{id}/medical-history` | `ViewMedicalHistory` (lịch sử Pet) — `01#9`, RULE-09-02 |
| 4 | `PATCH /medical-records/{id}` | `UpdateMedicalRecord` — `01#9` |
| 5 | `POST /medical-records/{id}/symptoms` | `RecordSymptom` — `01#9` |
| 6 | `POST /medical-records/{id}/examination-results` | `RecordExaminationResult` — `01#9` |
| 7 | `POST /medical-records/{id}/diagnoses` | `DiagnosePet` (gắn phiên khám) — `01#9`, RULE-09-03 |
| 8 | `POST /medical-records/{id}/treatments` | `CreateTreatment` — `01#9`, RULE-09-04 |
| 9 | `POST /medical-records/{id}/prescriptions` | `CreatePrescription` — `01#9` |
| 10 | `POST /medical-records/{id}/follow-ups` | `CreateFollowUp` — `01#9`, RULE-09-06 |
| 11 | `POST /consents/cross-store-requests` | `RequestCrossStoreConsent` — `01#9`, RULE-09-02 |
| 12 | `POST /consents/cross-store-verify` | `VerifyCrossStoreConsentOTP` (mở 24h) — `01#9`, RULE-09-02 |
| 13 | `POST /consents/{id}/revoke` | `RevokeCrossStoreConsent` — `01#9`, RULE-22-03 (theo domain 4.22) |
| 14 | `POST /consents/emergency-override` | `EmergencyOverrideAccess` — `01#9`, RULE-09-02 |

---

## B. API Design Matrix

| Operation | Actor | Preconditions | Business Rules | Method | Endpoint (proposed) | Auth | Authorization | State Transition | Idempotency | Notes / Trace |
|---|---|---|---|---|---|---|---|---|---|---|
| ExaminePet (+CreateMedicalRecord) | Veterinarian (phân công tại Store) | Pet + appointment/store hợp lệ | RULE-09-01 (chỉ vet) | POST | `/medical-records` | Bearer | `VETERINARIAN` | `[*] → record mở` + `MedicalRecordCreated` | Non-idempotent (mỗi phiên 1 record) | `CreateMedicalRecord` từ domain 4.9 (ops không tách op riêng) |
| ViewMedicalHistory | Vet / Customer / Caregiver | Cùng Store HOẶC consent/emergency | RULE-09-01/02 | GET | `/medical-records/{id}`, `/pets/{id}/medical-history` | Bearer | Vet (scope) / owner / caregiver được chia sẻ | none | Idempotent | Cross-store không consent → 403 |
| UpdateMedicalRecord | Veterinarian | Record tồn tại | (khóa 24h TBD Q9) | PATCH | `/medical-records/{id}` | Bearer | Vet điều trị | none | Idempotent | — |
| RecordSymptom / RecordExaminationResult | Veterinarian | Record tồn tại | RULE-09-01 | POST | `…/symptoms`, `…/examination-results` | Bearer | Vet | none | Non-idempotent (ghi nhận mới) | — |
| DiagnosePet | Veterinarian | Gắn phiên khám cụ thể | RULE-09-03 | POST | `…/diagnoses` | Bearer | Vet | none | Non-idempotent | Chẩn đoán làm căn cứ Treatment |
| CreateTreatment | Veterinarian | Thuộc record tương ứng | RULE-09-04 | POST | `…/treatments` | Bearer | Vet | none | Non-idempotent | — |
| CreatePrescription | Veterinarian | Thuộc record/treatment | — | POST | `…/prescriptions` | Bearer | Vet | none | Non-idempotent | Items: product + liều/tần suất/ngày (ERD) |
| CreateFollowUp | Veterinarian | Sau điều trị | RULE-09-06 (nghĩa vụ lập tái khám) | POST | `…/follow-ups` | Bearer | Vet | none | Non-idempotent | Ngày tái khám → nhắc hẹn (effect M23) |
| RequestCrossStoreConsent | Veterinarian (Store khác, cùng Org) | Pet có hồ sơ gốc | RULE-09-02 cơ chế 1 | POST | `/consents/cross-store-requests` | Bearer | Vet | `[*] → REQUESTED` + OTP 5 phút gửi chủ Pet | Non-idempotent (mỗi request 1 OTP) | Scope `ALL_STORES`/`SPECIFIC_STORE` CONFIRMED (ERD) |
| VerifyCrossStoreConsentOTP | Customer / Receptionist | OTP còn hạn | RULE-09-02 (`OTP_TTL=300s` → quyền 24h `Consent_TTL`) | POST | `/consents/cross-store-verify` | Bearer? (A1) | Người giữ OTP (chủ Pet) | `REQUESTED → ACTIVE (24h)` | Idempotent (đã ACTIVE trả grant cũ) | `purpose=CONSENT` fix server-side (ERD) |
| RevokeCrossStoreConsent | Customer | Grant `ACTIVE` | Thu hồi tức thì, bất kỳ lúc nào | POST | `/consents/{id}/revoke` | Bearer | Chủ Pet | `ACTIVE → REVOKED` | Idempotent | — |
| EmergencyOverrideAccess | Veterinarian | Nguy kịch + lý do lâm sàng bắt buộc | RULE-09-02 cơ chế 2 (`is_emergency=true` + incident + audit + cảnh báo) | POST | `/consents/emergency-override` | Bearer | Vet | `[*] → ACTIVE (emergency)` | Non-idempotent (mỗi lần 1 biên bản) | Auto: ClinicalIncident + audit + notify (effects M21/M23/M25) |

**ASSUMPTIONS dùng chung:** A1 verify OTP consent yêu cầu đăng nhập (người nhập là
chủ Pet/receptionist hỗ trợ — theo glossary "Customer hoặc Receptionist xác thực") ·
A2 `appointmentId` optional khi mở record (ERD nullable) — khám ngoài lịch cho phép ·
A3 follow-up gồm `followUpDate` bắt buộc + `note?` (docs: lập lịch tái khám, thiếu shape).

---

## C. Detailed endpoint contract

### C1. Records (proposed)

- **`POST /medical-records`** — Request `{petId (req), storeId (req),
  appointmentId? (A2), veterinarianId? (mặc định caller), recordNumber? (A4:
  BE sinh nếu thiếu), chiefComplaint (req CONFIRMED), clinicalSigns?,
  temperatureCelsius?, weightKg (req CONFIRMED), treatmentPlan?}`.
  Chỉ `VETERINARIAN`. Response `201 {recordId, petId, recordNumber}`.
  Status: `201` · `400` · `401` · `403` (non-vet) · `404`.
- **`GET /medical-records/{id}`** — Guards: vet trong Store sở hữu HOẶC grant
  cross-store `ACTIVE` còn hạn HOẶC `is_emergency` grant. Ngoài ra → `403`.
- **`GET /pets/{id}/medical-history`** — List records của Pet (cùng guard).
  Response `200 {items: [...]}`. Query PROPOSED: `page`, `pageSize`.
- **`PATCH /medical-records/{id}`** — Request `{chiefComplaint?, clinicalSigns?,
  treatmentPlan?, weightKg?}`. Không khóa 24h ở v1 (TBD Q9). Status chuẩn.

### C2. Clinical writes (proposed)

- **`…/symptoms`** — `{description (req), …?}` → `201 {symptomId}`.
- **`…/examination-results`** — `{resultType?, description (req), …?}` → `201`.
- **`…/diagnoses`** — `{diagnosticCode (req CONFIRMED), description (req)}` → `201`.
- **`…/treatments`** — `{description (req), therapeuticVaccineId? (ranh giới
  RULE-09-08: chỉ vaccine điều trị mới gắn đây)}` → `201 {treatmentId}`.
- **`…/prescriptions`** — `{items: [{productId (req), quantity (req), dosage (req),
  frequency (req), durationDays (req), instructions?}]}` (fields CONFIRMED ERD) → `201`.
- **`…/follow-ups`** — `{followUpDate (req, A3), note?}` → `201`. Effect: nhắc hẹn M23.
- Tất cả: vet điều trị; Status `201` · `400` · `401` · `403` · `404`.

### C3. Consent (proposed)

- **`POST /consents/cross-store-requests`** — Request `{petId (req),
  requestingStoreId (req), sourceStoreId? (null = toàn chuỗi),
  scopeType (req: `ALL_STORES`/`SPECIFIC_STORE` CONFIRMED)}`.
  Chỉ vet. Effect: OTP 5 phút tới SĐT/app chủ Pet. Response
  `201 {consentId, status: "REQUESTED", expiresAt}`.
- **`POST /consents/cross-store-verify`** — Request `{phone (req), otpCode (req,
  6 số — cùng VO auth)}`. Guards: đúng phone chủ Pet, OTP `purpose=CONSENT` còn hạn
  chưa dùng. Response `200 {consentId, status: "ACTIVE", expiresAt (+24h CONFIRMED)}`.
  Status: `200` · `400` · `401` sai OTP còn lượt · `404` · `410` hết hạn · `409` đã ACTIVE.
- **`POST /consents/{id}/revoke`** — Chỉ chủ Pet. Tức thì CONFIRMED.
  Response `200 {consentId, status: "REVOKED"}`.
- **`POST /consents/emergency-override`** — Request `{petId (req), storeId (req),
  emergencyReason (req CONFIRMED bắt buộc)}`. Chỉ vet. Effects CONFIRMED:
  grant `ACTIVE` (`is_emergency=true`) + `ClinicalIncident` (M21) + audit bất biến
  (M25) + cảnh báo chủ Pet + StoreManager (M23). Response `201 {consentId,
  status: "ACTIVE", isEmergency: true}`.
- **Status chung consent:** `200`/`201` · `400` · `401` · `403` · `404` · `409` · `410`.

---

## D. Security & reliability

1. Mọi write lâm sàng vet-only server-side (RULE-09-01); cross-store read bắt buộc
   grant/emergency (RULE-09-02) — không có đường tắt.
2. Emergency override luôn để lại 3 dấu vết (incident + audit + notify); thiếu một
   là lỗi hệ thống, không phải "tính năng".
3. OTP consent tách hẳn OTP đăng ký (`purpose` fix server-side, không expose).

---

## E. Open Questions / Decision Log

| ID | Câu hỏi | Trạng thái | Gốc |
|---|---|---|---|
| Q1 | Base URL + versioning | TBD (PO, chung) | — |
| Q2 | Envelope | DECIDED theo convention | convention |
| Q3 | `recordNumber` format + ai sinh (A4: BE sinh)? | TBD (BE) | ERD có cột, thiếu format |
| Q4 | Symptom/exam-result có danh mục chuẩn (ICD?) hay text tự do? | TBD (PO) | ERD text, `diagnostic_code` có mã |
| Q5 | Đơn thuốc có cần duyệt/phát thuốc qua kho (trừ tồn khi kê)? | TBD (PO) | docs không nối prescription→inventory |
| Q6 | Chủ Pet xem bệnh án có cần consent khi cùng Store? (Không — owner mặc định) | DECIDED (hiển nhiên từ RULE-04) | — |
| Q7 | Grant 24h có gia hạn được không hay phải request mới? | TBD (PO) | docs không nêu |
| Q8 | `sourceStoreId=null` nghĩa toàn chuỗi — giới hạn theo Org? (Hiển nhiên Org, cần chốt) | TBD (PO) | ERD nullable |
| Q9 | `is_locked` 24h (ERD) vs không khóa (ma trận ERD §5): theo bên nào? | TBD (PO) — v1 không khóa | ERD mâu thuẫn nội bộ |

---

## F. OpenAPI 3.1 YAML

Single source of truth: [`./openapi/clinical-v1.yaml`](./openapi/clinical-v1.yaml).
