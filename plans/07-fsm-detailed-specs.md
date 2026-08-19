# Pet-care-new: FSM Detailed Specifications

> **Date:** 2026-08-18  
> **Scope:** 6 FSMs của MVP  
> **Pattern:** Bám sát specs docs

---

## 1. Roles (5 Roles)

### Nhóm 1: Admin

| Role | Mô tả | Ai |
|------|-------|-----|
| SUPER_ADMIN | Quản trị toàn hệ thống, setup | Dev / PO |

### Nhóm 2: Store-Level

| Role | Mô tả | Ai |
|------|-------|-----|
| STORE_MANAGER | Quản lý store, duyệt refunds, inventory, finance, reports | Chủ store |
| RECEPTIONIST | Tiếp khách, check-in, tạo đơn, thu tiền | Lễ tân |
| VETERINARIAN | Khám bệnh, kê đơn, tiêm phòng, tạo bệnh án | Bác sĩ |
| GROOMER | Làm đẹp thú cưng, thêm dịch vụ phát sinh | Stylist |

### Nhóm 3: Customer

| Role | Mô tả | Ai |
|------|-------|-----|
| CUSTOMER | Đặt lịch, mua hàng, thanh toán, quản lý pets | Khách hàng |

---

## 2. FSM 1: CaregiverInvitation (W1)

### States:
```
INVITED → ACTIVE → REVOKED
   ↓
 REJECTED
   ↓
 EXPIRED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | InviteCaregiver | CUSTOMER | RULE-04-01, 04-04 | INVITED |
| INVITED | AcceptInvitation | Caregiver | RULE-04-02 | ACTIVE |
| INVITED | RejectInvitation | Caregiver | — | REJECTED |
| INVITED | ProcessExpiry | System | RULE-04-06 | EXPIRED |
| ACTIVE | RevokeCaregiver | CUSTOMER | RULE-04-03, 04-04 | REVOKED |

---

## 3. FSM 2: Appointment (W2)

### States:
```
BOOKED → CONFIRMED → CHECKED_IN → IN_PROGRESS → COMPLETED
   │                                  │
   └──→ CANCELLED ←──────────────────┘
   │
   └──→ NO_SHOW
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | BookAppointment | CUSTOMER/RECEPTIONIST | RULE-06-01, 06-02, 06-04, 06-07 | BOOKED |
| BOOKED | ConfirmAppointment | RECEPTIONIST | RULE-06-01 | CONFIRMED |
| BOOKED | CheckInAppointment | RECEPTIONIST | RULE-06-06 | CHECKED_IN |
| CONFIRMED | CheckInAppointment | RECEPTIONIST | RULE-06-06 | CHECKED_IN |
| CHECKED_IN | StartAppointmentService | RECEPTIONIST | RULE-06-07 | IN_PROGRESS |
| IN_PROGRESS | CompleteAppointment | RECEPTIONIST | RULE-06-06 | COMPLETED |
| BOOKED | CancelAppointment | CUSTOMER/RECEPTIONIST | RULE-06-05, 06-08 | CANCELLED |
| CONFIRMED | CancelAppointment | CUSTOMER/RECEPTIONIST | RULE-06-05, 06-08 | CANCELLED |
| BOOKED | MarkNoShow | RECEPTIONIST | RULE-06-09 | NO_SHOW |
| CONFIRMED | MarkNoShow | RECEPTIONIST | RULE-06-09 | NO_SHOW |

### Guards:

**RULE-06-01:** Operating hours
**RULE-06-02:** Customer/Caregiver permission
**RULE-06-04:** No duplicate appointments
**RULE-06-06:** Same store only
**RULE-06-07:** Service available + staff scheduled
**RULE-06-08:** Cancel only BOOKED/CONFIRMED + policy
**RULE-06-09:** Grace period for no-show

---

## 4. FSM 3: Order (W2)

