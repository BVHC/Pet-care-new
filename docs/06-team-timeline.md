# Pet-care-new: Team Timeline & Task Allocation

> **Date:** 2026-08-19
> **Duration:** 6 tuần (30 ngày làm việc)
> **Team:** 3 developers
> **Pattern:** Monolithic, Admin First, FSM-driven

---

## 1. Team Overview

| Person | Role | Primary Modules | Secondary Modules |
|--------|------|-----------------|------------------|
| **P1 (Lead)** | Backend Lead | Auth, Users, Organizations, Appointments, Invoices, Refunds | Workforce, Audit, Notifications |
| **P2** | Business Logic | Pets, Orders, Clinical (FULL), Vouchers | Reports, Walk-ins |
| **P3** | Commerce + Integration | Products, Inventory, Payments, Vaccinations | Grooming FSM, Docker |

---

## 2. Phases & Milestones

| Phase | Tuần | Milestone | Deliverable |
|-------|------|-----------|-------------|
| **1. Foundation** | W1 | M1: Foundation | CRUD foundation for all modules |
| **2. Core Domain** | W2 | M2: Core FSM | Appointments, Orders, Payments FSMs working |
| **3. Commerce** | W3-W4 | M3: Commerce | Invoices, Refunds, Clinical, Vaccinations |
| **4. Polish** | W5-W6 | M4: Demo Ready | FE Integration, Docker, Tests |

---

## 3. WEEKLY BREAKDOWN

### 📅 WEEK 1: Foundation (Aug 25-29)

**Goal:** Setup + CRUD foundation for all modules

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | Maven setup, pom.xml, Docker Compose | — | — |
| **Mon** | SecurityConfig + JWT | — | — |
| **Tue** | Auth: Register, OTP, Login | — | — |
| **Wed** | Users CRUD + Roles | Pets entity + migration | Products entity + migration |
| **Thu** | Organizations + Stores CRUD | PetService + Controller | ProductService + Controller |
| **Fri** | Stores Operating Hours + Services | CaregiverInvitation FSM | Inventory module |

**Deliverable W1:** ✅ Auth, Users, Organizations, Stores, Pets, Products, Inventory CRUD working

---

### 📅 WEEK 2: Core Domain (Sep 1-5)

**Goal:** 3 main FSMs - Appointments, Orders, Payments

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | Appointment entity + migration | Cart entity + CRUD | Payment entity + migration |
| **Tue** | AppointmentService: book(), confirm() | Order entity + createOrder() | PaymentService: makePayment() |
| **Wed** | AppointmentService: checkIn(), start() | OrderService: confirm(), process() | PaymentService: verify(), callback() |
| **Thu** | AppointmentService: complete(), cancel() | OrderService: prepare(), deliver() | PaymentService: cancel() |
| **Fri** | AppointmentController + tests | OrderController + tests | PaymentController + tests |

**FSM Transitions to Implement:**
- **Appointment FSM:** BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED (+ CANCELLED, NO_SHOW)
- **Order FSM:** PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED (+ CANCELLED, REFUNDED)
- **Payment FSM:** PENDING → PROCESSING → SUCCESS (+ FAILED, CANCELLED, REFUNDED)

**Deliverable W2:** ✅ 3 FSMs working with unit tests

---

### 📅 WEEK 3: Commerce Part 1 (Sep 8-12)

**Goal:** Invoice, Refund, Clinical, Vaccinations entities + services

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | Invoice entity + items + migration | MedicalRecord, Diagnosis entities | Voucher entity + validation |
| **Tue** | InvoiceService: create, issue, void | ClinicalService: examine(), diagnose() | Vaccination entity + schedule |
| **Wed** | Refund entity + request flow | ClinicalService: prescription() | VaccinationService: administer() |
| **Thu** | RefundService: approve, reject | MedicalHistory view + follow-ups | Vaccination reminders |
| **Fri** | RefundService: process, complete | ClinicalController + tests | Voucher + Vaccination tests |

**Deliverable W3:** ✅ Invoice + Refund FSMs working, Clinical + Vaccinations basic flow

---

### 📅 WEEK 4: Commerce Part 2 + Event Bridges (Sep 15-19)

