# Pet Care - Tài liệu dự án

> **Version:** 1.0  
> **Date:** 2026-08-18  
> **Scope:** 22 modules, 6 FSMs, 6 tuần

---

## 📚 Danh mục tài liệu

| # | File | Mô tả |
|---|------|--------|
| 1 | [01-business-operations.md](01-business-operations.md) | Danh mục nghiệp vụ + Actors |
| 2 | [02-business-rules.md](02-business-rules.md) | Tất cả Business Rules |
| 3 | [03-state-machines.md](03-state-machines.md) | FSM Specifications |
| 4 | [04-glossary.md](04-glossary.md) | Ubiquitous Language |
| 5 | [06-team-timeline.md](06-team-timeline.md) | Timeline 6 tuần |
| 6 | [MAPPING.md](MAPPING.md) | Mapping docs ↔ plans |

---

## 🎯 Tóm tắt Project (Updated per C-565e7b1)

| Aspect | Value |
|--------|-------|
| **Phases** | 4 |
| **Tuần** | 6 |
| **Modules** | 22 |
| **FSMs** | 8 (Account, Store, Appointment, Order, Payment, Invoice, Refund, Grooming) |
| **Roles** | 9 (SUPER_ADMIN, ORG_ADMIN, STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER, CUSTOMER) |
| **Rules** | ~150+ |

---

## 👥 Roles (8 Roles - FINAL per Reconciliation 2026-08-24)

> **Approved:** D-01 — Restore ORG_ADMIN for multi-tenant security

### Platform Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **SUPER_ADMIN** | Quản trị toàn hệ thống, setup platform | Dev/PO |

### Organization Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **ORG_ADMIN** | Quản lý chuỗi cửa hàng, setup organization | Chủ chuỗi |

### Store Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **STORE_MANAGER** | Quản lý vận hành store | Chủ store |
| **FINANCE_STAFF** | Thu ngân, thanh toán, hoàn tiền | Dưới Manager |
| **INVENTORY_STAFF** | Quản lý kho, chuyển kho | Dưới Manager |
| **RECEPTIONIST** | Tiếp khách, check-in, tạo đơn | Lễ tân |
| **VETERINARIAN** | Khám bệnh, kê đơn, tiêm phòng | Bác sĩ |
| **GROOMER** | Làm đẹp thú cưng | Stylist |

### User Scope

| Role | Mô tả | Notes |
|------|--------|-------|
| **CUSTOMER** | Khách hàng | Đặt lịch, mua hàng, quản lý thú cưng |

### RBAC Summary

| Scope | Roles |
|-------|-------|
| Platform | SUPER_ADMIN |
| Organization | ORG_ADMIN |
| Store | STORE_MANAGER, FINANCE_STAFF, INVENTORY_STAFF, RECEPTIONIST, VETERINARIAN, GROOMER |
| User | CUSTOMER |

---

## 📦 Modules (22 modules)

### Phase 1: Foundation (W1)

| Module | Docs | Commands |
|--------|-------|----------|
| Auth + OTP | 01, 02 | RULE-01-xx |
| Users | 01, 02 | RULE-02-xx |
| Organizations | 01, 02 | RULE-03-xx |
| Stores | 01, 02 | RULE-03-xx |
| Pets | 01, 02 | RULE-04-xx |
| Products | 01, 02 | RULE-05-xx |
| Inventory | 01, 02 | RULE-12-xx |

### Phase 2: Core Domain (W2)

| Module | Docs | Commands |
|--------|-------|----------|
| **Appointments (FSM)** | 01, 02, 03 | RULE-06-xx |
| **Orders (FSM)** | 01, 02, 03 | RULE-14-xx |
| **Payments (FSM)** | 01, 02, 03 | RULE-16-xx |

### Phase 3: Commerce (W3-W4)

| Module | Docs | Commands |
|--------|-------|----------|
| **Invoices (FSM)** | 01, 02, 03 | RULE-15-xx |
| **Refunds (FSM)** | 01, 02, 03 | RULE-17-xx |
| Clinical | 01, 02 | RULE-09-xx |
| Promotions | 01, 02 | RULE-18-xx |
| Vaccinations | 01, 02 | RULE-10-xx |

### Phase 4: Polish (W5-W6)

| Module | Docs | Commands |
|--------|-------|----------|
| FE Integration | - | - |
| Notifications | 01, 02 | RULE-23-xx |
| Walk-ins | 01, 02 | RULE-07-xx |
| **Grooming (FSM)** | 01, 02, 03 | RULE-11-xx |
| Workforce | 01, 02 | RULE-08-xx |
| Reports | 01, 02 | RULE-24-xx |
| Audit Logs | 01, 02 | RULE-25-xx |

---

## 🔄 FSMs (8 FSMs - Updated per C-565e7b1)

### Chi tiết FSMs:

| FSM | States | Docs | Phase | Notable |
|-----|--------|------|-------|---------|
| **Account** | PENDING_VERIFICATION → ACTIVE → LOCKED | 03 | W1 | 3 states, 4 transitions |
| **Store** | ACTIVE → SUSPENDED → DEACTIVATED → ARCHIVED | 03 | W1 | 4 states, 6 transitions |
| **Appointment** | BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED | 03 | W2 | +RescheduleAppointment |
| **Order** | PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED | 03 | W2 | +ProcessOrderTimeout (15min), +CancelOrderWithRefund |
| **Payment** | PENDING → PROCESSING → SUCCESS → PARTIALLY_REFUNDED → REFUNDED | 03 | W2 | +PARTIALLY_REFUNDED state |
| **Invoice** | DRAFT → ISSUED → PARTIALLY_PAID → PAID → VOID | 03 | W3 | +PARTIALLY_PAID state |
| **Refund** | REQUESTED → APPROVED → PROCESSING → COMPLETED → FAILED | 03 | W3 | +RetryRefund, +30-day window |
| **Grooming** | WAITING → IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL → COMPLETED | 03 | W6 | +AWAITING_CUSTOMER_APPROVAL |

---

## 🔗 Links

### Plans

- [Master Plan](../plans/Master-Plan.md)
- [Architecture](../plans/01-architecture-and-techstack.md)
- [Database](../plans/02-database-and-implementation.md)
- [Timeline](../plans/05-timeline-and-team-allocation.md)
- [FSM Specs](../plans/07-fsm-detailed-specs.md)
- [API Contracts](../plans/04-api-contracts.md)

### Source code

```
../BE/                     # Backend Java Spring Boot
../FE/                     # Frontend (copy từ Pet-care)
```

---

## 📅 Timeline

| Phase | Tuần | Milestone |
|-------|------|-----------|
| **1. Foundation** | W1 | M1: CRUD foundation |
| **2. Core Domain** | W2 | M2: FSMs working |
| **3. Commerce** | W3-W4 | M3: Full commerce |
| **4. Polish** | W5-W6 | M4: Demo ready |

---

## ❓ Questions

1. **Git repo:** Tạo mới hay continue từ base?
2. **Database:** Reset hay migrate từ base?
3. **FE:** Giữ nguyên hay refactor?
4. **Demo date:** Khi nào demo?
