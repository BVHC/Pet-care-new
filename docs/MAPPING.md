# Docs Mapping - Docs cũ ↔ Plan mới

> Giúp dev hiểu docs nào tương ứng với phase nào

---

## Tổng quan mapping (Updated per C-565e7b1)

| Docs File | Phase | Module | FSM |
|-----------|-------|--------|-----|
| **01-business-operations.md** | All | Tất cả | Tất cả |
| **02-business-rules.md** | All | Tất cả | Tất cả |
| **03-state-machines.md** | All | Account, Store, Appointments, Orders, Payments, Invoices, Refunds, Grooming, Package, Membership, StockTransfer, PurchaseOrder, Incident | ✅ (8 FSMs chính + 7 FSMs mở rộng) |
| **04-glossary.md** | All | Tất cả | Tất cả |
| **06-team-timeline.md** | All | All modules | Phased delivery |
| **MAPPING.md** | All | All | Cross-reference |

---

## Chi tiết mapping theo Phase

### Phase 1: Foundation (W1)

| Module | Business Operations | Business Rules | State Machines |
|--------|--------------------|--------------------|-------------------|
| Auth + OTP | Section 2: Authentication & OTP | RULE-01-01 → 01-05 | ❌ |
| Users | Section 3: Identity & Access | RULE-02-01 → 02-05 | ❌ |
| Organizations | Section 4: Organization & Store | RULE-03-01 → 03-06 | ❌ |
| Stores | Section 4: Organization & Store | RULE-03-01 → 03-06 (shared with Orgs) | ❌ |
| Pets | Section 5: Customer & Pet | RULE-04-01 | ❌ |
| Products | Section 6: Service & Product | RULE-05-01 → 05-05 | ❌ |
| Inventory | Section 13: Inventory | RULE-12-01 → 12-08 | ❌ |

---

### Phase 2: Core Domain (W2)

| Module | Business Operations | Business Rules | State Machines |
|--------|--------------------|--------------------|-------------------|
| **Appointments** | Section 7: Appointment | RULE-06-01 → 06-10 | Section 7: Appointment FSM (+ RescheduleAppointment) |
| **Orders** | Section 15: Order | RULE-14-01 → 14-07 | Section 15: Order FSM (+ ProcessOrderTimeout 15min, + CancelOrderWithRefund) |
| **Payments** | Section 17: Payment | RULE-16-01 → 16-05 | Section 17: Payment FSM (+ PARTIALLY_REFUNDED) |

---

### Phase 3: Commerce (W3-W4)

| Module | Business Operations | Business Rules | State Machines |
|--------|--------------------|--------------------|-------------------|
| **Invoices** | Section 16: Billing | RULE-15-01 → 15-07 | Section 16: Invoice FSM (+ PARTIALLY_PAID) |
| **Refunds** | Section 18: Refund | RULE-17-01 → 17-07 | Section 18: Refund FSM (+ RetryRefund, + ResolveRefundManually, + 30-day window) |
| Clinical | Section 10: Veterinary/Clinical | RULE-09-01 → 09-07 + RULE-22-08 | ❌ (+ CrossStoreConsent, EmergencyOverride) |
| Promotions | Section 19: Promotion | RULE-18-01 → 18-05 | ❌ |
| Vaccinations | Section 11: Vaccination | RULE-10-01 → 10-07 | ❌ (+ Barcode scanning, + available_quantity) |

---

### Phase 4: Polish (W5-W6)

| Module | Business Operations | Business Rules | State Machines |
|--------|--------------------|--------------------|-------------------|
| Notifications | Section 24: Notification | RULE-23-01 → 23-03 | ❌ |
| Walk-ins | Section 8: Walk-in & Queue | RULE-07-01 → 07-04 | ❌ |
| **Grooming** | Section 12: Grooming | RULE-11-01 → 11-05 | Section 12: Grooming FSM |
| Workforce | Section 9: Workforce | RULE-08-01 → 08-06 | ❌ |
| Reports | Section 25: Reporting | RULE-24-01 → 24-06 | ❌ |
| Audit Logs | Section 26: Audit | RULE-25-01 → 25-06 | ❌ |

---

## Business Rules quick reference

### W1: Foundation

```markdown
## RULE-01: Authentication
- RULE-01-01: Account only login when ACTIVE
- RULE-01-02: OTP only valid within TTL
- RULE-01-03: OTP must be verified before activation
- RULE-01-04: ResendOTP invalidates previous OTP
- RULE-01-05: Rate limit OTP attempts

## RULE-02: IAM
- RULE-02-01 → 02-05: User management

## RULE-03: Organization/Store
- RULE-03-01 → 03-06: Organization/Store operations

## RULE-04: Pets
- RULE-04-01: Only owner can manage pet

## RULE-05: Products
- RULE-05-01 → 05-05: Product management

## RULE-12: Inventory
- RULE-12-01 → 12-08: Stock management
```

### W2: Core Domain

