[← Backend Convention Index](../backend-convention.md)

# 3. Naming Convention

| Đối tượng | Quy tắc | Ví dụ |
|---|---|---|
| Package con trong module | Số ít, chuẩn Spring Boot | `controller`, `service`, `repository`, `entity`, `dto`, `mapper`, `fsm`, `exception` |
| Entity | Danh từ số ít, trùng Aggregate Root trong domain model | `Appointment`, `Invoice`, `Refund` |
| DTO Request | `{Command}Request` — Command lấy đúng tên trong `01-business-operations.md`/glossary | `BookAppointmentRequest`, `ApproveRefundRequest` |
| DTO Response | `{Entity}Response` | `AppointmentResponse`, `InvoiceResponse` |
| Method transition FSM | **Trùng chính xác tên Command nghiệp vụ**, không tự đặt lại | `bookAppointment()`, `checkInAppointment()`, `markNoShow()`, `abortAppointment()` |
| Method Service khác | Verb + Noun, tiếng Anh | `findById()`, `calculateTotalAmount()` |
| Hằng số | `UPPER_SNAKE_CASE` | `HOLD_TTL_SECONDS`, `MAX_OTP_RETRY` |
| RULE-ID trong code | Truyền dạng String hằng vào exception, không hard-code rải rác | `"RULE-06-11"` |

**Bắt buộc:** tên method transition phải khớp 100% với Command trong bảng Actor↔Command của `01-business-operations.md`. Không dùng tên tự sáng tác (vd không dùng `startService()` thay cho `startAppointmentService()`).

---

[← 2. Layering & DTO](02-layering-and-dto.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 4. Exception & Error Handling →](04-exception-handling.md)