**Goal:** Event bridges between FSMs, integration testing

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | Event: Payment → Invoice bridge | Event: Order → Invoice bridge | Integration tests |
| **Tue** | Event: Refund → Payment/Order bridge | Event: Appointment → Invoice | Integration tests |
| **Wed** | Invoice auto-create from events | Clinical → Invoice bridge | Inventory stock decrement |
| **Thu** | Bug fixes + polish | Bug fixes + polish | Bug fixes + polish |
| **Fri** | Commerce polish + review | Clinical polish + review | Commerce polish + review |

**Event Bridges:**
```
PaymentSucceededEvent → Order (PAID) + Invoice (update)
RefundCompletedEvent → Payment (REFUNDED) + Order (REFUNDED) + Invoice (REFUNDED)
AppointmentCompletedEvent → Invoice (auto-create)
ClinicalExaminationCreatedEvent → Invoice (auto-create)
```

**Deliverable W4:** ✅ Full commerce flow with event bridges

---

### 📅 WEEK 5: FE Integration (Sep 22-26)

**Goal:** Connect frontend pages to backend APIs

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | FE Auth pages + JWT integration | FE Pets pages + CRUD | FE Products pages + Cart |
| **Tue** | FE Appointments: book, confirm, check-in | FE Orders + Cart + checkout | FE Payments integration |
| **Wed** | FE Appointments: complete, cancel | FE Clinical: exam, prescription | FE Vaccinations pages |
| **Thu** | FE Invoices + Refunds | FE Vouchers | FE Walk-ins |
| **Fri** | Fix FE/BE bugs | Fix FE/BE bugs | Docker setup start |

**Deliverable W5:** ✅ All FE pages connected to BE APIs

---

### 📅 WEEK 6: Polish + Demo Prep (Sep 29 - Oct 3)

**Goal:** Final polish, tests, Docker, demo-ready

| Day | P1 (Lead) | P2 | P3 |
|-----|------------|-----|-----|
| **Mon** | E2E tests: Customer flow | E2E tests: Staff flow | Docker compose setup |
| **Tue** | FSM unit tests | FSM unit tests | Docker image build |
| **Wed** | **Notifications module** | **Walk-ins module** | **Grooming FSM** |
| **Thu** | **Workforce module** | **Reports module** | Grooming integration |
| **Fri** | Demo prep + final review | Demo prep + final review | Demo prep + final review |

**Deliverable W6:** ✅ Demo ready with Docker deployment

---

## 4. MODULE OWNERSHIP MATRIX

| Module | P1 | P2 | P3 | Phase | FSM |
|--------|:--:|:--:|:--:|:-----:|:--:|
| **Auth + OTP** | ✅ | | | W1 | ❌ |
| **Users** | ✅ | | | W1 | ❌ |
| **Organizations** | ✅ | | | W1 | ❌ |
| **Stores** | ✅ | | | W1 | ❌ |
| **Pets** | | ✅ | | W1 | ❌ |
| **Caregivers** | | ✅ | | W1 | ✅ |
| **Products** | | | ✅ | W1 | ❌ |
| **Inventory** | | | ✅ | W1 | ❌ |
| **Appointments** | ✅ | | | W2 | ✅ |
| **Orders + Cart** | | ✅ | | W2 | ✅ |
| **Payments** | | | ✅ | W2 | ✅ |
| **Invoices** | ✅ | | | W3 | ✅ |
| **Refunds** | ✅ | | | W3 | ✅ |
| **Clinical (FULL)** | | ✅ | | W3 | ❌ |
| **Promotions + Vouchers** | | ✅ | | W3 | ❌ |
| **Vaccinations** | | | ✅ | W3 | ❌ |
| **Event Bridges** | ✅ | ✅ | ✅ | W4 | ❌ |
| **FE Integration** | ✅ | ✅ | ✅ | W5 | ❌ |
| **Notifications** | ✅ | | | W6 | ❌ |
| **Walk-ins** | | ✅ | | W6 | ❌ |
| **Grooming FSM** | | | ✅ | W6 | ✅ |
| **Workforce** | ✅ | | | W6 | ❌ |
| **Reports** | | ✅ | | W6 | ❌ |
| **Audit Logs** | ✅ | | | W6 | ❌ |
| **Docker + Deploy** | | | ✅ | W6 | ❌ |
| **Tests** | ✅ | ✅ | ✅ | All | ❌ |

---

## 5. FSM SUMMARY (7 FSMs)

