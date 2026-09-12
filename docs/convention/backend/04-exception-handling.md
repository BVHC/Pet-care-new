[← Backend Convention Index](../backend-convention.md)

# 4. Exception & Error Handling

## 4.1. Base exception dùng chung (bắt buộc dùng trước)

Định nghĩa 1 lần tại `platform/exception`:

| Exception | Constructor | Dùng khi |
|---|---|---|
| `BusinessRuleViolationException` | `(String ruleId, String message)` | Vi phạm RULE-ID ở `02-business-rules.md` |
| `InvalidStateTransitionException` | `(String fsmName, String from, String to)` | Transition FSM không hợp lệ |
| `ResourceNotFoundException` | `(String resourceType, Object resourceId)` | Không tìm thấy entity |
| `AccessDeniedScopeException` | `(String requiredScope, String actualScope)` | Vi phạm RULE-02-01 (Scope mismatch) |
| `ConcurrencyConflictException` | `(String resourceType, Object resourceId)` | Optimistic lock conflict |

## 4.2. Khi nào được tạo exception riêng theo module

Chỉ tạo subclass riêng (kế thừa từ 1 trong 5 exception trên) khi thoả **≥ 1** tiêu chí:

1. Cần mang ≥ 2 field đặc thù mà base không diễn tả được.
   `RefundRetryLimitExceededException(refundId, retryCount, maxRetry)`
2. Cần được catch/xử lý riêng khác với default ở `GlobalExceptionHandler` (HTTP status khác, header khác...).
   `PaymentIdempotencyConflictException` → HTTP 409 kèm `idempotency-key` gốc.
3. Liên quan FSM có TTL/concurrency đặc biệt cần logic retry/rollback riêng (BookingHold, CaregiverInvite...).

**Không thoả tiêu chí nào → bắt buộc dùng exception chung**, không tạo class mới. Mọi exception riêng phải ghi rõ lý do (số tiêu chí) trong comment/PR description.

## 4.3. Error Response Envelope

`GlobalExceptionHandler` (`@RestControllerAdvice`) trả về format thống nhất:

```json
{
  "success": false,
  "errorCode": "BUSINESS_RULE_VIOLATION",
  "message": "Pet đã có lịch hẹn trùng khung giờ (RULE-06-11)",
  "statusCode": 400,
  "timestamp": "2026-09-08T10:00:00Z",
  "traceId": "a1b2c3d4"
}
```

Tất cả 6 field bắt buộc; `traceId` lấy từ MDC (xem [8. Logging & Audit](08-logging-and-audit.md)), `message` nhúng RULE-ID nếu có. Mapping `errorCode` ↔ HTTP status ↔ exception:

| Exception | errorCode | HTTP Status |
|---|---|---|
| `BusinessRuleViolationException` | `BUSINESS_RULE_VIOLATION` | 400 |
| `InvalidStateTransitionException` | `INVALID_STATE_TRANSITION` | 409 |
| `ResourceNotFoundException` | `RESOURCE_NOT_FOUND` | 404 |
| `AccessDeniedScopeException` | `ACCESS_DENIED_SCOPE_MISMATCH` | 403 |
| `ConcurrencyConflictException` | `CONCURRENCY_CONFLICT` | 409 |
| `MethodArgumentNotValidException` (Bean Validation) | `VALIDATION_FAILED` | 400 |

---

[← 3. Naming Convention](03-naming-convention.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 5. FSM Implementation Pattern →](05-fsm-pattern.md)
