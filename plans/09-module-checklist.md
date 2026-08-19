# Pet-care-new: Module Checklist (22 Modules)

> **Date:** 2026-08-18  
> **Scope:** 22 modules, 6 FSMs, 4 Phases  
> **Duration:** 6 tuần

---

## 1. Roles (5 Roles)

| Role | Mô tả |
|------|-------|
| SUPER_ADMIN | Quản trị toàn hệ thống |
| STORE_MANAGER | Quản lý store, duyệt refunds |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng |
| GROOMER | Làm đẹp thú cưng |
| CUSTOMER | Khách hàng |

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

### Module 5: Pets + Caregivers

**Owner:** P2  
**FSM:** ✅ CaregiverInvitation

| Task | Done |
|------|------|
| Pet entity + migration | ☐ |
| Pet CRUD | ☐ |
| Pet ownership (RULE-04-01) | ☐ |
| CaregiverInvitation entity | ☐ |
| InviteCaregiver → INVITED | ☐ |
| AcceptInvitation → ACTIVE | ☐ |
| RejectInvitation → REJECTED | ☐ |
| RevokeCaregiver → REVOKED | ☐ |
| ProcessExpiry → EXPIRED | ☐ |
| Test: caregiver FSM | ☐ |

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
| ConfirmAppointment (RULE-06-01) | ☐ |
| CheckInAppointment (RULE-06-06) | ☐ |
| StartAppointmentService | ☐ |
| CompleteAppointment (RULE-06-06) | ☐ |
| CancelAppointment (RULE-06-05, 06-08) | ☐ |
| MarkNoShow (RULE-06-09) | ☐ |
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
| CreateOrder (RULE-14-01, 14-02) | ☐ |
| Order → PAID (event) | ☐ |
| ConfirmOrder (RULE-14-04) | ☐ |
| ProcessOrder | ☐ |
| PrepareProductOrder (RULE-12-04, 14-02) | ☐ |
| CompleteStoreOrder (RULE-14-05) | ☐ |
| CancelOrder (RULE-14-03, 14-06) | ☐ |
| Order → REFUNDED (event) | ☐ |
| Test: full FSM | ☐ |
| Test: invalid transitions | ☐ |

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
| Payment → REFUNDED (event) | ☐ |
| Mock payment gateway | ☐ |
| Test: payment FSM | ☐ |

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
| RequestRefund (RULE-17-01, 17-02, 17-03) | ☐ |
| ApproveRefund (RULE-17-01, 17-02, 17-04) | ☐ |
| RejectRefund (RULE-17-06) | ☐ |
| ProcessRefund (RULE-17-04) | ☐ |
| CompleteRefund (RULE-17-02, 17-05) | ☐ |
| FailRefund (RULE-17-05) | ☐ |
| Test: refund FSM | ☐ |

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
| VaccineBatch entity | ☐ |
| Vaccination entity | ☐ |
| VaccinationSchedule entity | ☐ |
| AdministerVaccination (RULE-10-01, 10-02, 10-05) | ☐ |
| Expiry check (RULE-10-03) | ☐ |
| CreateVaccinationSchedule (RULE-10-04) | ☐ |
| Reminder notifications | ☐ |
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
| Walkin entity | ☐ |
| CreateWalkIn (RULE-07-01) | ☐ |
| Enqueue (RULE-07-02, 07-03) | ☐ |
| Call queue (RULE-07-04) | ☐ |
| FIFO ordering | ☐ |
| Test: walk-in flow | ☐ |

### Module 20: Grooming FSM

**Owner:** P3  
**FSM:** ✅ WAITING → COMPLETED

| Task | Done |
|------|------|
| GroomingSession entity | ☐ |
| GroomingService entity | ☐ |
| CreateGroomingSession (RULE-11-01) | ☐ |
| StartGrooming | ☐ |
| AddAdditionalService (RULE-11-02) | ☐ |
| ConfirmAdditionalService (RULE-11-03) | ☐ |
| CompleteGrooming | ☐ |
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

## 6. Progress Tracking

| Phase | Module | Owner | Status | % |
|-------|--------|-------|--------|---|
| **W1** | Auth + OTP | P1 | ☐ | 0% |
| **W1** | Users | P1 | ☐ | 0% |
| **W1** | Organizations | P1 | ☐ | 0% |
| **W1** | Stores | P1 | ☐ | 0% |
| **W1** | Pets | P2 | ☐ | 0% |
| **W1** | Caregivers | P2 | ☐ | 0% |
| **W1** | Products | P3 | ☐ | 0% |
| **W1** | Inventory | P3 | ☐ | 0% |
| **W2** | Appointments | P1 | ☐ | 0% |
| **W2** | Orders | P2 | ☐ | 0% |
| **W2** | Payments | P3 | ☐ | 0% |
| **W3** | Invoices | P1 | ☐ | 0% |
| **W3** | Refunds | P1 | ☐ | 0% |
| **W3** | Clinical | P2 | ☐ | 0% |
| **W3** | Promotions | P2 | ☐ | 0% |
| **W3** | Vaccinations | P3 | ☐ | 0% |
| **W4** | Event Bridges | All | ☐ | 0% |
| **W5** | FE Integration | All | ☐ | 0% |
| **W5** | Notifications | P1 | ☐ | 0% |
| **W5** | Walk-ins | P2 | ☐ | 0% |
| **W6** | Grooming | P3 | ☐ | 0% |
| **W6** | Workforce | P1 | ☐ | 0% |
| **W6** | Reports | P2 | ☐ | 0% |
| **W6** | Audit | P1 | ☐ | 0% |
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