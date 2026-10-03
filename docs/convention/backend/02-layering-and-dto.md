[← Backend Convention Index](INDEX.md)

# 2. Layering & Luồng gọi

```
TraceIdFilter → SecurityFilterChain
  → Controller   (@Valid, @PreAuthorize; trả ApiResponse<…>)
  → Service      (@Transactional; guard BR-… → BusinessRuleViolationException)
      └─ (đối tượng có SM) → {Entity}TransitionHandler extends StateMachineBase<S>
  → Repository   (JpaRepository)
Lỗi bất kỳ → GlobalExceptionHandler → ErrorResponse
```

| Layer | Làm | Không làm |
|---|---|---|
| **Controller** | Nhận `Request` (record), validate hình thức (`@Valid`), phân quyền theo role (`@PreAuthorize`), gọi một hàm service, bọc kết quả vào `ApiResponse` | Business logic, biết mã `BR-…`, `@Transactional`, nhận/trả entity |
| **Service** | Một hàm = một use case: nạp dữ liệu, kiểm tra rule `BR-…`, gọi TransitionHandler, ghi DB, gọi service module khác cho hệ quả liên aggregate, ghi audit, ghi outbox | Tự viết if/else whitelist trạng thái (đó là việc của TransitionHandler) |
| **TransitionHandler** | Whitelist chuyển trạng thái chép từ bảng 03 và các thao tác chuyển trạng thái của aggregate (xem [05](05-fsm-pattern.md)) | Gọi sang module khác |
| **Repository** | `JpaRepository` (hoặc `Repository` hẹp hơn khi cần chặn thao tác, như `AuditLogRepository` chỉ có `save`) | Logic nghiệp vụ |

Phạm vi chi nhánh (A05–A08 chỉ thao tác trên dữ liệu chi nhánh mình, domain-model nguyên tắc 8): vi phạm thì ném `AccessDeniedScopeException` (403, client nhận message chung). Cơ chế lấy chi nhánh của người đăng nhập và nơi kiểm tra **chưa chốt**, làm cùng module TK.

## DTO

- **Cấm** trả `@Entity` qua controller, kể cả lồng trong DTO.
- DTO là **Java record**, tên theo [03](03-naming-convention.md).
- Mapping Entity ↔ DTO dùng **MapStruct** (`@Mapper(componentModel = "spring")`), không viết mapper tay.
- Trường chỉ nhân viên được xem (ví dụ `medical_records.internal_note`, `feedbacks.resolution_note` — BR-KB-01, BR-KH-07, BR-DG-04) dùng **DTO riêng cho khách**, không dựa vào việc set `null`.

```java
public record BookAppointmentRequest(
        @NotNull Long petId,
        @NotNull Long branchId,
        @NotNull Long serviceId,
        @NotNull LocalDate slotDate,      // appointments.slot_date
        @NotNull LocalTime slotStart,     // appointments.slot_start, khung 30 phút
        @Size(max = 500) String note
) {}

public record AppointmentResponse(
        Long id, String code, String status, LocalDate slotDate, LocalTime slotStart, int rescheduleCount
) {}

@Mapper(componentModel = "spring")
public interface AppointmentMapper {
    AppointmentResponse toResponse(Appointment entity);
}
```

## Envelope thành công (`platform/model`)

| Trường hợp | Trả về |
|---|---|
| Một đối tượng | `ApiResponse.ok(dto)` hoặc `ApiResponse.ok(dto, "message")` |
| Tạo mới (HTTP 201) | `ApiResponse.created(dto, "message")` và `@ResponseStatus(HttpStatus.CREATED)` |
| Danh sách | `ApiResponse.ok(PageResponse.of(page))`, `page` đánh số từ 0 |

Payload luôn nằm một cấp trong `data`. Lỗi không bao giờ tạo ở controller: ném exception, `GlobalExceptionHandler` dựng `ErrorResponse` ([04](04-exception-handling.md)).

## Entity

Schema do Flyway sở hữu (`db/migration/V{n}__*.sql`, `ddl-auto=validate`); entity phải khớp đúng bảng trong `05-erd.md` **và** file migration.

| Việc | Quy ước |
|---|---|
| Lớp cha | ROOT/PART/REF kế thừa `TimestampedEntity` (`created_at`, `updated_at`); LOG kế thừa `CreatedAtEntity`. Loại của từng bảng ghi ở tiêu đề mục trong erd |
| Khóa chính | `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id`. 9 bảng PK đặc biệt (1–1, ghép, chuỗi) liệt kê ở `system-overview.md` §4b |
| Tham chiếu aggregate khác | Cột `Long <tên>Id`, không map quan hệ JPA tới entity của module khác ([01](01-package-structure.md)) |
| Enum | `@Enumerated(EnumType.STRING)`, giá trị là mã ASCII của erd §0 (`VACCINE_DUE`, không phải `TÁI_CHỦNG`) |
| Kiểu Java | Tiền `long`/`Long` (VND); `TIMESTAMPTZ` → `Instant`; `DATE` → `LocalDate`; `TIME` → `LocalTime`; cân nặng `BigDecimal`; `JSONB` → `@JdbcTypeCode(SqlTypes.JSON)` |
| Thời gian | Lấy từ bean `Clock`: `Instant.now(clock)`, `LocalDate.now(clock)`. Không gọi `now()` không có clock |
| Xóa | Không xóa cứng, trừ các trường hợp rule cho phép (erd §0). Không dùng `CascadeType.REMOVE`/`orphanRemoval`; service xóa con trước, cùng transaction, theo thứ tự ở `system-overview.md` §4b |
| Bảng LOG | Chỉ thêm mới. `audit_logs` dùng `@Immutable`; riêng `otp_tokens` được cập nhật vài cột (erd L146) |

---

[← 1. Package Structure](01-package-structure.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 3. Naming Convention →](03-naming-convention.md)
