# PET CARE 2.0 — BACKEND TIMELINE & TRACKING

> **Dự án:** Pet Care - Pet Shop Management Ecosystem  
> **Thời gian:** 6 tuần (07/09/2026 – 19/10/2026)  
> **Nhân sự BE:** 02 Backend Engineers  
> **Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL 17, Redis 7, Flyway, JUnit 5 + Testcontainers  
> **Tài liệu specs:** `docs/00-requirements.md` (25 modules, 217 rules, 19 FSMs)  
> **Ngày audit:** 25/09/2026

---

## 📊 TỔNG HỢP TIẾN ĐỘ

| Nhóm | Tổng Modules | ✅ Hoàn thành | 🔄 Đang làm | ⏳ Chưa bắt đầu |
|---|---|:---:|:---:|:---:|
| **Generic/Infrastructure** | 4 | 4 | 0 | 0 |
| **Supporting Domains** | 5 | 5 | 0 | 0 |
| **Core Domains** | 16 | 0 | 0 | 16 |
| **TỔNG CỘNG** | **25** | **9** | **0** | **16** |

---

## ✅ ĐÃ HOÀN THÀNH (9/25 modules)

| # | Module | Files | Controllers | Docs API | Trạng thái |
|---|---|---|---|---|---|
| 01 | **Authentication & OTP** | 36 | 1 | ✅ `auth-v1.md` | ✅ Hoàn thành |
| 02 | **Identity & Access Management** | 27 | 3 | ✅ `iam-v1.md` | ✅ Hoàn thành |
| 04 | **Customer & Pet** | 27 | 2 | ✅ `customer-pet-v1.md` | ✅ Hoàn thành |
| 05 | **Service & Product Catalog** | 37 | 4 | ✅ `catalog-v1.md` | ✅ Hoàn thành |
| 12 | **Inventory & Warehouse** | 30 | 3 | ✅ `inventory-v1.md` | ✅ Hoàn thành |
| 13 | **Procurement** | 35 | 3 | ✅ `procurement-v1.md` | ✅ Hoàn thành |
| 14 | **Order Management** | 16 | 1 | ✅ `order-v1.md` | ✅ Hoàn thành |
| 03 | **Organization & Store** | 55 | 6 | ✅ `org-store-v1.md` | ✅ Hoàn thành |
| 23 | **Notification** | 9 | 0 | ✅ `notification-v1.md` | ✅ Hoàn thành |

**Total:** 272 Java files (main) + 66 tests

---

## ⏳ CHƯA LÀM (16 modules còn lại)

### 🔴 Ưu Tiên Cao — Gốc rễ vận hành

| # | Module | Docs API | Entities chính | FSMs | Phụ thuộc |
|---|---|---|---|---|---|
| **06** | Appointment & Scheduling | `appointment-v1.md` | Appointment, AppointmentService | ✅ FSM-1: AppointmentStatus | Organization |
| **07** | Walk-in & Queue | `queue-v1.md` | QueueTicket, QueueLog | ✅ FSM-2: QueueStatus | Organization, Appointment |

### 🟠 Ưu Tiên Cao — Nghiệp vụ Bác sĩ

| # | Module | Docs API | Entities chính | FSMs | Phụ thuộc |
|---|---|---|---|---|---|
| **09** | Veterinary/Clinical | `clinical-v1.md` | ClinicalRecord, Prescription | ✅ FSM-6: VisitStatus | Appointment |
| **10** | Vaccination | `vaccination-v1.md` | VaccinationRecord, VaccineBatch | ✅ FSM-7: VaccinationStatus | Inventory (vaccine) |

### 🟡 Ưu Tiên Trung — Thanh toán & Tài chính

| # | Module | Docs API | Entities chính | FSMs | Phụ thuộc |
|---|---|---|---|---|---|
| **15** | Billing & Invoice | `invoice-v1.md` | Invoice, InvoiceLineItem | ✅ FSM-8: InvoiceStatus | Order, Clinical |
| **16** | Payment | `payment-v1.md` | Payment, PaymentMethod | ✅ FSM-9: PaymentStatus | Invoice, Order |
| **17** | Refund | `refund-v1.md` | RefundRequest, RefundApproval | ✅ FSM-10: RefundStatus | Payment, Invoice |
| **20** | Package | `package-v1.md` | ServicePackage, PackageRedemption | ✅ FSM-11: PackageStatus | Appointment, Customer |

### 🟢 Ưu Tiên Thấp — Bổ trợ

