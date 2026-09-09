[← Backend Convention Index](../backend-convention.md)

# 8. Logging & Audit

## 8.1. Application Log

- JSON structured log qua SLF4J + Logback (`logstash-logback-encoder`).
- `traceId`/`requestId` gắn vào MDC từ Filter đầu tiên của request, xuyên suốt mọi layer.

| Level | Khi dùng |
|---|---|
| `ERROR` | Lỗi hệ thống không mong muốn (DB down, NPE...) |
| `WARN` | Business rule violation / invalid transition đã được handle |
| `INFO` | Command nghiệp vụ quan trọng hoàn tất (`BookAppointment`, `IssueInvoice`, `CompleteRefund`) |
| `DEBUG` | Chi tiết nội bộ phục vụ debug |

## 8.2. Audit Log (Module 25)

Tách biệt hoàn toàn khỏi application log, dùng AOP:

```java
// platform/audit
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();
}

@Aspect
@Component
public class AuditAspect {
    @AfterReturning("@annotation(auditable)")
    public void audit(JoinPoint jp, Auditable auditable) {
        // ghi actor_id, actor_role (SYSTEM nếu do System thực thi), entity snapshot, IP, timestamp
    }
}

// Sử dụng
@Auditable(action = "ApproveRefund")
@Transactional
public void approveRefund(Long refundId) { ... }
```

Bắt buộc gắn `@Auditable` cho mọi command Maker-Checker (`ApproveRefund`, `ApproveStockTransfer`, `ApprovePurchaseRequest`, `ApproveInventoryAdjustment`) và mọi thao tác nhạy cảm (`EmergencyOverrideAccess`, `LockAccount`, `RecordCashPayment`).

---

[← 7. Transaction Management](07-transaction-management.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 9. Testing →](09-testing.md)
