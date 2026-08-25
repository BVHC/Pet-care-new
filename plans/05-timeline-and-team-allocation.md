# Pet-care-new: Timeline & Team Allocation

> **Date:** 2026-08-18  
> **Duration:** 6 tuần (30 ngày làm việc)  
> **Team:** 3 developers  
> **Pattern:** Monolithic, Admin First

---

## 1. Team Members

| Person | Vai trò | Modules |
|--------|---------|---------|
| **P1 (Lead)** | Backend Lead | Setup, Auth, Users, Orgs, Appointments, Invoices, Refunds, Workforce, Audit |
| **P2** | Business Logic | Pets, Orders, Clinical (FULL), Vouchers, Reports, Walk-ins |
| **P3** | Commerce + Integration | Products, Inventory, Payments, Vaccinations, Grooming, Docker |

---

## 2. Chi tiết từng tuần

### Phase 1: Foundation (W1)

**Goal:** CRUD foundation cho tất cả modules

#### W1 - Ngày 1-2 (Setup)

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Maven setup, pom.xml, Docker Compose | Chờ | Chờ |
| Mon | application.yml, SecurityConfig | — | — |
| Tue | Auth: Register, OTP, Login | — | — |
| Tue | JWT filter, Refresh token | — | — |

#### W1 - Ngày 3-5 (CRUD)

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Wed | Users CRUD + Roles | Pets entity | Products entity |
| Thu | Organizations CRUD | PetService + Controller | ProductService + Controller |
| Fri | Stores CRUD + Operating hours | Pet validations | Inventory module |

**Deliverable W1:** ✅ Foundation CRUD working

---

### Phase 2: Core Domain (W2)

**Goal:** 3 FSMs chính - Appointments, Orders, Payments

#### W2 - Ngày 1-2

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Appointment entity + migration | Cart entity + CRUD | Payment entity + migration |
| Tue | AppointmentService: book(), confirm() | Order entity + createOrder() | PaymentService: makePayment() |
| Wed | AppointmentService: checkIn(), start() | OrderService: confirm(), process() | PaymentService: verify(), callback() |
| Thu | AppointmentService: complete(), cancel() | OrderService: prepare(), deliver() | PaymentService: cancel(), refund |
| Fri | AppointmentController + tests | OrderController + tests | PaymentController + tests |

#### W2 - End of Week

**Deliverable W2:** ✅ 3 FSMs working với tests

---

### Phase 3: Commerce (W3-W4)

**Goal:** Invoices, Refunds, Vouchers, Clinical (FULL + Cross-Store), Vaccinations (Barcode)

#### W3

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Invoice entity + items (with PARTIALLY_PAID) | MedicalRecord + CrossStoreConsent entities | Voucher entity + validation |
| Tue | InvoiceService: create, issue, void, partial | ClinicalService: examine(), CrossStoreConsent | Vaccination entity + barcode schema |
| Wed | Refund entity + 30-day window check | ClinicalService: prescription() | VaccinationService: barcode scanning |
| Thu | RefundService: approve, reject, retry | MedicalHistory + Emergency Override | Voucher validation |
| Fri | RefundService: process, complete, manual resolve | ClinicalController + tests | Vaccination tests |

#### W4

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Outbox: Payment → Invoice | Outbox: Order → Invoice | Outbox: Refund → Payment |
| Tue | Outbox: Appointment → Invoice | Integration tests | Integration tests |
| Wed | Integration: Order → Payment → Invoice | Integration: Clinical → Invoice | Integration: Vaccination barcode |
| Thu | Bug fixes | Bug fixes | Bug fixes |
| Fri | Commerce polish | Clinical polish | Commerce polish |

**Deliverable W4:** ✅ Full commerce + Clinical working (with Outbox Pattern)

---

### Phase 4: Polish (W5-W6)

**Goal:** FE Integration, Tests, Docker, P2 features

#### W5: Integration

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | FE Auth pages | FE Pets pages | FE Products pages |
| Tue | FE Appointments pages | FE Orders + Cart | FE Payments |
| Wed | FE Invoices pages | FE Clinical pages | FE Vaccinations |
| Thu | Fix FE/BE bugs | Fix FE/BE bugs | Fix FE/BE bugs |
| Fri | API docs update | API docs update | API docs update |

#### W6: Tests + Docker + P2

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | E2E tests: Customer flow | E2E tests: Staff flow | Docker compose setup |
| Tue | FSM unit tests | FSM unit tests | Docker image build |
| Wed | **Notifications module** | **Walk-ins + Appointment Bridge** | **Grooming FSM + Customer Approval** |
| Thu | **Workforce (basic)** | **Reports (basic)** | Grooming integration |
| Fri | Demo prep | Demo prep | Demo prep |

