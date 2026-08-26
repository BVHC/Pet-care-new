# Plan: Migrate FE Components từ Pet-care → Pet-care-new

## 1. So sánh 2 Projects

| Aspect | Pet-care (cũ) | Pet-care-new (mới) |
|--------|---------------|---------------------|
| Router | TanStack Router (file-based) | React Router 6 (manual) |
| Styling | CSS Modules | CSS (default Vite) |
| Pages | 20+ pages (admin, customer, staff, public) | 8 pages cơ bản |
| Components | 15+ UI components | 4 components (Button, Input, Card, Select) |
| Layouts | 8 layouts (Admin, Customer, Staff, Public...) | Chưa có layout system |
| Hooks | usePagination, useDebounce, usePermission... | Chưa có |
| Stores | Zustand (auth, cart, order, global, modal) | Chưa có |
| Types | 10+ domain types | 20+ types (đã định nghĩa sẵn) |

## 2. Components Có Thể Migrate (100% tương thích)

### 2.1 Layouts (reuse nguyên)
```
Pet-care/FE/src/shared/components/layout/
├── PublicLayout.tsx    → Customer-facing layout (header/footer)
├── SiteHeader.tsx      → Navigation header
├── SiteFooter.tsx      → Footer
├── AuthLayout.tsx      → Login/Register wrapper
└── DashboardLayout.tsx → Staff/Admin dashboard wrapper
```

### 2.2 UI Components (adapted styling)
```
Pet-care/FE/src/shared/components/ui/
├── Button.tsx          → Cần verify props
├── Badge.tsx           → Status badges
├── ProductCard.tsx     → Product display card
├── StarRatingInput.tsx → Review star input
├── Stars.tsx           → Static star display
├── IconCircle.tsx      → Icon wrapper
└── Placeholder.tsx     → Loading placeholder
```

### 2.3 Shared Utilities (direct copy)
```
Pet-care/FE/src/shared/
├── hooks/useDebounce.ts    → Debounce hook
├── hooks/usePagination.ts  → Pagination logic
├── services/toast.service.ts → Toast notifications
└── utils/format.ts         → Date/price formatting
```

### 2.4 Page Templates (cần adapt API calls)
```
Pet-care/FE/src/pages/
├── public/HomePage.tsx       → Landing page template
├── public/LoginPage.tsx      → Login form reference
├── public/RegisterPage.tsx   → Register form reference
├── customer/shop/            → Cart, Checkout, Order pages
└── customer/account/         → Account pages
```

## 3. Components Cần Tạo Mới

### 3.1 Layout System (chưa có)
- [ ] `src/shared/components/layouts/MainLayout.tsx` - wrapper chính
- [ ] `src/shared/components/layouts/AdminLayout.tsx` - admin sidebar + header
- [ ] `src/shared/components/layouts/StaffLayout.tsx` - staff navigation

### 3.2 API Integration (theo BE mới)
- [ ] `src/domains/auth/api/auth.api.ts` - login/logout/register
- [ ] `src/domains/pet/api/pet.api.ts` - CRUD pets
- [ ] `src/domains/order/api/order.api.ts` - orders
- [ ] `src/domains/appointment/api/appointment.api.ts` - appointments

### 3.3 State Management
- [ ] `src/shared/stores/auth.store.ts` - auth state
- [ ] `src/shared/stores/cart.store.ts` - shopping cart
- [ ] `src/shared/stores/ui.store.ts` - modals, toasts

## 4. Migration Steps

### Step 1: Setup Layout System (2h)
- [ ] Copy `PublicLayout.tsx`, `SiteHeader.tsx`, `SiteFooter.tsx`
- [ ] Copy `AuthLayout.tsx`, `DashboardLayout.tsx`
- [ ] Update imports trong App.tsx/router.tsx
- [ ] Test navigation flows

### Step 2: Migrate UI Components (3h)
- [ ] Copy Button → verify variant props
- [ ] Copy Badge → adapt status colors
- [ ] Copy ProductCard → adapt API response mapping
- [ ] Copy Stars/StarRatingInput → for reviews
- [ ] Copy IconCircle, Placeholder

### Step 3: Migrate Utilities (1h)
- [ ] Copy `useDebounce.ts`
- [ ] Copy `usePagination.ts`
- [ ] Copy `toast.service.ts`
- [ ] Copy `format.ts`

### Step 4: Create API Layer (3h)
- [ ] Setup axios instance với interceptors
- [ ] Create auth API (login/logout/register)
- [ ] Create pet API (CRUD)
- [ ] Create order/cart API

### Step 5: Setup State Management (2h)
- [ ] Install Zustand
- [ ] Create auth store
- [ ] Create cart store
- [ ] Create UI store

### Step 6: Migrate Pages (4h)
- [ ] HomePage - landing với services
- [ ] LoginPage/RegisterPage - với new API
- [ ] ShopPage/ProductDetailPage - product listing
- [ ] CartPage/CheckoutPage - shopping flow
- [ ] AccountPage/PetsPage - customer account

## 5. Effort Estimate

| Task | Time | Priority |
|------|------|----------|
| Layout System | 2h | HIGH |
| UI Components | 3h | HIGH |
| Utilities | 1h | MEDIUM |
| API Layer | 3h | HIGH |
| State Management | 2h | MEDIUM |
| Pages | 4h | LOW |

**Total: ~15h**

## 6. Risks & Considerations

1. **API Response Format khác** - Pet-care-new types đã define sẵn, cần verify mapping
2. **Styling conflicts** - CSS Modules vs plain CSS
3. **Router differences** - TanStack Router vs React Router 6
4. **State management** - Cũ dùng gì? Cần check trước khi migrate

