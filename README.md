# Pet Care - Pet Shop Management System

> **Version:** 1.0  
> **Date:** 2026-08-18  
> **Stack:** Java 21 + Spring Boot 3.5 + PostgreSQL + Redis

---

## 📋 Mục lục

- [Giới thiệu](#giới-thiệu)
- [Cấu trúc](#cấu-trúc)
- [Bắt đầu](#bắt-đầu)
- [Tài liệu](#tài-liệu)
- [Team](#team)

---

## Giới thiệu

Hệ thống quản lý chuỗi cửa hàng chăm sóc thú cưng - **Pet Care**.

### Tính năng chính

- ✅ Đặt lịch khám/làm đẹp
- ✅ Quản lý thú cưng
- ✅ Mua sản phẩm
- ✅ Thanh toán online/cash
- ✅ Hồ sơ y tế (Clinical)
- ✅ Quản lý tiêm phòng

### Tech Stack

| Layer | Tech |
|-------|------|
| Backend | Java 21, Spring Boot 3.5 |
| Database | PostgreSQL 16 |
| Cache | Redis 7 |
| Auth | JWT |
| Migration | Flyway |
| Build | Maven |

---

## Cấu trúc

```
Pet-care-new/
├── docs/                    # Tài liệu nghiệp vụ
│   ├── INDEX.md            # Mục lục
│   ├── MAPPING.md         # Docs ↔ Plan mapping
│   ├── 01-business-operations.md
│   ├── 02-business-rules.md
│   ├── 03-state-machines.md
│   └── 04-glossary.md
│
├── plans/                   # Kế hoạch triển khai
│   ├── Master-Plan.md     # Tổng hợp
│   ├── 01-architecture.md
│   ├── 02-database.md
│   ├── 03-scaffolding.md
│   ├── 04-api-contracts.md
│   ├── 05-timeline.md
│   ├── 07-fsm-specs.md
│   ├── 08-event-bridges.md
│   └── 09-checklist.md
│
├── BE/                     # Backend (Java Spring Boot)
│
└── FE/                     # Frontend (sẽ setup sau)
```

---

## Bắt đầu

### Yêu cầu

- Java 21+
- Maven 3.9+
- Docker Desktop

### Setup

```bash
# 1. Clone/Copy project

# 2. Setup Docker
cd BE
docker-compose up -d

# 3. Build
mvn clean package -DskipTests

# 4. Run
java -jar target/petcare-api-1.0.0.jar

# 5. Test
curl http://localhost:8080/actuator/health
```

Chi tiết: xem [plans/03-scaffolding.md](plans/03-scaffolding.md)

---

## Tài liệu

### Cho Developer

| Tài liệu | Mô tả |
|-----------|--------|
| [Master Plan](plans/Master-Plan.md) | Tổng hợp toàn bộ kế hoạch |
| [Architecture](plans/01-architecture.md) | Kiến trúc hệ thống |
| [Timeline](plans/05-timeline.md) | Chi tiết 6 tuần |
| [API Contracts](plans/04-api-contracts.md) | REST API specifications |
| [FSM Specs](plans/07-fsm-specs.md) | State Machine specifications |

### Cho Business

| Tài liệu | Mô tả |
|-----------|--------|
| [Business Operations](docs/01-business-operations.md) | Danh mục nghiệp vụ |
| [Business Rules](docs/02-business-rules.md) | Tất cả business rules |
| [Glossary](docs/04-glossary.md) | Ubiquitous language |

---

## 4 Phases

| Phase | Tuần | Mục tiêu |
|-------|------|-----------|
| **1. Foundation** | W1 | CRUD foundation |
| **2. Core Domain** | W2 | Appointments, Orders, Payments FSMs |
| **3. Commerce** | W3-W4 | Invoices, Refunds, Clinical |
| **4. Polish** | W5-W6 | Integration, Tests, Docker |

Chi tiết: [Master Plan](plans/Master-Plan.md)

---

## 5 Roles

| Role | Mô tả |
|------|--------|
| SUPER_ADMIN | Quản trị toàn hệ thống |
| STORE_MANAGER | Quản lý store |
| RECEPTIONIST | Tiếp khách |
| VETERINARIAN | Bác sĩ thú y |
| GROOMER | Stylist |
| CUSTOMER | Khách hàng |

---

## Team

| Person | Vai trò | Modules |
|--------|---------|---------|
| P1 (Lead) | Backend Lead | Auth, Users, Appointments, Invoices, Refunds |
| P2 | Business Logic | Pets, Orders, Clinical, Vouchers |
| P3 | Commerce | Products, Inventory, Payments, Vaccinations, Docker |

---

## License

MIT
# Pet-care-new