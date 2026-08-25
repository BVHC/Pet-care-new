# Pet-care-new: Module Checklist (22 Modules)

> **Date:** 2026-08-18  
> **Scope:** 22 modules, 6 FSMs, 4 Phases  
> **Duration:** 6 tuần

---

## 1. Roles (8 Roles)

| Scope | Role | Mô tả |
|-------|------|-------|
| Platform | SUPER_ADMIN | Quản trị toàn hệ thống |
| Organization | ORG_ADMIN | Quản lý chuỗi cửa hàng |
| Store | STORE_MANAGER | Quản lý store, duyệt refunds |
| Store | FINANCE_STAFF | Thu ngân, đối soát |
| Store | INVENTORY_STAFF | Quản lý kho |
| Store | RECEPTIONIST | Tiếp khách, check-in, tạo đơn |
| Store | VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng |
| Store | GROOMER | Làm đẹp thú cưng |
| User | CUSTOMER | Khách hàng |

---

## 2. Phase 1: Foundation (W1)

### Module 1: Auth + OTP ⭐

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| Account entity + migration | ☐ |
| OTP entity + generation | ☐ |
| Register endpoint | ☐ |
| OTP verification | ☐ |
| Login endpoint | ☐ |
| JWT generation + validation | ☐ |
| Refresh token | ☐ |
| Logout | ☐ |
| Password hashing (BCrypt) | ☐ |
| Rate limiting | ☐ |
| Test: full auth flow | ☐ |

### Module 2: Users

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| User entity + migration | ☐ |
| User CRUD | ☐ |
| Role assignment | ☐ |
| Lock/Unlock user | ☐ |
| Test: user operations | ☐ |

### Module 3: Organizations

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| Organization entity + migration | ☐ |
| Organization CRUD | ☐ |
| Test: org operations | ☐ |

### Module 4: Stores

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| Store entity + migration | ☐ |
| Store CRUD | ☐ |
| Operating hours | ☐ |
| Store services config | ☐ |
| Store status FSM (ACTIVE/SUSPENDED/ARCHIVED) | ☐ |
| Test: store operations | ☐ |

### Module 5: Pets

**Owner:** P2  
**FSM:** ❌

| Task | Done |
|------|------|
| Pet entity + migration | ☐ |
| Pet CRUD | ☐ |
| Pet ownership (RULE-04-01) | ☐ |
| Pet medical history lookup | ☐ |
| Test: pet operations | ☐ |

### Module 6: Products

**Owner:** P3  
**FSM:** ❌

| Task | Done |
|------|------|
| Product entity + migration | ☐ |
| Product CRUD | ☐ |
| Product category | ☐ |
| Test: product operations | ☐ |

### Module 7: Inventory

**Owner:** P3  
**FSM:** ❌

| Task | Done |
|------|------|
| Inventory entity + migration | ☐ |
| Stock in/out operations | ☐ |
| Low stock alert | ☐ |
| Test: inventory operations | ☐ |

---

## 3. Phase 2: Core Domain (W2)

### Module 8: Appointments ⭐⭐

**Owner:** P1  
**FSM:** ✅ BOOKED → COMPLETED

| Task | Done |
|------|------|
| Appointment entity + migration | ☐ |
| BookAppointment (RULE-06-01, 06-02, 06-04, 06-07) | ☐ |
| ConfirmAppointment (RULE-06-01, 06-10) | ☐ |
| CheckInAppointment (RULE-06-06) | ☐ |
| StartAppointmentService (RULE-09-01, 11-01) | ☐ |
| CheckOutAppointment (RULE-06-06) | ☐ |
| CancelAppointment (RULE-06-05, 06-08) | ☐ |
| RescheduleAppointment (NEW - RULE-06-10) | ☐ |
| MarkNoShow (RULE-06-09) | ☐ |
| StoreResource collision check (RULE-06-10) | ☐ |
| List appointments | ☐ |
| Test: full FSM | ☐ |
| Test: invalid transitions | ☐ |

### Module 9: Orders + Cart ⭐⭐

**Owner:** P2  
**FSM:** ✅ PENDING_PAYMENT → DELIVERED