## 7. Files to Copy (Ready to Migrate)

```
✅ SHARED (100% reusable)
├── src/shared/components/layout/PublicLayout.tsx
├── src/shared/components/layout/SiteHeader.tsx
├── src/shared/components/layout/SiteFooter.tsx
├── src/shared/components/layout/AuthLayout.tsx
├── src/shared/components/layout/DashboardLayout.tsx
├── src/shared/components/ui/Button.tsx
├── src/shared/components/ui/Badge.tsx
├── src/shared/components/ui/ProductCard.tsx
├── src/shared/components/ui/Stars.tsx
├── src/shared/components/ui/StarRatingInput.tsx
├── src/shared/components/ui/IconCircle.tsx
├── src/shared/components/ui/Placeholder.tsx
├── src/shared/hooks/useDebounce.ts
├── src/shared/hooks/usePagination.ts
├── src/shared/services/toast.service.ts
└── src/shared/utils/format.ts

⚠️ ADAPT NEEDED (85% reusable)
├── src/shared/constants/routes.ts → merge với router mới
└── src/pages/public/HomePage.tsx → adapt API calls
```
# Pet-care-new: FSM Detailed Specifications

> **Date:** 2026-08-18  
> **Scope:** 6 FSMs  
> **Pattern:** Bám sát specs docs

---

## 1. Roles (8 Roles)

### Nhóm 1: Platform

| Role | Mô tả | Ai |
|------|-------|-----|
| SUPER_ADMIN | Quản trị toàn hệ thống, setup | Dev / PO |

### Nhóm 2: Organization

| Role | Mô tả | Ai |
|------|-------|-----|
| ORG_ADMIN | Quản lý chuỗi cửa hàng | Chủ chuỗi |

### Nhóm 3: Store-Level

| Role | Mô tả | Ai |
|------|-------|-----|
| STORE_MANAGER | Quản lý store, duyệt refunds, reports | Chủ store |
| FINANCE_STAFF | Thu ngân, thanh toán, hoàn tiền | Kế toán |
| INVENTORY_STAFF | Quản lý kho, chuyển kho | Thủ kho |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn, thu tiền | Lễ tân |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng | Bác sĩ |
| GROOMER | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |

### Nhóm 4: Customer

| Role | Mô tả | Ai |
|------|-------|-----|
| CUSTOMER | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 2. FSM 1: Appointment (W2 - Updated per C-565e7b1)

### States:
```
BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED
   │              │                             │
   └──→ CANCELLED ←───────────────────────────────┘
   │              │
   └──→ NO_SHOW
BOOKED ─(RescheduleAppointment)─→ BOOKED (revert to check availability)
CONFIRMED ─(RescheduleAppointment)─→ BOOKED (revert to check availability)
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | BookAppointment | CUSTOMER/RECEPTIONIST | RULE-06-01, 06-02, 06-04, 06-07 | BOOKED |
| BOOKED | ConfirmAppointment | RECEPTIONIST | RULE-06-01, 06-10 | CONFIRMED |
| BOOKED | CheckInAppointment | RECEPTIONIST | RULE-06-06 | CHECKED_IN |
| CONFIRMED | CheckInAppointment | RECEPTIONIST | RULE-06-06 | CHECKED_IN |
| BOOKED | RescheduleAppointment | CUSTOMER/RECEPTIONIST | RULE-06-01, 06-10 | BOOKED |
| CONFIRMED | RescheduleAppointment | CUSTOMER/RECEPTIONIST | RULE-06-01, 06-10 | BOOKED |
| CHECKED_IN | StartAppointmentService | VETERINARIAN/GROOMER | RULE-09-01, 11-01 | IN_PROGRESS |
| IN_PROGRESS | CheckOutAppointment | RECEPTIONIST | RULE-06-06 | COMPLETED |
| CHECKED_IN | CheckOutAppointment | RECEPTIONIST | RULE-06-06 | COMPLETED |
| BOOKED | CancelAppointment | CUSTOMER/RECEPTIONIST | RULE-06-05, 06-08 | CANCELLED |
| CONFIRMED | CancelAppointment | CUSTOMER/RECEPTIONIST | RULE-06-05, 06-08 | CANCELLED |
| BOOKED | MarkNoShow | RECEPTIONIST/SYSTEM | RULE-06-09 | NO_SHOW |
| CONFIRMED | MarkNoShow | RECEPTIONIST/SYSTEM | RULE-06-09 | NO_SHOW |

### Guards:

**RULE-06-01:** Operating hours + StoreResource availability
**RULE-06-02:** Customer pet ownership permission
**RULE-06-04:** No duplicate appointments
**RULE-06-05:** Cancel only non-terminal states
**RULE-06-06:** Same store only
**RULE-06-07:** Service available + staff scheduled + StoreResource available
**RULE-06-08:** Cancel only BOOKED/CONFIRMED + policy
**RULE-06-09:** Grace period for no-show
**RULE-06-10:** Staff + StoreResource collision check

---

## 3. FSM 2: Order (W2 - In-Store - Updated per C-565e7b1)

### States:
```
PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED
       │             │                          │                          │
       └──→ CANCELLED←┘                          │                          │
       │                                        │                          │
       └──→ CANCELLED (timeout 15min) ──────────→│←──────────────────────────┘
