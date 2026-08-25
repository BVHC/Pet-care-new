# Pet-care-new: Master Plan - Tổng hợp

> **Date:** 2026-08-18  
> **Duration:** 6 tuần (30 ngày làm việc)  
> **Team:** 3 developers  
> **Pattern:** Monolithic, Admin First

---

## Tóm tắt

| Aspect | Value |
|--------|-------|
| **Phases** | 4 |
| **Tuần** | 6 |
| **Modules** | 22 |
| **FSMs** | 8 (Updated per C-565e7b1) |
| **Roles** | 8 (SUPER_ADMIN, ORG_ADMIN, STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER, CUSTOMER) |
| **Rules** | ~150+ |

---

## 1. 4 Phases Overview

### Phase 1: Foundation (W1)
**Goal:** CRUD foundation cho tất cả modules

| Module | Owner | FSM |
|--------|-------|-----|
| Auth + OTP | P1 | ❌ |
| Users | P1 | ❌ |
| Organizations | P1 | ❌ |
| Stores | P1 | ❌ |
| Pets | P2 | ✅ |
| Products | P3 | ❌ |
| Inventory | P3 | ❌ |

**Deliverable:** ✅ Foundation CRUD working

---

### Phase 2: Core Domain (W2)
**Goal:** 3 FSMs chính - Appointments, Orders, Payments

| Module | Owner | FSM |
|--------|-------|-----|
| **Appointments** | P1 | ✅ |
| **Orders + Cart** | P2 | ✅ |
| **Payments** | P3 | ✅ |

**Deliverable:** ✅ 3 FSMs working với tests

---

### Phase 3: Commerce (W3-W4)
**Goal:** Invoices, Refunds, Vouchers, Clinical (FULL), Vaccinations

| Module | Owner | FSM |
|--------|-------|-----|
| **Invoices** | P1 | ✅ |
| **Refunds** | P1 | ✅ |
| Clinical (FULL) | P2 | ❌ |
| Promotions + Vouchers | P2 | ❌ |
| Vaccinations | P3 | ❌ |
| Event Bridges | All | ❌ |

**Deliverable:** ✅ Full commerce + Clinical working

---

### Phase 4: Polish (W5-W6)
**Goal:** FE Integration, Tests, Docker, P2 features

| Module | Owner | Priority |
|--------|-------|----------|
| FE Integration | All | P1 |
| Notifications | P1 | P2 |
| Walk-ins + Appointment Bridge | P2 | P2 |
| **Grooming FSM + Customer Approval** | P3 | P2 |
| Workforce | P1 | P2 |
| Reports | P2 | P2 |
| Audit Logs + Medical Access | P1 | P2 |
| Docker + Deploy | P3 | P1 |

**Deliverable:** ✅ Demo ready

---

## 2. Roles (8 Roles)

| Scope | Role | Mô tả | Ai |
|-------|------|-------|-----|
| Platform | **SUPER_ADMIN** | Quản trị toàn hệ thống, setup | Dev / PO |
| Organization | **ORG_ADMIN** | Quản lý chuỗi cửa hàng | Chủ chuỗi |
| Store | **STORE_MANAGER** | Quản lý store, duyệt refunds, reports | Chủ store |
| Store | **FINANCE_STAFF** | Thu ngân, thanh toán, hoàn tiền, đối soát | Kế toán |
| Store | **INVENTORY_STAFF** | Quản lý kho, chuyển kho, mua hàng | Thủ kho |
| Store | **RECEPTIONIST** | Tiếp khách, check-in, tạo đơn, thu tiền | Lễ tân |
| Store | **VETERINARIAN** | Khám bệnh, kê đơn, tiêm phòng, tạo bệnh án | Bác sĩ |
| Store | **GROOMER** | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |
| User | **CUSTOMER** | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 3. Team Members

| Person | Vai trò | Modules |
|--------|---------|---------|
| **P1 (Lead)** | Backend Lead | Auth, Users, Orgs, Stores, Appointments, Invoices, Refunds, Notifications, Workforce, Audit |
| **P2** | Business Logic | Pets, Orders, Clinical (FULL), Vouchers, Reports, Walk-ins |
| **P3** | Commerce + Integration | Products, Inventory, Payments, Vaccinations, Grooming, Docker |

---

## 4. FSMs Summary (8 FSMs - Updated per C-565e7b1)

| FSM | States | Transitions | Guards | Phase |
|-----|--------|-------------|--------|-------|
| **Account** | 3 | 4 | RULE-01-01 → 01-05 | W1 |
| **Store** | 4 | 6 | RULE-03-01 → 03-06 | W1 |
| **Appointment** | 7 | 14 | RULE-06-01 → 06-10 | W2 |
| **Order** | 8 | 13 | RULE-14-01 → 14-07 | W2 |
| **Payment** | 7 | 10 | RULE-16-01 → 16-05 | W2 |
| **Invoice** | 6 | 11 | RULE-15-01 → 15-07 | W3 |
| **Refund** | 6 | 8 | RULE-17-01 → 17-07 | W3 |
| **Grooming** | 5 | 9 | RULE-11-01 → 11-05 | W6 |

**Tổng: 8 FSMs, ~75 transitions**

---

## 5. Timeline chi tiết