**Deliverable W6:** ✅ Demo ready

---

## 3. Modules chi tiết theo tuần (Updated per C-565e7b1)

### Phase 1: Foundation (W1)

| Module | Owner | Tasks |
|---------|-------|-------|
| Auth + OTP | P1 | Register, Login, OTP, JWT |
| Users | P1 | CRUD, Roles (8 roles) |
| Organizations | P1 | CRUD |
| Stores + StoreResources | P1 | CRUD, Operating hours, Resource config |
| Pets | P2 | CRUD |
| Products | P3 | CRUD |
| Inventory (with PhysicalQuantity + ReservedQuantity) | P3 | Stock management, 15-min TTL reservation |

### Phase 2: Core Domain (W2)

| Module | Owner | FSM |
|---------|-------|-----|
| Appointments | P1 | ✅ BOOKED → COMPLETED (+ RescheduleAppointment, + StoreResource collision check) |
| Orders | P2 | ✅ PENDING_PAYMENT → DELIVERED (+ ProcessOrderTimeout, + CancelOrderWithRefund) |
| Payments | P3 | ✅ PENDING → SUCCESS (+ PARTIALLY_REFUNDED) |

### Phase 3: Commerce (W3-W4)

| Module | Owner | FSM |
|---------|-------|-----|
| Invoices | P1 | ✅ DRAFT → PAID (+ PARTIALLY_PAID, + DiscardInvoice) |
| Refunds | P1 | ✅ REQUESTED → COMPLETED (+ 30-day window, + RetryRefund, + ResolveRefundManually) |
| Clinical + Cross-Store Consent | P2 | ❌ (+ RequestCrossStoreConsent, + EmergencyOverride) |
| Promotions | P2 | ❌ |
| Vaccinations + Barcode | P3 | ❌ (+ ValidateBarcode, + AdministerVaccine) |

### Phase 4: Polish (W5-W6)

| Module | Owner | Priority |
|---------|-------|----------|
| FE Integration | All | P1 |
| Notifications | P1 | P2 |
| Walk-ins + Appointment Bridge | P2 | P2 |
| Grooming FSM + Customer Approval | P3 | P2 |
| Workforce | P1 | P2 |
| Reports | P2 | P2 |
| Audit Logs + Medical Access | P1 | P2 |

---

## 4. Milestones

| Milestone | Date | Criteria |
|-----------|------|----------|
| **M1: Foundation** | End of W1 | Auth, Users, Orgs, Pets, Products, Inventory CRUD |
| **M2: Core FSM** | End of W2 | Appointments, Orders, Payments FSM với tests |
| **M3: Commerce** | End of W4 | Invoices, Refunds, Clinical, Vaccinations working |
| **M4: Demo Ready** | End of W6 | FE integration, Docker, Tests |

---

## 5. Daily Standup Template

```
Hôm nay:
- Module: [Tên module]
- Tasks done: [X]
- Blocker: [Có/Không]

Ngày mai:
- Module: [Tên module]
-预计完成: [Estimate]

Cần hỗ trợ?
- [Câu hỏi/blocker]
```

---

## 6. Git Workflow

```
main (production)
└── develop (integration)
    ├── feature/auth
    ├── feature/users
    ├── feature/pets
    ├── feature/appointments
    ├── feature/orders
    ├── feature/payments
    ├── feature/clinical
    └── ...
```

**Commit convention:**
```
feat: add appointment booking FSM
fix: resolve check-in validation
docs: update API contracts
test: add appointment FSM tests
refactor: simplify checkout logic
```

---

## 7. Risk Management

| Risk | Impact | Mitigation |
|------|--------|------------|
| P1 delay | Toàn team chờ | P2, P3 parallel work |
| FSM phức tạp | Bug nhiều | Tests cho từng transition |
| FE integration khó | Blocker cuối | Daily sync W5 |
| Clinical quá phức tạp | Delay W3 | Focus vào core flow trước |

---

## 8. Definition of Done

### Mỗi Module cần:
- [ ] Entity + Migration (Flyway)
- [ ] Service + business logic
- [ ] Controller + REST endpoints
- [ ] DTOs (Request/Response)
- [ ] Exception handling
- [ ] Unit tests (≥ 1 test per FSM transition)
- [ ] API documented

### Mỗi FSM cần test:
- [ ] Happy path: BOOKED → CONFIRMED → CHECKED_IN → COMPLETED
- [ ] Invalid transitions: phải throw exception
- [ ] Edge cases: timing, permissions