PROCESSING ────→ CANCELLED (CancelOrderWithRefund)
READY ─────────→ CANCELLED (CancelOrderWithRefund)
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | CreateOrder | CUSTOMER/RECEPTIONIST | RULE-14-01, 14-02, 14-07 | PENDING_PAYMENT |
| PENDING_PAYMENT | Event: PaymentSucceeded | System | RULE-16-03 | PAID |
| PENDING_PAYMENT | CancelOrder | CUSTOMER/RECEPTIONIST | RULE-14-03, 14-06 | CANCELLED |
| PENDING_PAYMENT | Event: ProcessOrderTimeout | System | RULE-14-07 (TTL 15 phút) | CANCELLED |
| PAID | ConfirmOrder | RECEPTIONIST | RULE-14-04 | CONFIRMED |
| CONFIRMED | ProcessOrder | RECEPTIONIST | RULE-14-03 | PROCESSING |
| PROCESSING | PrepareProductOrder | INVENTORY_STAFF | RULE-12-04, 14-02 | READY |
| READY | CompleteStoreOrder | RECEPTIONIST | RULE-14-05 | DELIVERED |
| CONFIRMED | CancelOrder | CUSTOMER/RECEPTIONIST | RULE-14-03, 14-06 | CANCELLED |
| PROCESSING | CancelOrderWithRefund | STORE_MANAGER | RULE-14-03 | CANCELLED |
| READY | CancelOrderWithRefund | STORE_MANAGER | RULE-14-03 | CANCELLED |
| PAID | Event: RefundCompleted | System | RULE-17-01, 17-02 | REFUNDED |
| DELIVERED | Event: RefundCompleted | System | RULE-17-01, 17-02 | REFUNDED |

### Guards:

**RULE-14-01:** Customer + Store
**RULE-14-02:** Stock availability (reserve, not deduct)
**RULE-14-03:** Cannot modify CANCELLED orders
**RULE-14-06:** Cancel only PENDING_PAYMENT/CONFIRMED
**RULE-14-07:** Inventory reserved for 15 minutes, auto-release on timeout (ProcessOrderTimeout)
**Inventory Invariant:** AvailableQuantity = PhysicalQuantity - ReservedQuantity

---

## 4. FSM 3: Payment (W2 - Updated per C-565e7b1)

### States:
```
PENDING → PROCESSING → SUCCESS → PARTIALLY_REFUNDED → REFUNDED
   │         │           │                          │
   │         │           └──→ REFUNDED              │
   │         └──→ FAILED (retry allowed)            │
   └──→ CANCELLED ─────────────────────────────────┘
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | MakePayment | CUSTOMER | RULE-16-01, 16-02 | PENDING |
| [*] | RecordCashPayment | RECEPTIONIST | RULE-16-01, 16-02 | PENDING |
| PENDING | VerifyPayment | System/FinanceStaff | RULE-16-01, 16-03 | PROCESSING |
| PROCESSING | ReceivePaymentCallback/SettlePayment | System | RULE-16-03, 16-05 | SUCCESS |
| PROCESSING | ReceivePaymentCallback [fail] | System | RULE-16-04, 16-05 | FAILED |
| PENDING | CancelPayment | CUSTOMER/SYSTEM | RULE-16-04 | CANCELLED |
| SUCCESS | Event: RefundCompleted [partial] | System | RULE-17-02 | PARTIALLY_REFUNDED |
| SUCCESS | Event: RefundCompleted [full] | System | RULE-17-01, 17-02 | REFUNDED |
| PARTIALLY_REFUNDED | Event: RefundCompleted [partial] | System | RULE-17-02 | PARTIALLY_REFUNDED |
| PARTIALLY_REFUNDED | Event: RefundCompleted [full] | System | RULE-17-02 | REFUNDED |

### Guards:

**RULE-16-02:** Total payments ≤ Invoice total
**RULE-16-03:** Gateway confirmation or cash recorded
**RULE-16-05:** Callback verification
**Partial Refund Invariant:** RemainingRefundableAmount = TotalAmount - Sum(CompletedRefunds) >= 0

---

## 5. FSM 4: Invoice (W3)

### States:
```
DRAFT → ISSUED → PARTIALLY_PAID → PAID
           │                        │
           └──→ VOID                └──→ REFUNDED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | CreateInvoice | RECEPTIONIST/SYSTEM | RULE-15-01, 15-06 | DRAFT |
| DRAFT | IssueInvoice | STORE_MANAGER/RECEPTIONIST | RULE-15-01, 15-02, 15-03 | ISSUED |
| ISSUED | Event: PaymentSucceeded | System | RULE-15-05 | PARTIALLY_PAID |
| ISSUED | Event: PaymentSucceeded [full] | System | RULE-15-05 | PAID |
| PARTIALLY_PAID | Event: PaymentSucceeded [full] | System | RULE-15-05 | PAID |
| ISSUED | VoidInvoice | STORE_MANAGER | RULE-15-04 | VOID |
| PAID | Event: AllPaymentsRefunded | System | RULE-15-07 | REFUNDED |

### Guards:

**RULE-15-05:** Payment ≤ remaining
**RULE-15-07:** All payments refunded

---

## 6. FSM 5: Refund (W3 - Updated per C-565e7b1)

