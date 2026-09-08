[← Backend Convention Index](../backend-convention.md)

# 9. Testing

| Loại test | Bắt buộc cho | Công cụ |
|---|---|---|
| Unit test | Mọi Service / TransitionHandler chứa business rule hoặc guard | JUnit 5 + Mockito |
| Integration test | Mọi FSM (verify đúng bảng transition trong `03-state-machines.md`) + API có nghiệp vụ phức tạp (Atomic Reschedule, Refund Maker-Checker) | `@SpringBootTest` + Testcontainers |

Repository CRUD thuần không bắt buộc test riêng.

## Base test class cho 17 FSM

```java
public abstract class FsmTransitionTestBase<S extends Enum<S>> {
    protected abstract StateMachineBase<S> handler();

    protected void assertValidTransition(S from, S to) {
        assertDoesNotThrow(() -> handler().validateTransition(from, to));
    }

    protected void assertInvalidTransition(S from, S to) {
        assertThrows(InvalidStateTransitionException.class, () -> handler().validateTransition(from, to));
    }
}

class AppointmentTransitionHandlerTest extends FsmTransitionTestBase<AppointmentStatus> {

    @Override
    protected StateMachineBase<AppointmentStatus> handler() { return new AppointmentTransitionHandler(); }

    @ParameterizedTest
    @CsvSource({ "BOOKED,CONFIRMED", "CHECKED_IN,IN_PROGRESS", "IN_PROGRESS,COMPLETED" })
    void validTransitions(AppointmentStatus from, AppointmentStatus to) { assertValidTransition(from, to); }

    @ParameterizedTest
    @CsvSource({ "CHECKED_IN,COMPLETED" }) // No Direct Checkout Invariant
    void invalidTransitions(AppointmentStatus from, AppointmentStatus to) { assertInvalidTransition(from, to); }
}
```

Mỗi FSM class con liệt kê **đầy đủ** cặp (from, to) hợp lệ/không hợp lệ theo đúng bảng mermaid trong `03-state-machines.md` — không được bỏ sót cặp nào.

---

[← 8. Logging & Audit](08-logging-and-audit.md) · [Backend Convention Index](../backend-convention.md)