| FSM | States | Transitions | Guards | Owner | Week |
|-----|--------|-------------|--------|-------|------|
| **CaregiverInvitation** | INVITED, ACTIVE, REJECTED, EXPIRED, REVOKED | 5 | RULE-04-01 → 04-06 | P2 | W1 |
| **Appointment** | BOOKED, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW | 10 | RULE-06-01 → 06-09 | P1 | W2 |
| **Order** | PENDING_PAYMENT, PAID, CONFIRMED, PROCESSING, READY, DELIVERED, CANCELLED, REFUNDED | 10 | RULE-14-01 → 14-06 | P2 | W2 |
| **Payment** | PENDING, PROCESSING, SUCCESS, FAILED, CANCELLED, REFUNDED | 7 | RULE-16-01 → 16-05 | P3 | W2 |
| **Invoice** | DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID, REFUNDED | 7 | RULE-15-01 → 15-07 | P1 | W3 |
| **Refund** | REQUESTED, APPROVED, REJECTED, PROCESSING, COMPLETED, FAILED | 6 | RULE-17-01 → 17-06 | P1 | W3 |
| **Grooming** | WAITING, IN_PROGRESS, COMPLETED, CANCELLED | 6 | RULE-11-01 → 11-05 | P3 | W6 |

**Total: 51 FSM transitions to implement + test**

---

## 6. DEFINITION OF DONE

### Per Module:
- [ ] Entity + Migration (Flyway)
- [ ] Service + business logic
- [ ] Controller + REST endpoints
- [ ] DTOs (Request/Response)
- [ ] Exception handling (BusinessException)
- [ ] Unit tests (≥ 1 test per FSM transition)
- [ ] API documented

### Per FSM:
- [ ] Happy path: start → end state
- [ ] Invalid transitions: must throw exception
- [ ] Edge cases: timing, permissions, concurrent updates
- [ ] Guards validation

---

## 7. DAILY STANDUP TEMPLATE

```
📅 Hôm nay: [DD/MM]
👤 [Tên]: [Module]

✅ Đã làm:
   - Task 1
   - Task 2

⏳ Đang làm:
   - Task 3

❌ Blocker:
   - [Có/Không - Mô tả nếu có]

📅 Ngày mai:
   - Task 4
   - Task 5
```

---

## 8. GIT WORKFLOW

```
main (production)
└── develop (integration)
    ├── feature/auth
    ├── feature/users
    ├── feature/organizations
    ├── feature/stores
    ├── feature/pets
    ├── feature/caregivers
    ├── feature/products
    ├── feature/inventory
    ├── feature/appointments
    ├── feature/orders
    ├── feature/payments
    ├── feature/invoices
    ├── feature/refunds
    ├── feature/clinical
    ├── feature/vaccinations
    ├── feature/event-bridges
    ├── feature/fe-integration
    ├── feature/notifications
    ├── feature/walkins
    ├── feature/grooming
    ├── feature/workforce
    ├── feature/reports
    ├── feature/audit
    └── release/v1.0.0
```

### Commit Convention:
```
feat: add appointment booking FSM
feat: implement order checkout flow
fix: resolve check-in validation error
docs: update API contracts
test: add appointment FSM transition tests
refactor: simplify payment callback handler
```

---

## 9. RISK MANAGEMENT

| Risk | Impact | Mitigation |
|------|--------|------------|
| P1 delay blocks others | HIGH | P2, P3 parallel work on independent modules |
| FSM complexity | MEDIUM | Test each transition individually |
| Event bridge race conditions | MEDIUM | Use @TransactionalEventListener(AFTER_COMMIT) |
| FE integration challenges | MEDIUM | Daily sync during W5 |
| Clinical module too complex | MEDIUM | Focus on core examination → prescription flow |
| Docker issues at end | LOW | P3 starts Docker setup in W5 |

---

## 10. COMMUNICATION CHANNELS

| Channel | Purpose |
|---------|---------|
| **Daily Standup** | 15 phút/ngày, 9:00 AM |
| **Weekly Review** | Thứ 6, review tuần + planning tuần sau |
| **Async Updates** | Discord/Slack #petcare-dev |
| **Blocker Escalation** | Ngay lập tức qua chat |

---

## 11. NEXT STEPS

- [ ] Confirm team members (P1, P2, P3 names)
- [ ] Set up Git repo with branch structure
- [ ] Create Docker Compose environment
- [ ] Review FSM specs together
- [ ] Start W1: Setup + Auth

---

*Document generated: 2026-08-19*
*Version: 1.0*
