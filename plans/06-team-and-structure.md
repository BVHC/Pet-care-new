# Pet-care-new: Team Allocation & Folder Structure

> **Date:** 2026-08-25  
> **Duration:** 6 tuần  
> **Team:** BE-DEV 1 (Lead) · BE-DEV 2 · FE-DEV 3  
> **Pattern:** 2 Backend + 1 Frontend Specialist

---

## 1. Team Structure

| Role | Owner | Modules |
|------|-------|---------|
| BE-DEV 1 (Lead) | Core Backend | Auth, Users, Orgs/Stores, Appointments, Invoices, Refunds, Notifications, Workforce, Audit, Grooming |
| BE-DEV 2 | Business Logic | Pets, Orders, Clinical, Walk-ins, Vouchers, Reports |
| FE-DEV 3 | Frontend | All screens (Auth, Dashboard, Appointments, Orders, Cart, Payments, Clinical, Products, Inventory, Vaccinations, Grooming) |

---

## 2. Folder Structure Review

### Current Status (Pre-Week 1)

| Aspect | Status |
|--------|--------|
| BE modular structure | ✅ Done |
| FE structure | ✅ Done |
| Shared utilities | ✅ Done |
| DTO layer | ✅ Sample |
| Migration scripts | ✅ V1__init_schema.sql |
| Environment config | ✅ .env.example |
| Git ignore | ✅ Done |

### Module Pattern (Backend)

```
modules/{module}/
├── config/                       # Module-specific config
├── domain/
│   ├── entity/
│   ├── enums/
│   └── event/
├── dto/
│   ├── request/
│   └── response/
├── repository/
└── service/
    ├── {Module}Service.java
    └── impl/
        └── {Module}ServiceImpl.java
```

### Domain Pattern (Frontend)

```
domains/{domain}/
├── components/                   # Domain components
├── hooks/                        # Domain hooks
├── types/                        # Domain types
└── api/                          # Domain API
```

---

## 3. Milestones

| Milestone | Deadline | Criteria |
|----------|----------|----------|
| **M1: Foundation** | End of W1 | Auth, Users, Orgs, Pets, Products, Inventory CRUD |
| **M2: Core FSM** | End of W2 | Appointments, Orders, Payments FSM với tests |
| **M3: Commerce** | End of W4 | Invoices, Refunds, Clinical, Vaccinations working |
| **M4: Demo Ready** | End of W6 | FE integration, Docker, Tests |

---

For detailed tasks, see [07-tasks-and-specs.md](07-tasks-and-specs.md).
