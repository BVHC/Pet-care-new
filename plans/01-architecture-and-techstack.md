# Pet-care-new: Architecture & Tech Stack

> **Date:** 2026-08-18  
> **Duration:** 6 tuần (30 ngày làm việc)  
> **Pattern:** Monolithic, Admin First

---

## 1. Tech Stack

| Layer | Tech | Version |
|---|---|---|
| **Runtime** | Java | 21 LTS |
| **Framework** | Spring Boot | 3.5.15 |
| **Database** | PostgreSQL | 16+ |
| **Migration** | Flyway | — |
| **Cache** | Redis | 7.x |
| **ORM** | Spring Data JPA + Hibernate | — |
| **Auth** | JWT (HS256) | — |
| **Build** | Maven | 3.9.x |
| **Container** | Docker Compose | — |

---

## 2. Roles (5 Roles - Final)

### Nhóm 1: Admin (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| SUPER_ADMIN | Quản trị toàn hệ thống, setup | Dev / PO |

### Nhóm 2: Store-Level (4 roles)

| Role | Mô tả | Ai |
|------|-------|-----|
| STORE_MANAGER | Quản lý store, duyệt refunds, inventory, finance, reports | Chủ store |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn, thu tiền | Lễ tân |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng, tạo bệnh án | Bác sĩ |
| GROOMER | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |

### Nhóm 3: Customer (1 role)

| Role | Mô tả | Ai |
|------|-------|-----|
| CUSTOMER | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 3. 4 Phases Overview

| Phase | Tuần | Modules | FSMs | Deliverable |
|-------|------|---------|------|-------------|
| **1. Foundation** | W1 | Auth, Users, Orgs, Stores, Pets, Products, Inventory | 0 | CRUD foundation |
| **2. Core Domain** | W2 | Appointments, Orders, Payments | 3 | Main FSMs working |
| **3. Commerce** | W3-W4 | Invoices, Refunds, Vouchers, Clinical, Vaccinations | 2 | Full commerce |
| **4. Polish** | W5-W6 | Integration, Tests, Docker, P2 features | 0 | Demo ready |

---

## 4. Modules chi tiết theo Phase

### Phase 1: Foundation (W1)

| # | Module | FSM? | Owner |
|---|--------|-------|-------|
| 1 | Auth + OTP | ❌ | P1 |
| 2 | Users | ❌ | P1 |
| 3 | Organizations | ❌ | P1 |
| 4 | Stores | ❌ | P1 |
| 5 | Pets + Caregivers | ❌ | P2 |
| 6 | Products | ❌ | P3 |
| 7 | Inventory | ❌ | P3 |

### Phase 2: Core Domain (W2)

| # | Module | FSM? | Owner |
|---|--------|-------|-------|
| 8 | **Appointments** | ✅ | P1 |
| 9 | **Orders + Cart** | ✅ | P2 |
| 10 | **Payments** | ✅ | P3 |

### Phase 3: Commerce (W3-W4)

| # | Module | FSM? | Owner |
|---|--------|-------|-------|
| 11 | **Invoices** | ✅ | P1 |
| 12 | **Refunds** | ✅ | P1 |
| 13 | Promotions + Vouchers | ❌ | P2 |
| 14 | **Clinical (FULL)** | ❌ | P2 |
| 15 | Vaccinations | ❌ | P3 |

### Phase 4: Polish (W5-W6)

| # | Module | FSM? | Owner |
|---|--------|-------|-------|
| 16 | FE Integration | ❌ | All |
| 17 | Notifications | ❌ | P1 |
| 18 | Walk-ins | ❌ | P2 |
| 19 | Grooming FSM | ✅ | P3 |
| 20 | Workforce | ❌ | P1 |
| 21 | Reports | ❌ | P2 |
| 22 | Audit Logs | ❌ | P1 |

---

## 5. FSMs Summary (7 FSMs)