```markdown
## RULE-06: Appointments (FSM)
- RULE-06-01: Check operating hours
- RULE-06-02: Check pet ownership
- RULE-06-04: No duplicate appointments
- RULE-06-05: Cancel policy
- RULE-06-06: Same store only
- RULE-06-07: Service available
- RULE-06-08: Cancellation policy
- RULE-06-09: No-show grace period

## RULE-14: Orders (FSM)
- RULE-14-01 → 14-06: Order flow

## RULE-16: Payments (FSM)
- RULE-16-01 → 16-05: Payment flow
```

### W3: Commerce

```markdown
## RULE-15: Invoices (FSM)
- RULE-15-01 → 15-07: Invoice flow

## RULE-17: Refunds (FSM)
- RULE-17-01 → 17-06: Refund flow

## RULE-09: Clinical
- RULE-09-01 → 09-07: Medical records

## RULE-18: Promotions
- RULE-18-01 → 18-05: Vouchers

## RULE-10: Vaccinations
- RULE-10-01 → 10-05: Vaccine management
```

### W5-W6: Polish

```markdown
## RULE-23: Notifications
- RULE-23-01 → 23-03: Notification triggers

## RULE-07: Walk-ins
- RULE-07-01 → 07-04: Queue management

## RULE-11: Grooming (FSM)
- RULE-11-01 → 11-05: Grooming flow

## RULE-08: Workforce
- RULE-08-01 → 08-06: Schedule management

## RULE-24: Reports
- RULE-24-01 → 24-06: Report generation

## RULE-25: Audit
- RULE-25-01 → 25-06: Audit logging
```

---

## FSMs trong 03-state-machines.md

### W1: 2 FSMs (NEW per C-565e7b1)

| FSM | States | Section |
|-----|--------|---------|
| **Account** | PENDING_VERIFICATION, ACTIVE, LOCKED | Section 1 |
| **Store** | ACTIVE, SUSPENDED, DEACTIVATED, ARCHIVED | Section 2 |

### W2: 3 Core FSMs

| FSM | States | Section |
|-----|--------|---------|
| **Appointment** | BOOKED, CONFIRMED, CHECKED_IN, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW | Section 4 |
| **Order** | PENDING_PAYMENT, PAID, CONFIRMED, PROCESSING, READY, DELIVERED, CANCELLED, REFUNDED | Section 5 |
| **Payment** | PENDING, PROCESSING, SUCCESS, FAILED, CANCELLED, PARTIALLY_REFUNDED, REFUNDED | Section 7 |

### W3: 2 Commerce FSMs

| FSM | States | Section |
|-----|--------|---------|
| **Invoice** | DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID, REFUNDED | Section 6 |
| **Refund** | REQUESTED, APPROVED, REJECTED, PROCESSING, COMPLETED, FAILED | Section 8 |


### W6: 1 Grooming FSM

| FSM | States | Section |
|-----|--------|---------|
| **Grooming** | WAITING, IN_PROGRESS, AWAITING_CUSTOMER_APPROVAL, COMPLETED, CANCELLED | Section 15 |

### Additional FSMs (Mở rộng theo docs/03)

| FSM | States | Section |
|-----|--------|---------|
| **CaregiverInvitation** | INVITED, ACTIVE, REJECTED, EXPIRED, REVOKED | Section 3 |
| **Membership** | ACTIVE, UPGRADED, EXPIRED | Section 9 |
| **Package** | PURCHASED, ACTIVATED, PARTIALLY_CONSUMED, FULLY_CONSUMED, CANCELLED, EXPIRED | Section 10 |
| **StockTransfer** | REQUESTED, APPROVED, REJECTED, IN_TRANSIT, RECEIVED, DISCREPANCY, CANCELLED | Section 11 |
| **PurchaseRequest** | DRAFT, SUBMITTED, APPROVED, REJECTED, CANCELLED | Section 12 |
| **PurchaseOrder** | ISSUED, PARTIALLY_RECEIVED, RECEIVED, CLOSED, CANCELLED | Section 13 |
| **Incident** | RECORDED, CLASSIFIED, UNDER_INVESTIGATION, ESCALATED, RESOLVED, CLOSED | Section 14 |

---

## Cách đọc docs

### 1. Khi implement module X:
1. Đọc **01-business-operations.md** → Section X để hiểu nghiệp vụ
2. Đọc **02-business-rules.md** → RULE-XX để hiểu rules
3. Đọc **03-state-machines.md** → Section X để hiểu FSM (nếu có)
4. Đọc **04-glossary.md** → tra cứu terms

### 2. Khi debug:
1. Check **04-glossary.md** → xem định nghĩa term
2. Check **02-business-rules.md** → xem rule liên quan
3. Check **03-state-machines.md** → xem state transitions

### 3. Khi tạo API:
1. Check **01-business-operations.md** → xem commands
2. Check **02-business-rules.md** → xem guards
3. Tham khảo **04-api-contracts.md** trong plans/

---

## Quick Commands

### Clone docs về máy:
```bash
cd D:/Do-an/Pet-care-new/docs
ls -la *.md
```

### Search rule trong docs:
```bash
grep -n "RULE-06-01" *.md
```

### Xem FSM structure:
```bash
grep -A 20 "## FSM" 03-state-machines.md
```
