[← Backend Convention Index](../backend-convention.md)

# 2. Layering & Luồng gọi

```
Controller → Service → Repository
              │
              └─ (FSM phức tạp) → {Entity}TransitionHandler → Repository
```

- **Controller**: nhận `Request` (record), validate format (`@Valid`), gọi Service, trả `Response` (record). Không chứa business logic, không biết RULE-ID.
- **Service**: orchestration + business rule validation + gọi Repository/publish event. Với module có FSM (17 module), Service **không** tự viết if/else transition mà gọi `{Entity}TransitionHandler`.
- **TransitionHandler**: chỉ chịu trách nhiệm validate guard + transition state, extend `StateMachineBase<S>` (xem [5. FSM Implementation Pattern](05-fsm-pattern.md)).
- **Repository**: `JpaRepository`, không chứa logic nghiệp vụ.

## Entity vs DTO

- **Cấm tuyệt đối** trả `@Entity` qua Controller.
- DTO là **Java record**, đặt tên theo [3. Naming Convention](03-naming-convention.md).
- Mapping Entity ↔ DTO dùng **MapStruct** (`@Mapper(componentModel = "spring")`), không viết mapper thủ công.

```java
public record BookAppointmentRequest(
        @NotNull Long petId,
        @NotNull Long serviceId,
        @NotNull @Future LocalDateTime startTime
) {}

public record AppointmentResponse(
        Long id, String status, LocalDateTime startTime, LocalDateTime endTime
) {}

@Mapper(componentModel = "spring")
public interface AppointmentMapper {
    AppointmentResponse toResponse(Appointment entity);
}
```

---

[← 1. Package Structure](01-package-structure.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 3. Naming Convention →](03-naming-convention.md)
