[← Backend Convention Index](../backend-convention.md)

# 5. FSM Implementation Pattern

Tự code bằng **Enum State + Transition Map**, không dùng Spring StateMachine.

```java
// platform/fsm
public interface Transitionable<S extends Enum<S>> {
    Map<S, Set<S>> allowedTransitions();
}

public abstract class StateMachineBase<S extends Enum<S>> implements Transitionable<S> {
    public void validateTransition(S from, S to) {
        Set<S> allowed = allowedTransitions().getOrDefault(from, Set.of());
        if (!allowed.contains(to)) {
            throw new InvalidStateTransitionException(getClass().getSimpleName(), from.name(), to.name());
        }
    }
}

// module/appointment/fsm
@Component
public class AppointmentTransitionHandler extends StateMachineBase<AppointmentStatus> {

    @Override
    public Map<AppointmentStatus, Set<AppointmentStatus>> allowedTransitions() {
        return Map.of(
            BOOKED, Set.of(CONFIRMED, CHECKED_IN, CANCELLED, NO_SHOW),
            CONFIRMED, Set.of(CHECKED_IN, CANCELLED, NO_SHOW, BOOKED), // Reschedule
            CHECKED_IN, Set.of(IN_PROGRESS, CANCELLED),
            IN_PROGRESS, Set.of(COMPLETED, ABORTED)
        );
    }

    @Transactional
    public void checkInAppointment(Long appointmentId) {
        Appointment appt = repository.findByIdOrThrow(appointmentId);
        validateTransition(appt.getStatus(), CHECKED_IN); // RULE-06-06
        appt.setStatus(CHECKED_IN);
    }
}
```

- Transition map lấy trực tiếp từ bảng mermaid `stateDiagram-v2` trong `03-state-machines.md` — không tự suy diễn thêm cạnh.
- Guard nghiệp vụ (RULE-ID) validate **trước** khi gọi `validateTransition()`, ném `BusinessRuleViolationException` riêng với guard, `InvalidStateTransitionException` riêng với sai trạng thái.

---

[← 4. Exception & Error Handling](04-exception-handling.md) · [Backend Convention Index](../backend-convention.md) · [Tiếp: 6. Validation →](06-validation.md)