### States:
```
REQUESTED → APPROVED → PROCESSING → COMPLETED
     │                      │
     │                      └──→ FAILED (retry allowed)
     └──→ REJECTED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | RequestRefund | CUSTOMER/RECEPTIONIST | RULE-17-01, 17-02, 17-03, 17-07 | REQUESTED |
| REQUESTED | ApproveRefund | STORE_MANAGER | RULE-17-01, 17-02, 17-04 | APPROVED |
| REQUESTED | RejectRefund | STORE_MANAGER | RULE-17-06 | REJECTED |
| APPROVED | ProcessRefund | FINANCE_STAFF | RULE-17-04 | PROCESSING |
| PROCESSING | CompleteRefund | FINANCE_STAFF/System | RULE-17-02, 17-05 | COMPLETED |
| PROCESSING | FailRefund | System | RULE-17-05 | FAILED |
| FAILED | RetryRefund | FINANCE_STAFF/System | — | PROCESSING |
| FAILED | ResolveRefundManually | FINANCE_STAFF | RULE-17-03, 17-05 | COMPLETED |

### Guards:

**RULE-17-01:** Refund must link to original Payment
**RULE-17-02:** Refund ≤ RemainingRefundableAmount
**RULE-17-03:** Must refund via original payment channel
**RULE-17-04:** Must be APPROVED before processing
**RULE-17-05:** Processing status tracking
**RULE-17-06:** Only REQUESTED state can be rejected
**RULE-17-07:** Refund request within 30 days of payment
**Refund Window Invariant:** CurrentTimestamp ≤ Payment.CompletedTimestamp + 30 days

---

## 7. FSM 6: Grooming (W6) - Updated per C-565e7b1

### States:
```
WAITING → IN_PROGRESS → AWAITING_CUSTOMER_APPROVAL ─→ IN_PROGRESS
              │                                       │
              └──→ COMPLETED ←────────────────────────┘
WAITING/IN_PROGRESS ────→ CANCELLED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | CheckInGrooming | RECEPTIONIST | RULE-11-01 | WAITING |
| WAITING | PerformGrooming | GROOMER | RULE-11-01 | IN_PROGRESS |
| IN_PROGRESS | AddGroomingService | GROOMER | RULE-11-02, 11-05 | AWAITING_CUSTOMER_APPROVAL |
| AWAITING_CUSTOMER_APPROVAL | ConfirmAdditionalService | CUSTOMER | RULE-11-03 | IN_PROGRESS |
| AWAITING_CUSTOMER_APPROVAL | RejectAdditionalService | CUSTOMER | RULE-11-03 | IN_PROGRESS |
| IN_PROGRESS | CompleteGrooming | GROOMER | RULE-11-04 | COMPLETED |
| WAITING | CancelGrooming | CUSTOMER/RECEPTIONIST | — | CANCELLED |
| IN_PROGRESS | CancelGrooming | STORE_MANAGER/RECEPTIONIST | — | CANCELLED |

### Guards:

**RULE-11-01:** Grooming only for booked or walk-in pets
**RULE-11-02:** Additional service only during IN_PROGRESS
**RULE-11-03:** Customer must confirm/reject additional services
**RULE-11-04:** Cannot modify completed grooming
**RULE-11-05:** Additional services must be from store catalog

---

## 8. Summary (Updated per C-565e7b1)

| FSM | States | Transitions | Guards | Phase | Notes |
|-----|--------|-------------|--------|-------|-------|
| Appointment | 7 | 14 | RULE-06-01 → 06-10 | W2 | **+RescheduleAppointment** |
| Order | 8 | 13 | RULE-14-01 → 14-07 | W2 | **+ProcessOrderTimeout, +CancelOrderWithRefund** |
| Payment | 7 | 10 | RULE-16-01 → 16-05 | W2 | **+PARTIALLY_REFUNDED** |
| Invoice | 6 | 11 | RULE-15-01 → 15-07 | W3 | **+PARTIALLY_PAID, +DiscardInvoice, +VoidPartiallyPaidInvoice** |
| Refund | 6 | 8 | RULE-17-01 → 17-07 | W3 | **+RetryRefund, +ResolveRefundManually** |
| Grooming | 5 | 9 | RULE-11-01 → 11-05 | W6 | **+AWAITING_CUSTOMER_APPROVAL** |

**Tổng: 6 Core FSMs, ~65 transitions**

---

## 9. Key Invariants (C-565e7b1)

### StoreResource Collision Guard
$$\forall r \in RequiredStoreResources: ActiveBookings(r, timeRange) < Capacity(r)$$

### Inventory Reservation Invariant
$$AvailableQuantity = PhysicalQuantity - ReservedQuantity$$

### Partial Refund Invariant
$$RemainingRefundableAmount = TotalAmount - Sum(CompletedRefunds) >= 0$$

### Refund Window Invariant
$$CurrentTimestamp <= Payment.CompletedTimestamp + 30 days$$

---

## 10. Implementation Next Steps

1. [ ] Implement base `StateMachine<E, S>` class
2. [ ] Implement từng FSM concrete class
3. [ ] Viết unit tests cho mỗi transition
4. [ ] **Use Transactional Outbox Pattern** (D-04)
5. [ ] Implement Inventory Reserve với 15-min TTL
6. [ ] Add Vaccine Barcode scanning (RULE-10-06)
7. [ ] Implement Cross-Store Medical Consent (RULE-09-02, RULE-22-08)
8. [ ] Implement Walk-in to Appointment Bridge (RULE-07-05)# Pet-care-new: Task Tracker - 6-Week Sprint

> **Start Date:** 2026-09-01  
> **Team:** BE-DEV 1 · BE-DEV 2 · FE-DEV 3  
> **Pattern:** 2 Backend + 1 Frontend Specialist

---