### Phase 1: Foundation (W1)

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Maven setup + Docker Compose | — | — |
| Tue | SecurityConfig + JWT + Auth | — | — |
| Wed | Users + Organizations | Pets entity | Products entity |
| Thu | Stores + Operating hours | PetService CRUD | ProductService |
| Fri | Inventory config | Pet validations | Inventory module |

**Deliverable:** ✅ Auth, Users, Orgs, Pets, Products, Inventory

---

### Phase 2: Core Domain (W2)

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Appointments entity | Cart entity | Payment entity |
| Tue | Appointments FSM (book, confirm) | Orders entity + FSM | Payments FSM |
| Wed | Appointments FSM (check-in, start) | Orders FSM (confirm, process) | Payments FSM (verify, callback) |
| Thu | Appointments FSM (reschedule, cancel) | Orders FSM (prepare, deliver, timeout) | Payments FSM (cancel, partial refund) |
| Fri | AppointmentController + StoreResource collision | OrderController + Inventory reserve | PaymentController + tests |

**Deliverable:** ✅ Appointments + Orders + Payments FSMs (with StoreResource + Inventory Reservation + 15min TTL)

---

### Phase 3: Commerce (W3-W4)

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Invoice entity + FSM | MedicalRecord + Cross-Store Consent | Voucher entity |
| Tue | Invoice FSM + Partially Paid | ClinicalService + Emergency Override | Vaccination + Barcode schema |
| Wed | Refund entity + FSM + 30-day window | Prescription + FollowUp | Vaccination + Barcode scanning |
| Thu | Refund FSM (approve, reject, retry, manual) | MedicalHistory + permissions | Voucher validation |
| Fri | Refund FSM (process, complete) | ClinicalController + tests | Vaccination tests |

**W4: Event bridges + Integration**

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | Event: Payment → Order/Invoice | Event: Appointment → Invoice | Integration tests |
| Tue | Event: Refund → Payment/Order | Event: Clinical → Invoice | Integration tests |
| Wed | Integration: Order → Invoice | Integration: Clinical → Invoice | Bug fixes |
| Thu | Bug fixes | Bug fixes | Bug fixes |
| Fri | Commerce polish | Clinical polish | Commerce polish |

**Deliverable:** ✅ Full commerce + Clinical

---

### Phase 4: Polish (W5-W6)

**W5: FE Integration**

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | FE Auth + Appointments | FE Pets + Orders | FE Products + Cart |
| Tue | FE Appointments flow | FE Clinical | FE Vaccinations |
| Wed | FE Invoices + Refunds | FE Vouchers | FE Payments |
| Thu | Fix FE/BE bugs | Fix FE/BE bugs | Fix FE/BE bugs |
| Fri | API docs | API docs | Docker setup |

**W6: Tests + Docker + P2**

| Day | P1 (Lead) | P2 | P3 |
|-----|-----------|-----|-----|
| Mon | E2E tests: Customer | E2E tests: Staff | Docker compose |
| Tue | FSM unit tests | FSM unit tests | Docker image |
| Wed | **Notifications** | **Walk-ins** | **Grooming FSM** |
| Thu | **Workforce** | **Reports** | Grooming integration |
| Fri | Demo prep | Demo prep | Demo prep |

**Deliverable:** ✅ Demo ready

---

## 6. Files cần tạo/sửa

### Plans đã update

| File | Status | Nội dung |
|------|--------|-----------|
| `01-architecture.md` | ✅ Updated | 5 roles, 4 phases, 22 modules |
| `02-database.md` | ✅ Updated | Full schema (19 migrations) |
| `03-scaffolding.md` | ✅ Updated | Setup guide 6 tuần |
| `04-api-contracts.md` | ✅ Updated | REST API cho 22 modules |
| `05-timeline.md` | ✅ Updated | Chi tiết 6 tuần |
| `07-fsm-specs.md` | ✅ Updated | 7 FSMs |
| `08-event-bridges.md` | ✅ Updated | Cross-aggregate events |
| `09-checklist.md` | ✅ Updated | 22 modules checklist |
| `Master-Plan.md` | ✅ This | Tổng hợp |

### Docs (cần copy từ base)

| File | Source | Action |
|------|--------|--------|
| `docs/01-business-operations.md` | Pet-care/docs | Copy |
| `docs/02-business-rules.md` | Pet-care/docs | Copy |
| `docs/03-state-machines.md` | Pet-care/docs | Copy |
| `docs/04-glossary.md` | Pet-care/docs | Copy |

---

## 7. Milestones

| Milestone | Date | Criteria |
|-----------|------|----------|
| **M1: Foundation** | End of W1 | Auth, Users, Orgs, Pets, Products, Inventory CRUD |
| **M2: Core FSM** | End of W2 | Appointments, Orders, Payments FSM với tests |
| **M3: Commerce** | End of W4 | Invoices, Refunds, Clinical, Vaccinations |
| **M4: Demo Ready** | End of W6 | FE integration, Docker, Tests |

---

## 8. Next Steps - Checklist

### W0 (Preparation)

- [ ] ✅ Đồng ý plan này
- [ ] Copy docs từ Pet-care/docs
- [ ] Setup Git repo
- [ ] Review all plans
- [ ] Bắt đầu W1

### Questions cần trả lời

1. **Git repo:** Tạo repo mới hay continue từ base?
2. **Database:** Reset hoàn toàn hay migrate từ base?
3. **FE:** Giữ nguyên hay refactor?
4. **Demo date:** Ngày demo là khi nào?