| # | Module | Docs API | Entities chính | FSMs | Phụ thuộc |
|---|---|---|---|---|---|
| **08** | Workforce | `workforce-v1.md` | WorkShift, ShiftAssignment | ✅ FSM-4: ShiftStatus | Organization |
| **11** | Grooming | `grooming-v1.md` | GroomingSession, GroomingService | ✅ FSM-5: GroomingStatus | Appointment |
| **18** | Promotion & Voucher | `promotion-v1.md` | Promotion, Voucher | ✅ FSM-12: VoucherStatus | Order |
| **19** | Membership & Loyalty | `membership-v1.md` | Membership, LoyaltyPoint | ✅ FSM-13: MembershipStatus | Customer |
| **21** | Incident | `incident-v1.md` | Incident, IncidentReport | ✅ FSM-14: IncidentStatus | Organization |
| **22** | Consent & Privacy | `consent-v1.md` | ConsentRecord | ✅ FSM-15: ConsentStatus | Customer |
| **24** | Reporting & Analytics | `report-v1.md` | ReportConfig, ReportData | — | All domains |
| **25** | Audit | `audit-v1.md` | AuditLog | — | All domains |

---

## 📅 ROADMAP BE CHI TIẾT (6 TUẦN)

```
Tuần 1: 10-14/09   ██████████████████████████████  (Platform + Auth + IAM)           ✅ XONG
Tuần 2: 17-21/09   ██████████████████████████████  (Org + Catalog + Pet + Inventory)  ✅ XONG
Tuần 3: 24-28/09   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Appointment + Queue)              🔄 SẮP TỚI
Tuần 4: 01-05/10   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Clinical + Vaccination)
Tuần 5: 08-12/10   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Invoice + Payment + Refund + Package)
Tuần 6: 15-19/10   ░░░░░░░░░░░░░░░░░░░░░░░░░░░░  (Còn lại + Integration + E2E)
```

---

### TUẦN 3 (24–28/09/2026): APPOINTMENT & QUEUE 🔄

**Mục tiêu:** Cho phép đặt lịch khám/grooming và quản lý hàng đợi

#### Module 06: Appointment & Scheduling
```
Entity:    Appointment, AppointmentService, AppointmentSlot
FSM:       FSM-1: PENDING → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED / CANCELLED
API:       POST /api/v1/appointments/hold
           GET  /api/v1/appointments/{id}
           POST /api/v1/appointments/{id}/confirm
           POST /api/v1/appointments/{id}/cancel
           GET  /api/v1/stores/{storeId}/slots?date=&service=
Jobs:      AppointmentHoldExpiryJob (15 phút)
```
**Tasks:**
- [ ] Tạo module `appointment/`
- [ ] Entity: `Appointment`, `AppointmentService`
- [ ] FSM: `AppointmentStatus` enum + `AppointmentTransitionHandler`
- [ ] Repository: `AppointmentRepository`, `AppointmentServiceRepository`
- [ ] Service: `AppointmentService` với business rules (RULE-06-XX)
- [ ] Controller: REST endpoints
- [ ] Test: FSM tests cho mọi transition hợp lệ/không hợp lệ
- [ ] Migration: `V6__appointments.sql`

#### Module 07: Walk-in & Queue
```
Entity:    QueueTicket, QueueLog
FSM:       FSM-2: WAITING → CALLED → IN_SERVICE → SERVED / NO_SHOW
API:       POST /api/v1/queues/issue
           GET  /api/v1/queues/{id}/call
           POST /api/v1/queues/{id}/serve
Jobs:      QueueTimeoutJob (5 phút)
```
**Tasks:**
- [ ] Tạo module `queue/`
- [ ] Entity: `QueueTicket`, `QueueLog`
- [ ] FSM: `QueueStatus` enum + `QueueTransitionHandler`
- [ ] Repository + Service + Controller
- [ ] Test: FIFO logic, timeout handling
- [ ] Migration: `V7__queue.sql`

---

### TUẦN 4 (01–05/10/2026): CLINICAL & VACCINATION

**Mục tiêu:** Hồ sơ bệnh án điện tử và quản lý tiêm chủng