## 📅 Overview Timeline

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        PET-CARE PROJECT TIMELINE                            │
├─────────┬─────────┬─────────┬─────────┬─────────┬─────────────────────────┤
│   W1    │   W2    │   W3    │   W4    │   W5    │   W6                   │
│ 1-5/9   │ 8-12/9  │ 15-19/9 │ 22-26/9 │ 29/9-3/10│   6-10/10            │
├─────────┼─────────┼─────────┼─────────┼─────────┼─────────────────────────┤
│Foundation│Core FSM │Commerce │Integration│ FE Int  │Tests + Demo            │
├─────────┼─────────┼─────────┼─────────┼─────────┼─────────────────────────┤
│  M1     │   M2    │  M3     │          │   M4    │ DEMO                   │
└─────────┴─────────┴─────────┴─────────┴─────────┴─────────────────────────┘
```

## 📊 Milestones

| Milestone | Deadline | Criteria | Status |
|----------|----------|----------|--------|
| **M1: Foundation** | Fri, 05/09/2025 | Auth, Users, Orgs, Pets, Products, Inventory CRUD | ⬜ |
| **M2: Core FSM** | Fri, 12/09/2025 | Appointments, Orders, Payments FSM + tests | ⬜ |
| **M3: Commerce** | Fri, 26/09/2025 | Invoices, Refunds, Clinical, Vaccinations, Walk-ins | ⬜ |
| **M4: Demo Ready** | Fri, 10/10/2025 | FE integration, Docker, All tests | ⬜ |

---

## Week 1: Foundation (01/09 - 05/09)

### 🎯 Goal: Setup + CRUD foundation cho tất cả modules

---

### Day 0 - Pre-Week (30/08) - Schema Preparation

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 0.1 | Fix V1__init_schema.sql to match docs | BE-DEV 1 | 8:00 | 12:00 | ✅ | ✅ Đã fix 2026-08-25 |
| 0.2 | Update Account entity | BE-DEV 1 | 12:00 | 14:00 | ✅ | ✅ Đã fix |
| 0.3 | Create User, Pet, Account enums | BE-DEV 1 | 14:00 | 17:00 | ✅ | ✅ Đã fix |

**Day 0 Deliverable:** ✅ Schema 100% khớp với docs/02-database-and-implementation.md

---

### Day 1 - Monday (01/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 1.1 | Maven setup, pom.xml, Docker Compose | BE-DEV 1 | 8:00 | 10:00 | ⬜ | Dependencies: Spring Boot 3.x, JPA, Security, Flyway, Lombok, MapStruct |
| 1.2 | application.yml, DB config | BE-DEV 1 | 10:00 | 12:00 | ⬜ | PostgreSQL config, JPA settings |
| 1.3 | SecurityConfig, CORS | BE-DEV 1 | 13:00 | 15:00 | ⬜ | JWT filter setup |
| 1.4 | Setup React project | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Vite, Tailwind, React Query, Router |
| 1.5 | Install component library | FE-DEV 3 | 13:00 | 15:00 | ⬜ | Headless UI, Heroicons |

**Day 1 Deliverable:** Project builds, Auth skeleton ready

---

### Day 2 - Tuesday (02/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 1.6 | Account entity + migration | BE-DEV 1 | 8:00 | 10:00 | ⬜ | V1__init_schema.sql |
| 1.7 | OTP entity + generation | BE-DEV 1 | 10:00 | 12:00 | ⬜ | 6-digit, 5 min expiry |
| 1.8 | Auth: Register, OTP endpoints | BE-DEV 1 | 13:00 | 17:00 | ⬜ | POST /auth/register, /auth/verify-otp |
| 1.9 | Login page UI mockup | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Form validation |
| 1.10 | API integration ready | FE-DEV 3 | 13:00 | 17:00 | ⬜ | axios setup, interceptors |

**Day 2 Deliverable:** Auth API works, Login page rendered

---

### Day 3 - Wednesday (03/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 1.11 | JWT generation + validation | BE-DEV 1 | 8:00 | 10:00 | ⬜ | Access + Refresh token |
| 1.12 | User entity + CRUD | BE-DEV 1 | 10:00 | 12:00 | ⬜ | 8 roles config |
| 1.13 | Pets entity + CRUD | BE-DEV 2 | 8:00 | 12:00 | ⬜ | PetService, PetController |
| 1.14 | Products entity + CRUD | BE-DEV 2 | 13:00 | 17:00 | ⬜ | ProductService, ProductController |
| 1.15 | Auth pages integration | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Login, Register, OTP verify |

**Day 3 Deliverable:** Users + Pets CRUD, Auth flow complete

---

### Day 4 - Thursday (04/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 1.16 | Organization entity + CRUD | BE-DEV 1 | 8:00 | 12:00 | ⬜ | OrganizationService |
| 1.17 | Store entity + Operating Hours | BE-DEV 1 | 13:00 | 17:00 | ⬜ | StoreService, OperatingHours |
| 1.18 | Inventory entity + CRUD | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Physical + Reserved quantity |
| 1.19 | Products list UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Grid, filter, pagination |
| 1.20 | Pets list UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Pet cards, add pet form |

**Day 4 Deliverable:** Stores + Inventory CRUD, Products + Pets UI

---

### Day 5 - Friday (05/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 1.21 | StoreServices config | BE-DEV 1 | 8:00 | 10:00 | ⬜ | StoreService mapping |
| 1.22 | Unit tests: Auth flow | BE-DEV 1 | 10:00 | 12:00 | ⬜ | JUnit + Mockito |
| 1.23 | Unit tests: CRUD operations | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Pet, Product, Inventory |
| 1.24 | Store selector UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Dropdown, store switcher |
| 1.25 | Dashboard skeleton | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Layout, sidebar, header |

**Day 5 Deliverable:** ✅ **M1: Foundation Complete**

---

## Week 2: Core Domain FSMs (08/09 - 12/09)

### 🎯 Goal: 3 FSMs chính - Appointments, Orders, Payments

---

### Day 1 - Monday (08/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 2.1 | Appointment entity + migration | BE-DEV 1 | 8:00 | 10:00 | ⬜ | Status enum: BOOKED→COMPLETED |
| 2.2 | AppointmentService: book() | BE-DEV 1 | 10:00 | 12:00 | ⬜ | RULE-06-01, 06-02, 06-04, 06-07 |
| 2.3 | Cart entity + CRUD | BE-DEV 2 | 8:00 | 12:00 | ⬜ | CartService |
| 2.4 | Appointments booking UI | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Calendar, time slots |

**Day 6 Deliverable:** Appointment booking works

---

### Day 2 - Tuesday (09/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 2.5 | AppointmentService: confirm(), checkIn() | BE-DEV 1 | 8:00 | 12:00 | ⬜ | RULE-06-01, 06-06 |
| 2.6 | AppointmentService: start(), complete() | BE-DEV 1 | 13:00 | 15:00 | ⬜ | RULE-09-01, 11-01 |
| 2.7 | AppointmentService: cancel(), reschedule() | BE-DEV 1 | 15:00 | 17:00 | ⬜ | RULE-06-05, 06-08, 06-10 |
| 2.8 | Order entity + createOrder() | BE-DEV 2 | 8:00 | 12:00 | ⬜ | RULE-14-01, 14-02 |
| 2.9 | Inventory Reservation (15 min TTL) | BE-DEV 2 | 13:00 | 17:00 | ⬜ | InventoryReservation entity |

**Day 7 Deliverable:** Appointments FSM core + Order creation

---

### Day 3 - Wednesday (10/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 2.10 | Payment entity + migration | BE-DEV 2 | 8:00 | 10:00 | ⬜ | Status: PENDING→SUCCESS |
| 2.11 | PaymentService: makePayment() | BE-DEV 2 | 10:00 | 12:00 | ⬜ | RULE-16-01, 16-02 |
| 2.12 | PaymentService: callback(), verify() | BE-DEV 2 | 13:00 | 15:00 | ⬜ | Mock gateway |
| 2.13 | Appointments list UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Filter by status, date |
| 2.14 | Order checkout UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Cart, voucher, payment method |

**Day 8 Deliverable:** Payments + Checkout UI

---

### Day 4 - Thursday (11/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 2.15 | OrderService: confirm(), process() | BE-DEV 2 | 8:00 | 12:00 | ⬜ | RULE-14-04 |
| 2.16 | OrderService: prepare(), deliver() | BE-DEV 2 | 13:00 | 15:00 | ⬜ | RULE-14-05 |
| 2.17 | OrderService: cancel() + refund | BE-DEV 2 | 15:00 | 17:00 | ⬜ | RULE-14-03, 14-06 |
| 2.18 | Appointments calendar view | FE-DEV 3 | 8:00 | 12:00 | ⬜ | FullCalendar integration |
| 2.19 | Order history UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Status timeline |

**Day 9 Deliverable:** Orders FSM + Calendar view

---

### Day 5 - Friday (12/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 2.20 | AppointmentController + tests | BE-DEV 1 | 8:00 | 12:00 | ⬜ | All transitions tested |
| 2.21 | OrderController + tests | BE-DEV 2 | 8:00 | 12:00 | ⬜ | FSM tests |
| 2.22 | PaymentController + tests | BE-DEV 2 | 13:00 | 15:00 | ⬜ | Payment FSM tests |
| 2.23 | Cash payment + payment UI | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Receipt display |

**Day 10 Deliverable:** ✅ **M2: Core FSM Complete**

---

## Week 3: Commerce + Medical (15/09 - 19/09)

### 🎯 Goal: Invoices, Refunds, Clinical, Vaccinations

---

### Day 1 - Monday (15/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 3.1 | Invoice entity + items | BE-DEV 1 | 8:00 | 10:00 | ⬜ | Status: DRAFT→PAID |
| 3.2 | InvoiceService: issue(), void() | BE-DEV 1 | 10:00 | 12:00 | ⬜ | RULE-15-01 to 15-04 |
| 3.3 | MedicalRecord entity | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Diagnosis, Prescription entities |
| 3.4 | ClinicalService: examine() | BE-DEV 2 | 13:00 | 17:00 | ⬜ | RULE-09-01 |

**Day 11 Deliverable:** Invoices + Medical records setup

---

### Day 2 - Tuesday (16/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 3.5 | InvoiceService: partial paid | BE-DEV 1 | 8:00 | 10:00 | ⬜ | PARTIALLY_PAID status |
| 3.6 | Refund entity | BE-DEV 1 | 10:00 | 12:00 | ⬜ | 30-day window |
| 3.7 | RefundService: approve, reject | BE-DEV 1 | 13:00 | 15:00 | ⬜ | RULE-17-01 to 17-06 |
| 3.8 | Prescription CRUD | BE-DEV 2 | 8:00 | 12:00 | ⬜ | PrescriptionItem |
| 3.9 | Invoice list UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Filter by status |
| 3.10 | Medical record form UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Symptoms, diagnosis, Rx |

**Day 12 Deliverable:** Refunds + Clinical forms

---

### Day 3 - Wednesday (17/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 3.11 | RefundService: process, complete | BE-DEV 1 | 8:00 | 10:00 | ⬜ | Payment gateway |
| 3.12 | RefundService: retry + manual | BE-DEV 1 | 10:00 | 12:00 | ⬜ | Edge cases |
| 3.13 | Vaccine entity + batch | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Barcode field |
| 3.14 | VaccinationService: administer | BE-DEV 2 | 13:00 | 17:00 | ⬜ | RULE-10-05, 10-06 |
| 3.15 | Prescription display UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Printable format |
| 3.16 | Refund request UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Reason, amount |

**Day 13 Deliverable:** Refunds + Vaccinations API

---

### Day 4 - Thursday (18/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 3.17 | ValidateBarcode API | BE-DEV 2 | 8:00 | 10:00 | ⬜ | Barcode validation |
| 3.18 | Cross-Store Consent entities | BE-DEV 2 | 10:00 | 12:00 | ⬜ | ConsentRequest, OTP |
| 3.19 | Cross-Store: request + OTP | BE-DEV 2 | 13:00 | 15:00 | ⬜ | RULE-09-02, 22-08 |
| 3.20 | Cross-Store: emergency override | BE-DEV 2 | 15:00 | 17:00 | ⬜ | No OTP needed |
| 3.21 | Vaccination record UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | History, due dates |
| 3.22 | Barcode scanner UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Camera integration |

**Day 14 Deliverable:** Cross-Store consent + Vaccination scanner

---

### Day 5 - Friday (19/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 3.23 | Medical history UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Timeline, diagnoses |
| 3.24 | Voucher entity + validation | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Discount types |
| 3.25 | Walk-in Queue entities | BE-DEV 2 | 13:00 | 17:00 | ⬜ | Queue, QueueEntry |
| 3.26 | Integration tests: Commerce | ALL | 8:00 | 17:00 | ⬜ | Cross-module |

**Day 15 Deliverable:** ✅ Commerce core complete

---

## Week 4: Integration + Event Bridges (22/09 - 26/09)

### 🎯 Goal: Event Bridges (Outbox Pattern), Walk-ins

---

### Day 1 - Monday (22/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 4.1 | Outbox table + scheduler | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Polling job |
| 4.2 | Order → Invoice bridge | BE-DEV 1 | 13:00 | 17:00 | ⬜ | OutboxEvent |
| 4.3 | Payment → Order bridge | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Order.PAID event |
| 4.4 | Inventory reserve timeout | BE-DEV 2 | 13:00 | 17:00 | ⬜ | 15 min scheduler |

**Day 16 Deliverable:** Outbox pattern working

---

### Day 2 - Tuesday (23/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 4.5 | Appointment → Invoice | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Service items |
| 4.6 | Clinical → Invoice | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Medical items |
| 4.7 | Vaccination → Inventory | BE-DEV 2 | 13:00 | 15:00 | ⬜ | Batch decrement |
| 4.8 | Refund → Payment bridge | BE-DEV 1 | 13:00 | 17:00 | ⬜ | Payment.REFUNDED |

**Day 17 Deliverable:** All event bridges

---

### Day 3 - Wednesday (24/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 4.9 | Walk-in Queue logic | BE-DEV 2 | 8:00 | 12:00 | ⬜ | FIFO ordering |
| 4.10 | Walk-in to Appointment bridge | BE-DEV 2 | 13:00 | 17:00 | ⬜ | RULE-07-05 |
| 4.11 | Walk-in check-in UI | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Queue display |

**Day 18 Deliverable:** Walk-ins working

---

### Day 4 - Thursday (25/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 4.12 | Integration tests: Outbox | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Event processing |
| 4.13 | Integration tests: Bridges | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Full flow |
| 4.14 | Bug fixes | ALL | 13:00 | 17:00 | ⬜ | From tests |

**Day 19 Deliverable:** Integration tests pass

---

### Day 5 - Friday (26/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 4.15 | GroomingSession entity | BE-DEV 1 | 8:00 | 10:00 | ⬜ | WAITING→COMPLETED |
| 4.16 | GroomingService entity | BE-DEV 1 | 10:00 | 12:00 | ⬜ | Additional services |
| 4.17 | Grooming FSM + Customer approval | BE-DEV 1 | 13:00 | 17:00 | ⬜ | RULE-11-01 to 11-04 |
| 4.18 | Voucher input UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Auto-validate |

**Day 20 Deliverable:** ✅ **M3: Commerce Complete**

---

## Week 5: FE Integration (29/09 - 03/10)

### 🎯 Goal: All BE endpoints connected to FE

---

### Day 1 - Monday (29/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 5.1 | BE bug fixes | BE-DEV 1 | 8:00 | 12:00 | ⬜ | From testing |
| 5.2 | BE bug fixes | BE-DEV 2 | 8:00 | 12:00 | ⬜ | From testing |
| 5.3 | Auth + Dashboard pages | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Profile, settings |

---

### Day 2 - Tuesday (30/09)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 5.4 | Appointments full UI | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Booking, list, calendar |
| 5.5 | API docs update | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Swagger/OpenAPI |

---

### Day 3 - Wednesday (01/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 5.6 | Orders + Cart full UI | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Checkout flow |
| 5.7 | API docs update | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Complete docs |

---

### Day 4 - Thursday (02/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 5.8 | Clinical + Prescription UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Full medical flow |
| 5.9 | Products + Inventory UI | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Admin views |
| 5.10 | Notifications BE | BE-DEV 1 | 8:00 | 17:00 | ⬜ | Entity, service |

---

### Day 5 - Friday (03/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 5.11 | Vaccination scanner UI | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Camera + manual |
| 5.12 | Grooming approval UI | FE-DEV 3 | 13:00 | 15:00 | ⬜ | Customer confirm |
| 5.13 | All pages polish | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Responsive, loading states |

**Day 25 Deliverable:** FE connected to all endpoints

---

## Week 6: Testing + Docker + Demo (06/10 - 10/10)

### 🎯 Goal: Demo-ready application

---

### Day 1 - Monday (06/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 6.1 | FSM unit tests | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Each transition |
| 6.2 | FSM unit tests | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Each transition |
| 6.3 | E2E tests: Customer flow | FE-DEV 3 | 13:00 | 17:00 | ⬜ | Playwright |

---

### Day 2 - Tuesday (07/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 6.4 | E2E tests: Staff flow | FE-DEV 3 | 8:00 | 12:00 | ⬜ | Admin actions |
| 6.5 | Integration tests | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Full flows |
| 6.6 | Docker compose setup | BE-DEV 2 | 13:00 | 17:00 | ⬜ | All services |

---

### Day 3 - Wednesday (08/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 6.7 | Docker image build | BE-DEV 2 | 8:00 | 12:00 | ⬜ | Multi-stage |
| 6.8 | Health checks | BE-DEV 1 | 8:00 | 12:00 | ⬜ | /health endpoint |
| 6.9 | Workforce BE | BE-DEV 1 | 13:00 | 17:00 | ⬜ | Schedule, absences |
| 6.10 | Reports BE | BE-DEV 2 | 13:00 | 17:00 | ⬜ | Revenue, customer |

---

### Day 4 - Thursday (09/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 6.11 | Audit logs BE | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Entity, service |
| 6.12 | Grooming integration | BE-DEV 1 | 8:00 | 12:00 | ⬜ | Full FSM test |
| 6.13 | FE polish + bug fixes | FE-DEV 3 | 8:00 | 17:00 | ⬜ | Final touches |

**Day 29 Deliverable:** All features complete

---

### Day 5 - Friday (10/10)

| # | Task | Owner | Start | Deadline | Status | Notes |
|---|------|-------|-------|----------|--------|-------|
| 6.14 | Final testing | ALL | 8:00 | 12:00 | ⬜ | Smoke tests |
| 6.15 | Demo prep | ALL | 13:00 | 17:00 | ⬜ | Slide, script |

**Day 30 Deliverable:** ✅ **M4: Demo Ready** 🚀

---

## 📊 Progress Tracking

### Weekly Summary

| Week | BE-DEV 1 | BE-DEV 2 | FE-DEV 3 | Overall |
|------|-----------|-----------|----------|---------|
| W1 | ⬜ /8 | ⬜ /6 | ⬜ /6 | ⬜ /20 |
| W2 | ⬜ /7 | ⬜ /8 | ⬜ /5 | ⬜ /20 |
| W3 | ⬜ /6 | ⬜ /8 | ⬜ /6 | ⬜ /20 |
| W4 | ⬜ /5 | ⬜ /6 | ⬜ /3 | ⬜ /14 |
| W5 | ⬜ /3 | ⬜ /2 | ⬜ /9 | ⬜ /14 |
| W6 | ⬜ /7 | ⬜ /3 | ⬜ /4 | ⬜ /14 |

### Total: 102 tasks

---

## 🔄 Daily Standup Format

```markdown
## Standup - [DATE]