| Task | Done |
|------|------|
| Cart entity + CRUD | ☐ |
| Order entity + migration | ☐ |
| CreateOrder (RULE-14-01, 14-02, 14-07) | ☐ |
| Inventory Reservation (RULE-14-07) | ☐ |
| ProcessOrderTimeout (auto-cancel 15min) | ☐ |
| Order → PAID (event) | ☐ |
| ConfirmOrder (RULE-14-04) | ☐ |
| ProcessOrder | ☐ |
| PrepareProductOrder (RULE-12-04, 14-02) | ☐ |
| CompleteStoreOrder (RULE-14-05) | ☐ |
| CancelOrder (RULE-14-03, 14-06) | ☐ |
| CancelOrderWithRefund (NEW) | ☐ |
| Order → REFUNDED (event) | ☐ |
| Test: full FSM | ☐ |
| Test: invalid transitions | ☐ |
| Test: inventory reservation timeout | ☐ |

### Module 10: Payments ⭐⭐

**Owner:** P3  
**FSM:** ✅ PENDING → SUCCESS

| Task | Done |
|------|------|
| Payment entity + migration | ☐ |
| MakePayment (RULE-16-01, 16-02) | ☐ |
| RecordCashPayment (RULE-16-01, 16-02) | ☐ |
| VerifyPayment (RULE-16-01, 16-03) | ☐ |
| Payment callback (RULE-16-03, 16-05) | ☐ |
| Payment SUCCESS / FAILED | ☐ |
| CancelPayment (RULE-16-04) | ☐ |
| Payment PARTIALLY_REFUNDED (NEW) | ☐ |
| Payment → REFUNDED (event) | ☐ |
| Mock payment gateway | ☐ |
| Test: payment FSM | ☐ |
| Test: partial refund flow | ☐ |

---

## 4. Phase 3: Commerce (W3-W4)

### Module 11: Invoices ⭐

**Owner:** P1  
**FSM:** ✅ DRAFT → PAID

| Task | Done |
|------|------|
| Invoice entity + items | ☐ |
| CreateInvoice (RULE-15-01, 15-06) | ☐ |
| IssueInvoice (RULE-15-01, 15-02, 15-03) | ☐ |
| PaymentSucceeded → PARTIALLY_PAID/PAID | ☐ |
| VoidInvoice (RULE-15-04) | ☐ |
| Invoice → REFUNDED (RULE-15-07) | ☐ |
| Test: invoice FSM | ☐ |

### Module 12: Refunds ⭐

**Owner:** P1  
**FSM:** ✅ REQUESTED → COMPLETED

| Task | Done |
|------|------|
| Refund entity | ☐ |
| RequestRefund (RULE-17-01, 17-02, 17-03, 17-07) | ☐ |
| 30-day refund window check (RULE-17-07) | ☐ |
| ApproveRefund (RULE-17-01, 17-02, 17-04) | ☐ |
| RejectRefund (RULE-17-06) | ☐ |
| ProcessRefund (RULE-17-04) | ☐ |
| CompleteRefund (RULE-17-02, 17-05) | ☐ |
| FailRefund (RULE-17-05) | ☐ |
| RetryRefund (NEW) | ☐ |
| ResolveRefundManually (NEW) | ☐ |
| Test: refund FSM | ☐ |
| Test: 30-day window | ☐ |

### Module 13: Clinical (FULL) ⭐⭐

**Owner:** P2  
**FSM:** ❌

| Task | Done |
|------|------|
| MedicalRecord entity | ☐ |
| Diagnosis entity | ☐ |
| Prescription entity | ☐ |
| PrescriptionItem entity | ☐ |
| FollowUp entity | ☐ |
| ExaminePet (RULE-09-01) | ☐ |
| CreateDiagnosis (RULE-09-03) | ☐ |
| CreatePrescription (RULE-09-05) | ☐ |
| MedicalHistory view (RULE-09-02) | ☐ |
| CreateFollowUp (RULE-09-06) | ☐ |
| Permission check (RULE-09-07) | ☐ |
| **Cross-Store Consent (NEW - RULE-09-02, 22-08)** | ☐ |
| RequestCrossStoreConsent + OTP | ☐ |
| VerifyCrossStoreConsentOTP | ☐ |
| EmergencyOverrideAccess (NEW) | ☐ |
| Test: clinical flow | ☐ |

### Module 14: Promotions + Vouchers

**Owner:** P2  
**FSM:** ❌