#### Module 09: Veterinary/Clinical
```
Entity:    ClinicalRecord, Prescription, PrescriptionItem
FSM:       FSM-6: SCHEDULED → IN_PROGRESS → COMPLETED / CANCELLED
API:       POST /api/v1/clinical-records
           GET  /api/v1/pets/{petId}/clinical-records
           POST /api/v1/clinical-records/{id}/prescribe
```
**Tasks:**
- [ ] Tạo module `clinical/`
- [ ] Entity: `ClinicalRecord`, `Prescription`, `PrescriptionItem`
- [ ] FSM: `VisitStatus` enum + handler
- [ ] Service: clinical diagnosis, prescription logic
- [ ] Controller + Tests
- [ ] Migration: `V8__clinical.sql`

#### Module 10: Vaccination
```
Entity:    VaccinationRecord, VaccineBatch
FSM:       FSM-7: SCHEDULED → ADMINISTERED / MISSED / CANCELLED
API:       POST /api/v1/vaccinations
           GET  /api/v1/vaccines/batches
           POST /api/v1/vaccines/batches
Jobs:      VaccinationReminderJob
```
**Tasks:**
- [ ] Tạo module `vaccination/`
- [ ] Entity: `VaccinationRecord`, `VaccineBatch`
- [ ] FSM: `VaccinationStatus` enum + handler
- [ ] Integration với Inventory (vaccine stock)
- [ ] Controller + Tests
- [ ] Migration: `V9__vaccination.sql`

---

### TUẦN 5 (08–12/10/2026): INVOICE, PAYMENT, REFUND, PACKAGE

**Mục tiêu:** Thanh toán và hoàn tiền

#### Module 15: Billing & Invoice
```
Entity:    Invoice, InvoiceLineItem
FSM:       FSM-8: DRAFT → ISSUED → PAID / VOID / OVERDUE
API:       POST /api/v1/invoices
           GET  /api/v1/invoices/{id}
           POST /api/v1/invoices/{id}/issue
```
**Tasks:**
- [ ] Tạo module `invoice/`
- [ ] Entity: `Invoice`, `InvoiceLineItem`
- [ ] FSM: `InvoiceStatus` enum + handler
- [ ] Integration: Order, Clinical, Grooming
- [ ] Controller + Tests
- [ ] Migration: `V10__invoices.sql`

#### Module 16: Payment
```
Entity:    Payment, PaymentMethod
FSM:       FSM-9: PENDING → PROCESSING → COMPLETED / FAILED / REFUNDED
API:       POST /api/v1/payments
           GET  /api/v1/payments/{id}
           POST /api/v1/payments/vnpay/callback
Gateway:   VNPAY integration
```
**Tasks:**
- [ ] Tạo module `payment/`
- [ ] Entity: `Payment`, `PaymentMethod`
- [ ] FSM: `PaymentStatus` enum + handler
- [ ] VNPAY gateway integration
- [ ] Controller + Tests
- [ ] Migration: `V11__payments.sql`

#### Module 17: Refund
```
Entity:    RefundRequest, RefundApproval
FSM:       FSM-10: PENDING → APPROVED / REJECTED → PROCESSING → COMPLETED
API:       POST /api/v1/refunds
           GET  /api/v1/refunds/{id}
           POST /api/v1/refunds/{id}/approve
           POST /api/v1/refunds/{id}/reject
Maker-Checker: Store Manager approves
```
**Tasks:**
- [ ] Tạo module `refund/`
- [ ] Entity: `RefundRequest`, `RefundApproval`
- [ ] FSM: `RefundStatus` enum + handler
- [ ] Maker-Checker logic
- [ ] Controller + Tests
- [ ] Migration: `V12__refunds.sql`

#### Module 20: Package
```
Entity:    ServicePackage, PackagePurchase, PackageRedemption
FSM:       FSM-11: ACTIVE → EXPIRED / DEPLETED
API:       GET  /api/v1/packages
           POST /api/v1/packages/purchase
           POST /api/v1/packages/{id}/redeem
```
**Tasks:**
- [ ] Tạo module `package/`
- [ ] Entity: `ServicePackage`, `PackagePurchase`, `PackageRedemption`
- [ ] FSM: `PackageStatus` enum + handler
- [ ] Redemption logic (Appointment)
- [ ] Controller + Tests
- [ ] Migration: `V13__packages.sql`

---

### TUẦN 6 (15–19/10/2026): CÒN LẠI + INTEGRATION

**Mục tiêu:** Hoàn thiện tất cả modules còn lại

#### Modules còn lại:

| # | Module | Tasks chính |
|---|---|---|
| **08** | Workforce | WorkShift, ShiftAssignment, ShiftSchedule |
| **11** | Grooming | GroomingSession, GroomingService, BeforeAfter photos |
| **18** | Promotion & Voucher | Promotion, Voucher, Coupon code logic |
| **19** | Membership & Loyalty | MembershipTier, LoyaltyPoint, PointTransaction |
| **21** | Incident | IncidentReport, IncidentType, Severity |
| **22** | Consent & Privacy | ConsentRecord, Break-Glass emergency access |
| **24** | Reporting & Analytics | ReportConfig, Scheduled reports, Export |
| **25** | Audit | AuditLog, Immutable event sourcing |

#### Integration Tasks:
- [ ] E2E tests cho tất cả FSMs
- [ ] API contract validation (`node docs/api/check-contracts.mjs`)
- [ ] Performance testing
- [ ] Security audit

---

## 📋 CHECKLIST MIGRATION

| Version | Nội dung | Trạng thái |
|---|---|---|
| V1 | init_schema (25 bảng core) | ✅ Hoàn thành |
| V2 | auth_session_tokens | ✅ Hoàn thành |
| V3 | refresh_token_cleanup_index | ✅ Hoàn thành |
| V4 | pets_audit_columns | ✅ Hoàn thành |
| V5 | caregiver_delegations | ✅ Hoàn thành |
| V6 | appointments | ⏳ Tuần 3 |
| V7 | queue | ⏳ Tuần 3 |
| V8 | clinical_records | ⏳ Tuần 4 |
| V9 | vaccination | ⏳ Tuần 4 |
| V10 | invoices | ⏳ Tuần 5 |
| V11 | payments | ⏳ Tuần 5 |
| V12 | refunds | ⏳ Tuần 5 |
| V13 | packages | ⏳ Tuần 5 |
| V14-V20 | Còn lại | ⏳ Tuần 6 |

---

## 🎯 MILESTONES

| Milestone | Ngày | Modules | Tiêu chí definition of done |
|---|---|---|---|
| **M1: Foundation** | 14/09 | Platform, Auth, IAM | Auth flow hoàn chỉnh, JWT, RBAC |
| **M2: Core Commerce** | 05/10 | Catalog, Order, Inventory, Appointment, Queue, Clinical, Vaccination | Đặt lịch → Khám → Thanh toán E2E |
| **M3: Full Commerce** | 12/10 | Invoice, Payment, Refund, Package | Thanh toán VNPAY, hoàn tiền Maker-Checker |
| **M4: Complete** | 19/10 | Tất cả 25 modules | E2E test pass, API contract valid |

---

## 📁 STRUCTURE HIỆN TẠI

```
BE/src/main/java/com/petcare/module/
├── auth/          ✅ 36 files (1 controller)
├── catalog/       ✅ 37 files (4 controllers)
├── iam/           ✅ 27 files (3 controllers)
├── inventory/     ✅ 30 files (3 controllers)
├── notification/  ✅ 9 files (0 controllers - async job)
├── order/         ✅ 16 files (1 controller)
├── organization/  ✅ 55 files (6 controllers)
├── pet/           ✅ 27 files (2 controllers)
├── procurement/   ✅ 35 files (3 controllers)
├── ─────────────────────────────────────────────
├── appointment/   ⏳ 0 files - TUẦN 3
├── queue/         ⏳ 0 files - TUẦN 3
├── clinical/      ⏳ 0 files - TUẦN 4
├── vaccination/   ⏳ 0 files - TUẦN 4
├── invoice/       ⏳ 0 files - TUẦN 5
├── payment/       ⏳ 0 files - TUẦN 5
├── refund/        ⏳ 0 files - TUẦN 5
├── package/       ⏳ 0 files - TUẦN 5
├── workforce/     ⏳ 0 files - TUẦN 6
├── grooming/      ⏳ 0 files - TUẦN 6
├── promotion/     ⏳ 0 files - TUẦN 6
├── membership/    ⏳ 0 files - TUẦN 6
├── incident/      ⏳ 0 files - TUẦN 6
├── consent/       ⏳ 0 files - TUẦN 6
├── report/        ⏳ 0 files - TUẦN 6
└── audit/         ⏳ 0 files - TUẦN 6
```

---

## 📊 THỐNG KÊ

| Chỉ số | Giá trị |
|---|---|
| Tổng Java files (main) | 272 |
| Tổng Java files (test) | 66 |
| Modules hoàn thành | 9/25 (36%) |
| API contracts viết | 17/25 (68%) |
| FSMs specified | 19 |
| Business rules (RULE-ID) | 217 |

---

*Audit: 25/09/2026*