### States:
```
PENDING_PAYMENT → PAID → CONFIRMED → PROCESSING → READY → DELIVERED
       │                          │                          │
       └──→ CANCELLED ←──────────┘                          │
       │                                                     │
       └────────────────────→ REFUNDED ←────────────────────┘
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | CreateOrder | CUSTOMER/RECEPTIONIST | RULE-14-01, 14-02 | PENDING_PAYMENT |
| PENDING_PAYMENT | Event: PaymentSucceeded | System | RULE-16-03 | PAID |
| PENDING_PAYMENT | CancelOrder | CUSTOMER/RECEPTIONIST | RULE-14-03, 14-06 | CANCELLED |
| PAID | ConfirmOrder | RECEPTIONIST | RULE-14-04 | CONFIRMED |
| CONFIRMED | ProcessOrder | RECEPTIONIST | RULE-14-03 | PROCESSING |
| PROCESSING | PrepareProductOrder | RECEPTIONIST | RULE-12-04, 14-02 | READY |
| READY | CompleteStoreOrder | RECEPTIONIST | RULE-14-05 | DELIVERED |
| CONFIRMED | CancelOrder | CUSTOMER/RECEPTIONIST | RULE-14-03, 14-06 | CANCELLED |
| PAID | Event: RefundCompleted | System | RULE-17-01, 17-02 | REFUNDED |
| DELIVERED | Event: RefundCompleted | System | RULE-17-01, 17-02 | REFUNDED |

### Guards:

**RULE-14-01:** Customer + Store
**RULE-14-02:** Stock availability
**RULE-14-06:** Cancel only PENDING_PAYMENT/CONFIRMED

---

## 5. FSM 4: Payment (W2)

### States:
```
PENDING → PROCESSING → SUCCESS
   │         │           │
   │         │           └──→ REFUNDED
   │         └──→ FAILED
   └──→ CANCELLED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | MakePayment | CUSTOMER | RULE-16-01, 16-02 | PENDING |
| [*] | RecordCashPayment | RECEPTIONIST | RULE-16-01, 16-02 | PENDING |
| PENDING | VerifyPayment | System | RULE-16-01, 16-03 | PROCESSING |
| PROCESSING | ReceivePaymentCallback | System | RULE-16-03, 16-05 | SUCCESS |
| PROCESSING | ReceivePaymentCallback [fail] | System | RULE-16-04, 16-05 | FAILED |
| PENDING | CancelPayment | CUSTOMER | RULE-16-04 | CANCELLED |
| SUCCESS | Event: RefundCompleted | System | RULE-17-01, 17-02 | REFUNDED |

### Guards:

**RULE-16-02:** Total payments ≤ Invoice total
**RULE-16-05:** Callback verification

---

## 6. FSM 5: Invoice (W3)

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

## 7. FSM 6: Refund (W3)

### States:
```
REQUESTED → APPROVED → PROCESSING → COMPLETED
     │                      │
     │                      └──→ FAILED
     └──→ REJECTED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | RequestRefund | CUSTOMER/RECEPTIONIST | RULE-17-01, 17-02, 17-03 | REQUESTED |
| REQUESTED | ApproveRefund | STORE_MANAGER | RULE-17-01, 17-02, 17-04 | APPROVED |
| REQUESTED | RejectRefund | STORE_MANAGER | RULE-17-06 | REJECTED |
| APPROVED | ProcessRefund | STORE_MANAGER/System | RULE-17-04 | PROCESSING |
| PROCESSING | CompleteRefund | STORE_MANAGER/System | RULE-17-02, 17-05 | COMPLETED |
| PROCESSING | FailRefund | System | RULE-17-05 | FAILED |

### Guards:

**RULE-17-02:** Refund ≤ payment

---

## 8. FSM 7: Grooming (W6) - P2

### States:
```
WAITING → IN_PROGRESS → COMPLETED
              │
              └──→ CANCELLED
```

### Transitions:

| From | Command | Actor | Guards | To |
|------|---------|-------|--------|----|
| [*] | CreateGroomingSession | RECEPTIONIST | RULE-11-01 | WAITING |
| WAITING | StartGrooming | GROOMER | — | IN_PROGRESS |
| IN_PROGRESS | AddAdditionalService | GROOMER | RULE-11-02 | IN_PROGRESS |
| IN_PROGRESS | ConfirmAdditionalService | CUSTOMER | RULE-11-03 | IN_PROGRESS |
| IN_PROGRESS | CompleteGrooming | GROOMER | — | COMPLETED |
| WAITING | CancelGrooming | CUSTOMER/RECEPTIONIST | — | CANCELLED |

---

## 9. Summary

| FSM | States | Transitions | Guards | Phase |
|-----|--------|-------------|--------|-------|
| CaregiverInvitation | 4 | 5 | RULE-04-01 → 04-06 | W1 |
| Appointment | 7 | 10 | RULE-06-01 → 06-09 | W2 |
| Order | 8 | 10 | RULE-14-01 → 14-06 | W2 |
| Payment | 6 | 7 | RULE-16-01 → 16-05 | W2 |
| Invoice | 6 | 7 | RULE-15-01 → 15-07 | W3 |
| Refund | 6 | 6 | RULE-17-01 → 17-06 | W3 |
| Grooming | 4 | 6 | RULE-11-01 → 11-05 | W6 |

**Tổng: 7 FSMs, 51 transitions**

---

## 10. Next Steps

1. [ ] Implement base StateMachine<E, S> class
2. [ ] Implement từng FSM concrete class
3. [ ] Viết unit tests cho mỗi transition
4. [ ] Implement Spring Events cho cross-aggregate
5. [ ] Test integration giữa các FSMs