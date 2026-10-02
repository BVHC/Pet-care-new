[← Backend Convention Index](../backend-convention.md)

# 9. Testing

| Loại test | Bắt buộc cho | Công cụ |
|---|---|---|
| Unit test | Mọi Service / TransitionHandler chứa business rule hoặc guard | JUnit 5 + Mockito |
| Integration test | Mọi FSM (verify đúng bảng transition trong `03-state-machines.md`) + API có nghiệp vụ phức tạp (Atomic Reschedule, Refund Maker-Checker) | `@SpringBootTest` + Testcontainers |

Repository CRUD thuần không bắt buộc test riêng.

## Base test class cho FSM

`src/test/java/com/petcare/platform/fsm/FsmTransitionTestBase<S>` — lớp con khai báo một **bản sao độc lập** của bảng chuyển trạng thái trong `03-state-machines.md` (không đọc lại từ handler, nếu không test sẽ tự khẳng định chính nó). Base tự sinh test cho **mọi** cặp `from × to` của enum và mọi trạng thái khởi tạo, nên không thể bỏ sót cặp nào.

```java
public abstract class FsmTransitionTestBase<S extends Enum<S>> {
    protected abstract StateMachineBase<S> handler();
    protected abstract Class<S> stateType();
    protected abstract Map<S, Set<S>> expectedTransitions();   // bảng 03, gồm cả X → X
    protected abstract Set<S> expectedInitialStates();         // các dòng "— → X"

    protected void assertValidTransition(S from, S to) { ... }
    protected void assertInvalidTransition(S from, S to) { ... }

    @TestFactory Stream<DynamicTest> everyTransitionPairMatchesSpec() { ... }  // hợp lệ ⇔ có trong expectedTransitions
    @TestFactory Stream<DynamicTest> everyInitialStateMatchesSpec() { ... }
}

class AppointmentTransitionHandlerTest extends FsmTransitionTestBase<AppointmentStatus> {

    @Override protected StateMachineBase<AppointmentStatus> handler() { return new AppointmentTransitionHandler(); }
    @Override protected Class<AppointmentStatus> stateType() { return AppointmentStatus.class; }

    @Override
    protected Map<AppointmentStatus, Set<AppointmentStatus>> expectedTransitions() {
        return Map.of(
            BOOKED,     Set.of(BOOKED, CHECKED_IN, CANCELLED, NO_SHOW),   // #2, #3, #4/#5, #6
            CHECKED_IN, Set.of(COMPLETED, CANCELLED));                     // #7, #8
    }

    @Override protected Set<AppointmentStatus> expectedInitialStates() { return Set.of(BOOKED); }  // #1
}
```

Lớp con chỉ khai báo bảng; thêm `@Test` riêng cho guard nghiệp vụ (mã `BR-…`) của từng thao tác.

---

[← 8. Logging & Audit](08-logging-and-audit.md) · [Backend Convention Index](../backend-convention.md)
