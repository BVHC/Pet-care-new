[← Backend Convention Index](INDEX.md)

# 9. Testing

| Loại test | Bắt buộc cho | Công cụ | Tên file · lệnh chạy |
|---|---|---|---|
| Unit test | Mọi Service / TransitionHandler chứa business rule hoặc guard | JUnit 5 + Mockito (+ `spring-security-test` khi cần principal) | `*Test.java` · `mvn test` (Surefire) |
| Integration test | Mọi FSM (đúng bảng chuyển trạng thái của `03-state-machines.md`) + API có nghiệp vụ phức tạp (hệ quả liên aggregate, thu tiền + trừ kho, quota/sức chứa khi chạy đồng thời) | `@SpringBootTest` + Testcontainers Postgres 17 | `*IT.java` · `mvn verify` (Failsafe) |

Repository CRUD thuần không bắt buộc test riêng. Một test: `mvn test -Dtest=XxxTest#method`; một IT: `mvn verify -Dit.test=XxxIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false`.

## Môi trường

- **Cần Docker đang chạy.** Test chạm DB khai báo `@ActiveProfiles("test")` và `@Import(TestcontainersConfiguration.class)`: mỗi lượt chạy có một container `postgres:17` sạch, Flyway áp migration thật, Hibernate `ddl-auto=validate`. Không dùng H2, không cần DB cục bộ.
- Surefire/Failsafe ép JVM `-Duser.timezone=Asia/Ho_Chi_Minh`. Test không được dựa vào timezone JVM: thay bean `Clock` bằng `Clock.fixed(…, TimeConfig.BUSINESS_ZONE)` cho mọi rule phụ thuộc thời gian (hạn đặt 24h, cửa sổ check-in, OTP hết hạn…). Riêng `created_at`/`updated_at` do Hibernate đặt theo giờ JVM nên `Clock.fixed` không điều khiển được (system-overview §7).
- `audit_logs` không xóa được (trigger BR-QT-16), nên IT không dọn bảng này: mỗi test đánh dấu bản ghi của mình (ví dụ `reason` là UUID) rồi truy vấn theo dấu đó, như `AuditRecorderIT`.
- Unit test của service dùng mock `AuditRecorder` và kiểm tra `AuditEntry` được truyền vào. IT gọi qua service có `@Transactional`, vì `record()` là `MANDATORY` và sẽ lỗi nếu gọi ngoài transaction.

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

Base chỉ phủ whitelist trạng thái. Ngoài ra mỗi FSM cần:

- `@Test` riêng cho **từng guard** `BR-…` của từng thao tác: một case vi phạm (đúng mã rule trong message) và một case biên hợp lệ.
- Với trường hợp đặc biệt ở [05](05-fsm-pattern.md): guard theo `source` của Order, cờ `is_locked` của Tài khoản.
- Một IT cho mỗi chuỗi hệ quả ở Phụ lục 03 mà module tham gia: sau thao tác, kiểm tra trạng thái của **mọi** aggregate bị kéo theo, và kiểm tra rollback toàn bộ khi một bước giữa chừng lỗi.

## Sự kiện đồng bộ

- Cơ chế của [07](07-transaction-management.md) §7.2 (listener chạy cùng thread và cùng transaction với bên phát, listener lỗi thì rollback cả use case, phát ngoài transaction thì lỗi) đã được chứng minh **một lần** ở `src/test/java/com/petcare/DomainEventTransactionIT`. Module không viết lại test cho cơ chế.
- Unit test service bên phát: mock `ApplicationEventPublisher`, verify đúng record sự kiện được phát sau khi đổi trạng thái; case guard vi phạm thì verify **không** phát.
- Unit test listener: verify gọi đúng method service của module mình với dữ liệu lấy từ sự kiện.
- IT chuỗi hệ quả (bullet cuối mục trên) vẫn bắt buộc cho từng sự kiện thật: kiểm tra mọi module nhận đã đổi dữ liệu, và rollback toàn bộ khi một listener lỗi.

---

[← 8. Logging & Audit](08-logging-and-audit.md) · [Backend Convention Index](INDEX.md)
