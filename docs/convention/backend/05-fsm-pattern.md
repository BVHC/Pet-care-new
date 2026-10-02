[← Backend Convention Index](../backend-convention.md)

# 5. FSM Implementation Pattern

Tự code bằng **Enum State + Transition Map**, không dùng Spring StateMachine.

```java
// platform/fsm
public interface Transitionable<S extends Enum<S>> {
    Map<S, Set<S>> allowedTransitions();   // cạnh from → to, gồm cả X → X
    Set<S> initialStates();                // các dòng "— → X"
}

public abstract class StateMachineBase<S extends Enum<S>> implements Transitionable<S> {
    public boolean canTransition(S from, S to) { ... }
    public void validateTransition(S from, S to) { ... }  // sai → InvalidStateTransitionException(fsmName, from, to)
    public void validateInitial(S to) { ... }             // sai → InvalidStateTransitionException(fsmName, null, to)
}

// module/appointment/fsm — bảng "3. Lịch hẹn" của 03-state-machines.md
@Component
public class AppointmentTransitionHandler extends StateMachineBase<AppointmentStatus> {

    @Override
    public Map<AppointmentStatus, Set<AppointmentStatus>> allowedTransitions() {
        return Map.of(
            BOOKED,     Set.of(BOOKED,       // #2 đổi khung giờ
                               CHECKED_IN,   // #3
                               CANCELLED,    // #4, #5
                               NO_SHOW),     // #6
            CHECKED_IN, Set.of(COMPLETED,    // #7
                               CANCELLED)    // #8
        );
    }

    @Override
    public Set<AppointmentStatus> initialStates() {
        return Set.of(BOOKED);               // #1
    }

    @Transactional
    public void rescheduleAppointment(Long appointmentId, ...) {   // tên method: xem 03-naming-convention.md
        Appointment appt = repository.findByIdOrThrow(appointmentId);
        if (appt.getRescheduleCount() >= maxReschedules) {
            throw new BusinessRuleViolationException("BR-LH-06", "Lịch hẹn đã hết lượt đổi");  // guard trước
        }
        validateTransition(appt.getStatus(), BOOKED);                                      // rồi mới kiểm tra trạng thái
        ...
    }
}
```

- Transition map chép **đúng** bảng chuyển trạng thái đánh số trong `03-state-machines.md` (cột *Từ → Sang*), ghi số dòng (`#n`) cạnh mỗi cạnh. Không tự suy diễn thêm cạnh.
- Dòng `X → X` (đổi giờ, gán lại, gia hạn — thao tác giữ nguyên trạng thái nhưng có điều kiện) là cạnh `X → X` trong map; thao tác đó gọi `validateTransition(current, X)`. Dòng `— → X` (tạo mới) khai báo trong `initialStates()` và kiểm tra bằng `validateInitial(X)`.
- Guard nghiệp vụ (mã `BR-…`) validate **trước** khi gọi `validateTransition()`, ném `BusinessRuleViolationException` riêng với guard, `InvalidStateTransitionException` riêng với sai trạng thái.

---

[← 4. Exception & Error Handling](04-exception-handling.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 6. Validation →](06-validation.md)
