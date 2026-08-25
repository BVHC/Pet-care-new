# Pet Care - Pet Shop Management System

> **Version:** 2.0  
> **Date:** 2026-08-26  
> **Stack:** Java 21 + Spring Boot 3.5 + PostgreSQL + Redis  
> **GitHub:** https://github.com/BVHC/Pet-care-new

---

## 📋 Mục lục

- [Giới thiệu](#giới-thiệu)
- [Cấu trúc](#cấu-trúc)
- [Bắt đầu](#bắt-đầu)
- [Tài liệu](#tài-liệu)
- [Team](#team)
- [Database](#database)
- [Architecture](#architecture)

---

## Giới thiệu

Hệ thống quản lý chuỗi cửa hàng chăm sóc thú cưng - **Pet Care 2.0**. Xây dựng theo kiến trúc Domain-Driven Design (DDD) với Event-Driven pattern.

### Tính năng chính (22 modules)

| Phase | Modules |
|-------|---------|
| **Foundation (W1)** | Auth + OTP, Users, Organizations, Stores, Pets, Products, Inventory |
| **Core Domain (W2)** | Appointments FSM, Orders FSM, Payments FSM |
| **Commerce (W3-W4)** | Invoices FSM, Refunds FSM, Clinical, Vaccinations, Promotions |
| **Polish (W5-W6)** | FE Integration, Notifications, Walk-ins, Grooming FSM, Workforce, Reports, Audit |

### Tech Stack

| Layer | Tech |
|-------|------|
| Backend | Java 21, Spring Boot 3.5 |
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| Auth | JWT + OTP |
| Migration | Flyway |
| Build | Maven |
| Event | Transactional Outbox Pattern |

---

## Cấu trúc

```
Pet-care-new/
├── docs/                          # Tài liệu nghiệp vụ
│   ├── INDEX.md                   # Mục lục tài liệu
│   ├── MAPPING.md                 # Docs ↔ Plans mapping
│   ├── 06-team-timeline.md        # Timeline 6 tuần
│   ├── 01-business-operations.md  # Nghiệp vụ + Actors
│   ├── 02-business-rules.md       # Tất cả Business Rules (~150+)
│   ├── 03-state-machines.md       # FSM Specifications (8 core + 7 extended)
│   └── 04-glossary.md             # Ubiquitous Language
│
├── plans/                          # Kế hoạch triển khai
│   ├── Master-Plan.md             # Tổng hợp toàn bộ
│   ├── 01-architecture-and-techstack.md
│   ├── 02-database-and-implementation.md
│   ├── 03-step-by-step-scaffolding.md
│   ├── 04-api-contracts.md
│   ├── 05-timeline-and-team-allocation.md
│   ├── 07-fsm-detailed-specs.md
│   ├── 08-event-bridges.md
│   └── 09-module-checklist.md
│
├── docs/diagrams/                  # Database diagrams
│   ├── README.md                   # Hướng dẫn xem diagram
│   └── schema-overview.html        # Interactive schema viewer
│
├── BE/                            # Backend (Java Spring Boot)
│   └── src/main/java/com/petcare
│
└── FE/                            # Frontend (React)
```

---

## 8 FSMs (Finite State Machines)

| FSM | States | Transitions | Phase |
|-----|--------|-------------|-------|
| **Account** | PENDING_VERIFICATION → ACTIVE → LOCKED | 4 | W1 |
| **Store** | ACTIVE → SUSPENDED → DEACTIVATED → ARCHIVED | 6 | W1 |
| **Appointment** | BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED | 14 | W2 |
| **Order** | PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED | 13 | W2 |
| **Payment** | PENDING → PROCESSING → SUCCESS → PARTIALLY_REFUNDED → REFUNDED | 10 | W2 |
| **Invoice** | DRAFT → ISSUED → PARTIALLY_PAID → PAID → VOID | 11 | W3 |
| **Refund** | REQUESTED → APPROVED → PROCESSING → COMPLETED | 8 | W3 |
| **Grooming** | WAITING → IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL → COMPLETED | 9 | W6 |

---

## 9 Roles

| Role | Scope | Mô tả |
|------|-------|--------|
| **SUPER_ADMIN** | Platform | Quản trị toàn hệ thống |
| **ORG_ADMIN** | Organization | Quản lý chuỗi cửa hàng |
| **STORE_MANAGER** | Store | Quản lý vận hành store |
| **FINANCE_STAFF** | Store | Thu ngân, thanh toán, hoàn tiền |
| **INVENTORY_STAFF** | Store | Quản lý kho, chuyển kho |
| **RECEPTIONIST** | Store | Tiếp khách, check-in, tạo đơn |
| **VETERINARIAN** | Store | Khám bệnh, kê đơn, tiêm phòng |
| **GROOMER** | Store | Làm đẹp thú cưng |
| **CUSTOMER** | User | Khách hàng |

---

## 4 Phases (6 tuần)

| Phase | Tuần | Milestone | Deliverable |
|-------|------|-----------|-------------|
| **1. Foundation** | W1 | M1: CRUD Foundation | Auth, Users, Organizations, Stores, Pets, Products, Inventory |
| **2. Core Domain** | W2 | M2: FSMs Working | Appointments, Orders, Payments FSMs |
| **3. Commerce** | W3-W4 | M3: Full Commerce | Invoices, Refunds, Clinical, Vaccinations, Event Bridges |
| **4. Polish** | W5-W6 | M4: Demo Ready | FE Integration, Docker, Reports, Audit |

---

## Bắt đầu

### Yêu cầu

- Java 21+
- Maven 3.9+
- Docker Desktop
- PostgreSQL 16
- Redis 7

### Setup

```bash
# 1. Clone repository
git clone https://github.com/BVHC/Pet-care-new.git
cd Pet-care-new

# 2. Setup Docker
cd BE
docker-compose up -d

# 3. Build
mvn clean package -DskipTests

# 4. Run
java -jar target/petcare-api-1.0.0.jar

# 5. Verify
curl http://localhost:8080/actuator/health
```

Chi tiết: [plans/03-step-by-step-scaffolding.md](plans/03-step-by-step-scaffolding.md)

---

## Tài liệu

### Cho Developer

| Tài liệu | Mô tả |
|-----------|--------|
| [Master Plan](plans/Master-Plan.md) | Tổng hợp toàn bộ kế hoạch |
| [Architecture](plans/01-architecture-and-techstack.md) | Kiến trúc hệ thống |
| [Database](plans/02-database-and-implementation.md) | Database design + Flyway migrations |
| [Timeline](plans/05-timeline-and-team-allocation.md) | Chi tiết 6 tuần |
| [API Contracts](plans/04-api-contracts.md) | REST API specifications |
| [FSM Specs](plans/07-fsm-detailed-specs.md) | State Machine specifications |
| [Event Bridges](plans/08-event-bridges.md) | Event-driven architecture |
| [Module Checklist](plans/09-module-checklist.md) | Implementation checklist |
| [Database Diagrams](docs/diagrams/) | Interactive schema viewer |

### Cho Business

| Tài liệu | Mô tả |
|-----------|--------|
| [Business Operations](docs/01-business-operations.md) | Danh mục nghiệp vụ + Actors |
| [Business Rules](docs/02-business-rules.md) | Tất cả business rules (~150+) |
| [State Machines](docs/03-state-machines.md) | FSM Specifications |
| [Glossary](docs/04-glossary.md) | Ubiquitous language |

### Reference

| Tài liệu | Mô tả |
|-----------|--------|
| [Docs Index](docs/INDEX.md) | Mục lục tài liệu |
| [Docs Mapping](docs/MAPPING.md) | Docs ↔ Plans cross-reference |
| [Team Timeline](docs/06-team-timeline.md) | Team allocation + detailed schedule |

---

## Database

Xem chi tiết: [docs/diagrams/schema-overview.html](docs/diagrams/schema-overview.html)

### Core Entities

```
Account (Auth)
    └── User (9 roles)
           ├── Organization (ORG_ADMIN)
           │      └── Store (STORE_MANAGER + 5 store roles)
           │             ├── StoreResource
           │             ├── StoreOperatingHour
           │             ├── Appointment
           │             ├── Order
           │             ├── Payment
           │             ├── Invoice
           │             ├── Refund
           │             └── Grooming
           ├── Pet
           │      ├── MedicalRecord
           │      ├── Prescription
           │      ├── Vaccination
           │      └── GroomingRecord
           └── Cart → CartItem
```

### Key Design Patterns

| Pattern | Usage |
|---------|-------|
| **Transactional Outbox** | Event-driven bridges (D-04 approved) |
| **Optimistic Locking** | Concurrent FSM transitions |
| **Soft Delete** | All entities support archival |
| **Physical + Reserved Quantity** | Inventory with 15-min TTL reservation |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Frontend (FE)                           │
│                    React + TypeScript + Vite                    │
└────────────────────────────┬────────────────────────────────────┘
                             │ REST API
┌────────────────────────────▼────────────────────────────────────┐
│                      API Layer (BE)                             │
│  Controllers → DTOs → Services → Domain Events                  │
├─────────────────────────────────────────────────────────────────┤
│                     Domain Layer                                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │   Aggregates │ │    Events    │ │   Guards     │            │
│  │  (Entities)  │ │  (Outbox)    │ │  (Rules)     │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
├─────────────────────────────────────────────────────────────────┤
│                  Infrastructure Layer                            │
│  Repository → PostgreSQL (Flyway) → Redis (Cache)              │
└─────────────────────────────────────────────────────────────────┘
```

### Event Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│  Payment    │────▶│   Outbox    │────▶│   Invoice   │
│  SUCCESS    │     │  (Async)    │     │   UPDATE    │
└─────────────┘     └─────────────┘     └─────────────┘
```

---

## Team

| Person | Role | Modules |
|--------|------|---------|
| P1 (Lead) | Backend Lead | Auth, Users, Organizations, Stores, Appointments, Invoices, Refunds, Workforce, Audit, Notifications |
| P2 | Business Logic | Pets, Orders, Clinical, Promotions, Walk-ins, Reports |
| P3 | Commerce + Integration | Products, Inventory, Payments, Vaccinations, Grooming, Docker |

Chi tiết: [docs/06-team-timeline.md](docs/06-team-timeline.md)

---

## Commit History

| Commit | Date | Description |
|--------|------|------------|
| C-565e7b1 | 2026-08-24 | **Major Update**: 8 FSMs, StoreResources, Inventory Reserve TTL, Outbox Pattern |
| ... | ... | Previous versions |

---

## License

MIT

---

*Last updated: 2026-08-26*