### BE-DEV 1
- Hôm qua: [Task 1.x], [Task 1.y]
- Hôm nay: [Task 2.x]
- Blocker: [Có/Không - chi tiết]

### BE-DEV 2  
- Hôm qua: [Task x.x], [Task x.y]
- Hôm nay: [Task x.z]
- Blocker: [Có/Không - chi tiết]

### FE-DEV 3
- Hôm qua: [Task x.x]
- Hôm nay: [Task x.y]
- Đợi API: [Endpoint nào]
- Blocker: [Có/Không - chi tiết]
```

---

## ⚠️ Risk Register

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| P1 delay (Auth/Setup) | Medium | High | Buffer trong W1 |
| FSM bugs | High | Medium | Unit tests mạnh |
| FE integration chậm | Medium | Medium | OpenAPI spec trước |
| Clinical complexity | High | Medium | Core flow trước |
| Demo issues | Low | High | Full rehearsal |

---

## ✅ Definition of Done

Mỗi task cần:
- [ ] Code complete
- [ ] Unit tests pass (nếu có FSM)
- [ ] Code review approved
- [ ] Merged to develop branch

---

## 📋 Schema Changes Log

### 2026-08-25: V1__init_schema.sql - Fixed to match docs/02-database-and-implementation.md

| # | Change | Reason |
|---|--------|--------|
| 1 | Thêm `PENDING_VERIFICATION` vào `account_status` enum | Theo tài liệu |
| 2 | Thêm `PROCESSING` vào `payment_status` enum | Theo tài liệu |
| 3 | Thêm `PAID` vào `order_status` enum | Theo tài liệu |
| 4 | Thêm `DEACTIVATED` vào `store_status` enum | Theo tài liệu |
| 5 | Thêm `consent_status` enum | Theo tài liệu |
| 6 | Thêm `cross_store_consent_status` enum | Theo tài liệu |
| 7 | Đổi `OTPs.code` từ VARCHAR(10) → VARCHAR(6) | Theo tài liệu |
| 8 | Đổi `inventory.min_threshold` DEFAULT từ 10 → 5 | Theo tài liệu |
| 9 | Đổi `accounts.status` DEFAULT từ 'ACTIVE' → 'PENDING_VERIFICATION' | Theo tài liệu |
| 10 | Thêm `UNIQUE(vaccine_id, batch_number)` cho vaccine_batches | Theo tài liệu |
| 11 | Bỏ duplicate manufacturer trong vaccine_batches | Theo tài liệu |
| 12 | Thêm cross_store_consents.status dùng enum type | Theo tài liệu |
| 13 | Thêm seed data đầy đủ (accounts, orgs, stores, services, vaccines, products, inventory) | Theo tài liệu |