| FSM | States | Transitions | Phase |
|-----|--------|-------------|-------|
| **CaregiverInvitation** | 5 | 5 | W1 |
| **Appointment** | 7 | 10 | W2 |
| **Order** | 8 | 10 | W2 |
| **Payment** | 6 | 7 | W2 |
| **Invoice** | 6 | 7 | W3 |
| **Refund** | 6 | 6 | W3 |
| **Grooming** | 4 | 6 | W6 |

---

## 6. Permissions Matrix

| Permission | SUPER_ADMIN | MANAGER | RECEPTION | VET | GROOMER | CUSTOMER |
|------------|:-----------:|:-------:|:---------:|:---:|:-------:|:--------:|
| system:* | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| store:create | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| store:manage | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| staff:manage | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| inventory:* | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| refund:approve | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| pet:view | ✅ | ✅ | ✅ | ✅ | ✅ | own |
| pet:create | ✅ | ✅ | ✅ | ❌ | ❌ | own |
| appointment:* | ✅ | ✅ | ✅ | ✅ | ❌ | own |
| order:* | ✅ | ✅ | ✅ | ❌ | ❌ | own |
| payment:* | ✅ | ✅ | record | ❌ | ❌ | own |
| invoice:* | ✅ | ✅ | ✅ | ❌ | ❌ | view own |
| medical:* | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| vaccination:* | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| grooming:* | ✅ | ✅ | ❌ | ❌ | ✅ | ❌ |
| notification:* | ✅ | ✅ | ✅ | ✅ | ✅ | own |
| report:* | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |

---

## 7. Project Structure

```
backend/
├── src/main/java/com/petcare/
│   ├── PetcareApplication.java
│   ├── common/
│   │   ├── config/         # Security, JWT
│   │   ├── security/      # JWT filter, UserPrincipal
│   │   ├── exception/      # GlobalExceptionHandler
│   │   └── model/          # ApiResponse, PageResponse
│   │
│   ├── modules/
│   │   ├── auth/           # Login, Register, OTP
│   │   ├── users/          # User CRUD
│   │   ├── organizations/  # Org + Stores
│   │   ├── pets/           # Pets + Caregivers
│   │   ├── appointments/   # FSM: BOOKED → COMPLETED
│   │   ├── products/       # Products + Categories
│   │   ├── inventory/      # Stock management
│   │   ├── orders/         # FSM: PENDING_PAYMENT → DELIVERED
│   │   ├── payments/       # FSM: PENDING → SUCCESS
│   │   ├── invoices/       # FSM: DRAFT → PAID
│   │   ├── refunds/        # FSM: REQUESTED → COMPLETED
│   │   ├── promotions/     # Vouchers
│   │   ├── clinical/       # Medical records, diagnoses
│   │   ├── vaccinations/  # Vaccine management
│   │   ├── notifications/  # In-app notifications
│   │   ├── walkins/        # Queue management
│   │   ├── grooming/       # Grooming FSM
│   │   ├── workforce/      # Work schedules
│   │   ├── reports/        # Analytics
│   │   └── audit/          # Audit logs
│   │
│   └── resources/
│       ├── application.yml
│       └── db/migration/   # Flyway scripts
│
├── src/test/
└── Dockerfile
```

---

## 8. Timeline

| Phase | Tuần | P1 | P2 | P3 |
|-------|------|-----|-----|-----|
| **1. Foundation** | W1 | Auth + Users + Orgs | Pets + Caregivers | Products + Inventory |
| **2. Core Domain** | W2 | Appointments FSM | Orders FSM | Payments FSM |
| **3. Commerce** | W3-W4 | Invoices + Refunds | Clinical (FULL) + Vouchers | Vaccinations |
| **4. Polish** | W5 | Integration | Integration | Docker + Deploy |
| **4. Polish** | W6 | Tests + Audit | Reports + Walk-ins | Grooming FSM |

Chi tiết: xem [05-timeline-and-team-allocation.md](05-timeline-and-team-allocation.md)