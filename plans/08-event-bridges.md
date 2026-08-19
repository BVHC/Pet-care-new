# Pet-care-new: Event Bridges & Cross-Aggregate Flows

> **Date:** 2026-08-18  
> **Scope:** Event bridges cho 6 FSMs  
> **Pattern:** Spring Application Events

---

## 1. Event System Pattern

### 1.1 Base Event

```java
public abstract sealed class BaseDomainEvent 
    permits AppointmentBooked, AppointmentCompleted, 
            OrderCreated, OrderPaid, OrderCancelled, OrderDelivered,
            PaymentSucceeded, PaymentFailed, RefundCompleted,
            InvoiceIssued, InvoicePaid, RefundRequested,
            ClinicalExaminationCreated, VaccinationAdministered { ... }
```

### 1.2 Publish & Listen

```java
@Service
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;
    
    @Transactional
    public Order createOrder(...) {
        Order order = ...;
        orderRepo.save(order);
        eventPublisher.publishEvent(new OrderCreatedEvent(order));
        return order;
    }
}

@Component
public class PaymentEventListener {
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        // Cập nhật Order → PAID
        // Cập nhật Invoice → PARTIALLY_PAID hoặc PAID
        // Gửi notification
    }
}
```

---

## 2. Event Bridge Flows

### Flow 1: Order → Payment → Invoice

```
Customer checkout
       │
       ▼
OrderCreatedEvent
       │
       ├──▶ NotificationListener → "Order created"
       │
       ▼
Customer makes Payment
       │
       ▼
PaymentSucceededEvent
       │
       ├──▶ OrderListener → Order.PAID
       ├──▶ InvoiceListener → Invoice updated
       └──▶ NotificationListener → "Payment successful"
```

### Flow 2: Appointment → Invoice

```
Appointment.COMPLETED
       │
       ▼
AppointmentCompletedEvent
       │
       └──▶ InvoiceListener → Auto-create invoice for service
              └──▶ NotificationListener → "Invoice created"
```

### Flow 3: Refund → Payment/Order/Invoice

```
RefundRequested
       │
       ▼
StoreManager approves → RefundApproved
       │
       ▼
ProcessRefund → COMPLETED
       │
       ▼
RefundCompletedEvent
       │
       ├──▶ PaymentListener → Payment.REFUNDED
       ├──▶ OrderListener → Order.REFUNDED
       ├──▶ InvoiceListener → Invoice.REFUNDED
       └──▶ NotificationListener → "Refund completed"
```

### Flow 4: Clinical → Invoice

```
Examination completed
       │
       ▼
ClinicalExaminationCreatedEvent
       │
       └──▶ InvoiceListener → Auto-create invoice for examination + prescription
```

---

## 3. Event Listeners Mapping

| Event | Listeners | Effects |
|-------|-----------|---------|
| **OrderCreated** | NotificationListener | Notify customer |
| **OrderPaid** | InventoryListener | Decrement stock |
| | InvoiceListener | Update invoice |
| | NotificationListener | Notify customer |
| **PaymentSucceeded** | OrderListener | Order → PAID |
| | InvoiceListener | Update invoice status |
| **PaymentFailed** | NotificationListener | Notify customer |
| **RefundCompleted** | PaymentListener | Payment → REFUNDED |
| | OrderListener | Order → REFUNDED |
| | InvoiceListener | Invoice → REFUNDED |
| **AppointmentCompleted** | InvoiceListener | Create service invoice |
| **ClinicalExaminationCreated** | InvoiceListener | Create clinical invoice |
| **VaccinationAdministered** | InventoryListener | Decrement vaccine stock |
| | NotificationListener | Notify customer |

---

## 4. Implementation

### 4.1 Create Events

```java
public record OrderCreatedEvent(Order order) implements BaseDomainEvent {}
public record OrderPaidEvent(Order order) implements BaseDomainEvent {}
public record PaymentSucceededEvent(Payment payment) implements BaseDomainEvent {}
public record RefundCompletedEvent(Refund refund) implements BaseDomainEvent {}
public record AppointmentCompletedEvent(Appointment appointment) implements BaseDomainEvent {}
public record ClinicalExaminationCreatedEvent(MedicalRecord record) implements BaseDomainEvent {}
```

### 4.2 Create Listeners

```java
@Component
public class OrderEventListener { ... }

@Component
public class PaymentEventListener { ... }

@Component
public class InvoiceEventListener { ... }

@Component
public class InventoryEventListener { ... }

@Component
public class NotificationEventListener { ... }
```

### 4.3 Transaction Boundaries

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onOrderPaid(OrderPaidEvent event) {
    // Chỉ chạy SAU KHI transaction commit
    inventoryService.decrementStock(event.getOrder());
}
```

---

## 5. Skip (P2 features không cần event bridges)

| Event | Module | Lý do |
|-------|--------|-------|
| StockTransferShipped | Inventory | Không làm |
| PackageActivated | Loyalty | Không làm |
| IncidentRecorded | Incidents | Không làm |

---

## 6. Next Steps

1. [ ] Tạo tất cả Event classes
2. [ ] Implement EventListeners
3. [ ] Wire events vào Services
4. [ ] Viết tests cho cross-aggregate flows
5. [ ] Verify AFTER_COMMIT pattern