| Task | Done |
|------|------|
| Voucher entity | ☐ |
| VoucherUsage entity | ☐ |
| Create voucher | ☐ |
| Validate voucher (RULE-18-02, 18-05) | ☐ |
| Apply voucher at checkout | ☐ |
| Voucher usage tracking (RULE-18-03, 18-04) | ☐ |
| Expiry validation | ☐ |
| Test: voucher flow | ☐ |

### Module 15: Vaccinations

**Owner:** P3  
**FSM:** ❌

| Task | Done |
|------|------|
| Vaccine entity | ☐ |
| VaccineBatch entity + barcode field (NEW) | ☐ |
| available_quantity field (NEW - C-565e7b1) | ☐ |
| Vaccination entity | ☐ |
| VaccinationSchedule entity | ☐ |
| **Barcode scanning (RULE-10-06 - NEW)** | ☐ |
| ValidateBarcode API | ☐ |
| AdministerVaccine (RULE-10-05, 10-06) | ☐ |
| Expiry check (RULE-10-03) | ☐ |
| Stock decrement on injection (RULE-10-05) | ☐ |
| CreateVaccinationSchedule (RULE-10-04) | ☐ |
| ScheduleNextVaccination (RULE-10-04) | ☐ |
| Reminder notifications | ☐ |
| **Routine vs Clinical vaccination (RULE-10-07)** | ☐ |
| Test: vaccination flow | ☐ |

### Module 16: Event Bridges (W4)

**Owner:** All  
**FSM:** ❌

| Task | Done |
|------|------|
| Order → Payment → Invoice | ☐ |
| Payment → Order.PAID | ☐ |
| Refund → Payment.REFUNDED | ☐ |
| Refund → Order.REFUNDED | ☐ |
| Appointment → Invoice | ☐ |
| Clinical → Invoice | ☐ |
| Vaccination → Inventory decrement | ☐ |

---

## 5. Phase 4: Polish (W5-W6)

### Module 17: FE Integration

**Owner:** All  
**FSM:** ❌

| Task | P1 | P2 | P3 |
|------|-----|-----|-----|
| FE Auth pages | ☐ | — | — |
| FE Pets pages | — | ☐ | — |
| FE Products pages | — | — | ☐ |
| FE Appointments | ☐ | — | — |
| FE Orders + Cart | — | ☐ | — |
| FE Payments | — | — | ☐ |
| FE Clinical | — | ☐ | — |
| FE Invoices | ☐ | — | — |
| FE Refunds | ☐ | — | — |

### Module 18: Notifications

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| Notification entity | ☐ |
| Send notification trigger | ☐ |
| List user notifications | ☐ |
| Mark as read | ☐ |
| Unread count | ☐ |
| Test: notification flow | ☐ |

### Module 19: Walk-ins

**Owner:** P2  
**FSM:** ❌

| Task | Done |
|------|------|
| Queue entity (NEW) | ☐ |
| QueueEntry entity (NEW) | ☐ |
| Walkin entity + appointment_id FK (NEW) | ☐ |
| CreateWalkIn (RULE-07-01) | ☐ |
| Enqueue (RULE-07-02, 07-03) | ☐ |
| Call queue (RULE-07-04) | ☐ |
| FIFO ordering | ☐ |
| **Walk-in to Appointment Bridge (RULE-07-05)** | ☐ |
| CheckInWalkIn → auto-create Appointment | ☐ |
| Test: walk-in flow | ☐ |

### Module 20: Grooming FSM

**Owner:** P3  
**FSM:** ✅ WAITING → AWAITING_CUSTOMER_APPROVAL → COMPLETED

| Task | Done |
|------|------|
| GroomingSession entity | ☐ |
| GroomingService entity | ☐ |
| CheckInGrooming (RULE-11-01) | ☐ |
| PerformGrooming | ☐ |
| AddGroomingService (RULE-11-02) | ☐ |
| ConfirmAdditionalService (RULE-11-03) | ☐ |
| RejectAdditionalService (RULE-11-03) | ☐ |
| CompleteGrooming (RULE-11-04) | ☐ |
| CancelGrooming | ☐ |
| Test: grooming FSM | ☐ |

