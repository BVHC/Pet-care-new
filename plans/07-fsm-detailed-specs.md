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
8. [ ] Implement Walk-in to Appointment Bridge (RULE-07-05)