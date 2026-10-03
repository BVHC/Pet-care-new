[← Backend Convention Index](INDEX.md)

# 4. Exception & Error Handling

## 4.1. Base exception dùng chung (bắt buộc dùng trước)

Định nghĩa 1 lần tại `platform/exception`:

| Exception | Constructor | Dùng khi |
|---|---|---|
| `BusinessRuleViolationException` | `(String ruleId, String message)` | Vi phạm rule `BR-<MODULE>-<n>` ở `02-business-rules.md` |
| `InvalidStateTransitionException` | `(String fsmName, String from, String to)` | Transition FSM không hợp lệ |
| `ResourceNotFoundException` | `(String resourceType, Object resourceId)` | Không tìm thấy entity |
| `AccessDeniedScopeException` | `(String requiredScope, String actualScope)` | Truy cập dữ liệu ngoài phạm vi (vd chi nhánh khác); client chỉ nhận message chung |
| `ConcurrencyConflictException` | `(String resourceType, Object resourceId)` | Xung đột do thao tác đồng thời (khóa, tranh chấp dữ liệu) |

Cả 5 lớp kế thừa `PlatformException` (abstract, mang `ErrorCode`). `GlobalExceptionHandler` map mọi `PlatformException` theo `errorCode()`, nên subclass của module tự có envelope đúng, không cần thêm handler. `BusinessRuleViolationException` nhận mã rule dạng `BR-<MODULE>-<n>` (docs/02-business-rules.md) và nhúng vào message: `"<message> (BR-LH-05)"`.

## 4.2. Khi nào được tạo exception riêng theo module

Chỉ tạo subclass riêng (kế thừa từ 1 trong 5 exception trên, đặt ở `module/<feature>/exception/`) khi thoả **≥ 1** tiêu chí:

1. Cần mang ≥ 2 field đặc thù mà base không diễn tả được, và caller hoặc client cần đọc chúng.
   Ví dụ giả định: lỗi thiếu tồn lúc thu tiền cần trả danh sách dòng thiếu (BR-BH-04 "chỉ rõ dòng thiếu").
2. Cần được xử lý khác default ở `GlobalExceptionHandler` (HTTP status khác, header khác...).
3. Liên quan FSM có TTL/concurrency đặc biệt cần logic retry/rollback riêng. *(Chưa có trường hợp nào; cơ chế khóa đồng thời còn chờ ADR, xem [07](07-transaction-management.md).)*

**Không thoả tiêu chí nào → bắt buộc dùng exception chung**, không tạo class mới. Mọi exception riêng phải ghi rõ lý do (số tiêu chí) trong Javadoc và mô tả PR. Nếu cần thêm trường vào `ErrorResponse` (tiêu chí 1, 2) thì phải sửa `platform/` và ghi ADR, vì envelope lỗi là hợp đồng chung với FE.

## 4.3. Error Response Envelope

`GlobalExceptionHandler` (`@RestControllerAdvice`) trả về format thống nhất:

```json
{
  "success": false,
  "errorCode": "BUSINESS_RULE_VIOLATION",
  "message": "Thú cưng đã có 2 lịch BOOKED (BR-LH-05)",
  "statusCode": 400,
  "timestamp": "2026-09-08T10:00:00Z",
  "traceId": "a1b2c3d4"
}
```

Tất cả 6 field bắt buộc; `traceId` lấy từ MDC (xem [8. Logging & Audit](08-logging-and-audit.md)), `message` nhúng mã rule `BR-…` nếu có. Mapping `errorCode` ↔ HTTP status ↔ exception:

| Exception | errorCode | HTTP Status |
|---|---|---|
| `BusinessRuleViolationException` | `BUSINESS_RULE_VIOLATION` | 400 |
| `InvalidStateTransitionException` | `INVALID_STATE_TRANSITION` | 409 |
| `ResourceNotFoundException` | `RESOURCE_NOT_FOUND` | 404 |
| `AccessDeniedScopeException` | `ACCESS_DENIED_SCOPE_MISMATCH` | 403 |
| `ConcurrencyConflictException` | `CONCURRENCY_CONFLICT` | 409 |
| `MethodArgumentNotValidException`, `HandlerMethodValidationException`, `ConstraintViolationException` (Bean Validation) | `VALIDATION_FAILED` — message dạng `"field: lỗi; field: lỗi"` | 400 |
| `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException`, `MissingServletRequestParameterException` | `MALFORMED_REQUEST` | 400 |
| `NoResourceFoundException`, `NoHandlerFoundException` | `RESOURCE_NOT_FOUND` | 404 |
| `HttpRequestMethodNotSupportedException` | `METHOD_NOT_ALLOWED` | 405 |
| `HttpMediaTypeNotSupportedException` | `UNSUPPORTED_MEDIA_TYPE` | 415 |
| `AuthenticationException` (Spring Security) | `UNAUTHENTICATED` | 401 |
| `AccessDeniedException` (Spring Security) | `ACCESS_DENIED` | 403 |
| `PessimisticLockingFailureException`, `OptimisticLockingFailureException`, `DataIntegrityViolationException` | `CONCURRENCY_CONFLICT` — message chung, không lộ tên constraint | 409 |
| Lỗi web khác của Spring mang status 4xx (`ResponseStatusException`…) | theo status: 401/403/404/405/415 như trên, còn lại `MALFORMED_REQUEST` | theo mã |
| Mọi exception khác | `INTERNAL_ERROR` — message chung, log ERROR kèm stacktrace | 500 |

`ErrorResponse` chỉ được tạo trong `GlobalExceptionHandler`. Lỗi 401/403 sinh ra trong security filter chain được `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` chuyển về handler này qua `HandlerExceptionResolver`, không tự ghi body.

## 4.4. Chọn exception nào

| Tình huống | Exception |
|---|---|
| Điều kiện ở cột *Điều kiện* của bảng 03, hoặc cột *Xử lý khi vi phạm* của 02 có chữ "từ chối" / "không cho" | `BusinessRuleViolationException("BR-…", message)` |
| Trạng thái hiện tại không có cạnh tới trạng thái đích | `InvalidStateTransitionException` — do `StateMachineBase.validateTransition()` ném, service không tự tạo |
| Rule ghi "từ chối (403)" vì vượt phân cấp / ngoài phạm vi (BR-QT-01, 07, BR-BH-02, BR-DG-02) | `AccessDeniedScopeException` |
| Id không tồn tại | `ResourceNotFoundException` |
| Hai thao tác đồng thời tranh cùng dữ liệu, phát hiện ở tầng ứng dụng | `ConcurrencyConflictException` (lỗi khóa/ràng buộc của DB đã được handler map 409 sẵn) |
| Rule ghi "cảnh báo, không chặn" | **Không ném exception** — xem [06](06-validation.md) |
| Rule chỉ là "ẩn nút" / "không hiển thị" | Vẫn phải chặn ở service bằng exception tương ứng: request có thể gửi thẳng tới API |

Message trả client viết tiếng Việt, nói điều gì sai và (nếu có) cách xử lý, không lộ tên bảng, cột hay constraint. `BusinessRuleViolationException` tự nối `" (BR-…)"` vào cuối message.

---

[← 3. Naming Convention](03-naming-convention.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 5. FSM Implementation Pattern →](05-fsm-pattern.md)
