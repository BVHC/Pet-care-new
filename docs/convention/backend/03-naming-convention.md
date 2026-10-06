[← Backend Convention Index](INDEX.md)

# 3. Naming Convention

Code và định danh viết tiếng Anh; tài liệu, message trả client và comment viết tiếng Việt.

| Đối tượng | Quy tắc | Ví dụ |
|---|---|---|
| Package con trong module | Số ít, chuẩn Spring Boot | `controller`, `service`, `repository`, `entity`, `dto`, `mapper`, `fsm`, `job`, `listener`, `exception` |
| Sự kiện đồng bộ | Record `{Việc}Event` trong `api/` của module phát (06 §3) | `PetDeceasedEvent` |
| Listener sự kiện | `{TênSựKiện}Listener` (bỏ hậu tố `Event`) trong `listener/` của module nhận, method `on({TênSựKiện}Event)`; mỗi module một listener cho mỗi sự kiện (07 §7.2) | `appointment/listener/PetDeceasedListener` |
| Job định kỳ | `{Việc}Job`, method `run()`; property `app.jobs.<việc-kebab>.*`, record `{Việc}Properties`; mã log `<VIỆC>` / `<VIỆC>_FAILED` (ADR-0007) | `SessionCleanupJob`, `app.jobs.session-cleanup.cron`, `SESSION_CLEANUP` |
| Entity | Tên model ở `04-domain-model.md` (PascalCase), **không hậu tố `Entity`**. Bảng tương ứng: erd §12 | `Appointment`, `Visit`, `CashierShift` |
| Entity của `platform/` | Được thêm hậu tố `Entity` để không trùng tên khái niệm | `AuditLogEntity` |
| Enum trạng thái / loại | `{Entity}Status`, `{Entity}{Thuộc tính}`; hằng số là mã ASCII của erd | `AppointmentStatus.NO_SHOW`, `CareTaskType.VACCINE_DUE` |
| Field entity | camelCase của tên cột | `slot_date` → `slotDate`, `reschedule_count` → `rescheduleCount` |
| DTO request | `{Command}Request` | `BookAppointmentRequest` |
| DTO response | `{Entity}Response`; bản rút gọn hoặc bản cho khách thêm hậu tố mô tả | `AppointmentResponse`, `MedicalRecordCustomerResponse` |
| Method transition / use case | Tên command (xem dưới) | |
| Method service khác | Verb + Noun tiếng Anh | `findById()`, `calculateNights()` |
| Hằng số | `UPPER_SNAKE_CASE` | `MAX_ADDRESSES` |
| Mã rule | Chuỗi `"BR-<MÃ>-<số>"` truyền thẳng vào `BusinessRuleViolationException` tại chỗ kiểm tra, đúng mã trong `02-business-rules.md` | `"BR-LH-05"` |
| Mã action audit | `<ĐỐI_TƯỢNG>_<QUÁ_KHỨ>`, hằng số do module giữ (một lớp `…AuditActions` mỗi module) | `ACCOUNT_LOCKED`, `ORDER_PAID` |
| Khóa `system_configs` | `<nhóm>.<tên>` chữ thường | `otp.ttl_minutes`, `appointment.max_reschedules` (erd L170) |

## Tên command

Đặc tả v16 **không có** bảng Actor↔Command hay glossary tiếng Anh: `01-business-operations.md` chỉ liệt kê use case tiếng Việt. Tên command chốt theo cách sau:

1. Nguồn: cột *Sự kiện* của bảng chuyển trạng thái (`03-state-machines.md`) hoặc tên use case/thao tác trong `01`, `02`.
2. Đặt tên: động từ + danh từ tiếng Anh, camelCase, một tên cho một dòng bảng (hoặc một thao tác). Dòng do `SYS ← …` kích hoạt cũng có tên riêng, vì service của aggregate phát sự kiện gọi tới nó.
3. Danh sách tên đề xuất nằm trong plan của module, **người dùng duyệt trước khi code**. Không tự đặt tên rồi dùng luôn.
4. Javadoc của method ghi dòng nguồn, ví dụ `/** Lịch hẹn#2 — đổi khung giờ (BR-LH-06). */`.

Cùng một tên được dùng cho method service, method TransitionHandler (nếu có) và DTO request (`{Command}Request`).

---

[← 2. Layering & DTO](02-layering-and-dto.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 4. Exception & Error Handling →](04-exception-handling.md)