### Module 21: Workforce

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| WorkSchedule entity | ☐ |
| StaffAbsence entity | ☐ |
| CreateWorkSchedule (RULE-08-01) | ☐ |
| No overlapping schedules (RULE-08-02) | ☐ |
| RecordAbsence (RULE-08-03) | ☐ |
| ApproveLeave (RULE-08-05) | ☐ |
| View own schedule (RULE-08-06) | ☐ |
| Test: workforce flow | ☐ |

### Module 22: Reports

**Owner:** P2  
**FSM:** ❌

| Task | Done |
|------|------|
| RevenueReport (RULE-24-01, 24-02) | ☐ |
| Store comparison (RULE-24-04) | ☐ |
| RevenueReconciliation (RULE-24-05) | ☐ |
| CustomerReport (RULE-24-06) | ☐ |
| Test: report generation | ☐ |

### Module 23: Audit Logs

**Owner:** P1  
**FSM:** ❌

| Task | Done |
|------|------|
| AuditLog entity | ☐ |
| RecordAuditLog (RULE-25-01) | ☐ |
| Immutability (RULE-25-02) | ☐ |
| PermissionChange history (RULE-25-03) | ☐ |
| PaymentRefundAudit (RULE-25-05) | ☐ |
| InventoryAudit (RULE-25-06) | ☐ |
| Test: audit logging | ☐ |

### Module 24: Docker + Deploy

**Owner:** P3  
**FSM:** ❌

| Task | Done |
|------|------|
| Dockerfile | ☐ |
| docker-compose.yml | ☐ |
| Environment variables | ☐ |
| Health check endpoint | ☐ |
| Deployment script | ☐ |

### Module 25: Tests

**Owner:** All  
**FSM:** ❌

| Task | P1 | P2 | P3 |
|------|-----|-----|-----|
| Unit tests per module | ☐ | ☐ | ☐ |
| FSM transition tests | ☐ | ☐ | ☐ |
| E2E: Customer flow | ☐ | ☐ | ☐ |
| E2E: Staff flow | ☐ | ☐ | ☐ |
| Integration tests | ☐ | ☐ | ☐ |

---

## 6. Progress Tracking (Updated per C-565e7b1)

| Phase | Module | Owner | Status | % |
|-------|--------|-------|--------|---|
| **W1** | Auth + OTP | P1 | ☐ | 0% |
| **W1** | Users | P1 | ☐ | 0% |
| **W1** | Organizations | P1 | ☐ | 0% |
| **W1** | Stores + StoreResources | P1 | ☐ | 0% |
| **W1** | Pets | P2 | ☐ | 0% |
| **W1** | Products | P3 | ☐ | 0% |
| **W1** | Inventory | P3 | ☐ | 0% |
| **W2** | Appointments + Reschedule | P1 | ☐ | 0% |
| **W2** | Orders + Inventory Reserve | P2 | ☐ | 0% |
| **W2** | Payments + Partial Refund | P3 | ☐ | 0% |
| **W3** | Invoices + Partially Paid | P1 | ☐ | 0% |
| **W3** | Refunds + Retry + 30-day | P1 | ☐ | 0% |
| **W3** | Clinical + Cross-Store Consent | P2 | ☐ | 0% |
| **W3** | Promotions | P2 | ☐ | 0% |
| **W3** | Vaccinations + Barcode | P3 | ☐ | 0% |
| **W4** | Event Bridges (Outbox) | All | ☐ | 0% |
| **W5** | FE Integration | All | ☐ | 0% |
| **W5** | Notifications | P1 | ☐ | 0% |
| **W5** | Walk-ins + Appointment Bridge | P2 | ☐ | 0% |
| **W6** | Grooming + Customer Approval | P3 | ☐ | 0% |
| **W6** | Workforce | P1 | ☐ | 0% |
| **W6** | Reports | P2 | ☐ | 0% |
| **W6** | Audit + Medical Record Access | P1 | ☐ | 0% |
| **W6** | Docker | P3 | ☐ | 0% |
| **W6** | Tests | All | ☐ | 0% |

---

## 7. Milestones

| Milestone | Date | Criteria |
|-----------|------|----------|
| **M1: Foundation** | End of W1 | Auth, Users, Orgs, Pets, Products, Inventory CRUD |
| **M2: Core FSM** | End of W2 | Appointments, Orders, Payments FSM với tests |
| **M3: Commerce** | End of W4 | Invoices, Refunds, Clinical, Vaccinations working |
| **M4: Demo Ready** | End of W6 | FE integration, Docker, Tests |