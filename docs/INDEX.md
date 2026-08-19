# Pet Care - Tài liệu dự án

> **Version:** 1.0  
> **Date:** 2026-08-18  
> **Scope:** 22 modules, 7 FSMs, 6 tuần

---

## 📚 Danh mục tài liệu

| # | File | Mô tả |
|---|------|--------|
| 1 | [01-business-operations.md](01-business-operations.md) | Danh mục nghiệp vụ + Actors |
| 2 | [02-business-rules.md](02-business-rules.md) | Tất cả Business Rules |
| 3 | [03-state-machines.md](03-state-machines.md) | FSM Specifications |
| 4 | [04-glossary.md](04-glossary.md) | Ubiquitous Language |

---

## 🎯 Tóm tắt Project

| Aspect | Value |
|--------|-------|
| **Phases** | 4 |
| **Tuần** | 6 |
| **Modules** | 22 |
| **FSMs** | 7 |
| **Roles** | 5 |
| **Rules** | ~150 |

---

## 👥 Roles (5 Roles - Final)

| Role | Mô tả | Từ docs cũ |
|------|--------|-------------|
| **SUPER_ADMIN** | Quản trị toàn hệ thống | = Platform Admin |
| **STORE_MANAGER** | Quản lý store, duyệt refunds | = StoreManager + InventoryStaff + FinanceStaff |
| **RECEPTIONIST** | Tiếp khách, check-in, tạo đơn | Giữ nguyên |
| **VETERINARIAN** | Khám bệnh, kê đơn, tiêm phòng | Giữ nguyên |
| **GROOMER** | Làm đẹp thú cưng | Giữ nguyên |
| **CUSTOMER** | Khách hàng | Giữ nguyên |

### Roles đã gộp/bỏ:

| Role cũ | Xử lý |
|---------|--------|
| ORGANIZATION_ADMIN | Gộp vào SUPER_ADMIN |
| INVENTORY_STAFF | Gộp vào STORE_MANAGER |
| FINANCE_STAFF | Gộp vào STORE_MANAGER |
| CAREGIVER | Bỏ (không cần riêng) |

---

## 📦 Modules (22 modules)

### Phase 1: Foundation (W1)

| Module | Docs | Commands |
|--------|-------|----------|
| Auth + OTP | 01, 02 | RULE-01-xx |
| Users | 01, 02 | RULE-02-xx |
| Organizations | 01, 02 | RULE-03-xx |
| Stores | 01, 02 | RULE-03-xx |
| Pets + Caregivers | 01, 02 | RULE-04-xx |
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

## 🔄 FSMs (7 FSMs)

### Chi tiết FSMs:

| FSM | States | Docs | Phase |
|-----|--------|------|-------|
| **CaregiverInvitation** | INVITED → ACTIVE → REVOKED | 03 | W1 |
| **Appointment** | BOOKED → CONFIRMED → COMPLETED | 03 | W2 |
| **Order** | PENDING_PAYMENT → DELIVERED | 03 | W2 |
| **Payment** | PENDING → SUCCESS | 03 | W2 |
| **Invoice** | DRAFT → PAID | 03 | W3 |
| **Refund** | REQUESTED → COMPLETED | 03 | W3 |
| **Grooming** | WAITING → COMPLETED | 03 | W6 |

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
