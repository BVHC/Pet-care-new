[← Backend Convention Index](INDEX.md)

# 5. FSM Implementation Pattern

Tự code bằng **Enum State + Transition Map**, không dùng Spring StateMachine. Lớp nền có sẵn ở `platform/fsm`:

```java
public interface Transitionable<S extends Enum<S>> {
    Map<S, Set<S>> allowedTransitions();   // cạnh from → to, gồm cả X → X
    Set<S> initialStates();                // các dòng "— → X"
}

public abstract class StateMachineBase<S extends Enum<S>> implements Transitionable<S> {
    public boolean canTransition(S from, S to) { ... }
    public void validateTransition(S from, S to) { ... }  // sai → InvalidStateTransitionException(fsmName, from, to)
    public void validateInitial(S to) { ... }             // sai → InvalidStateTransitionException(fsmName, null, to)
}
```

Mỗi đối tượng trong bảng 10 đối tượng của `03-state-machines.md` (L16–27) có một `{Entity}TransitionHandler extends StateMachineBase<{Entity}Status>` ở `module/<feature>/fsm/`.

## Chép bảng 03 thành transition map

```java
// module/<feature>/fsm — bảng "3. Lịch hẹn" của 03-state-machines.md
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
        );                                   // COMPLETED, CANCELLED, NO_SHOW: FINAL, không có khóa
    }

    @Override
    public Set<AppointmentStatus> initialStates() {
        return Set.of(BOOKED);               // #1
    }
}
```

- Chép **đúng** cột *Từ → Sang* của bảng đánh số, ghi `#n` cạnh mỗi cạnh. Không tự suy diễn thêm cạnh, kể cả khi thấy "hợp lý".
- Trạng thái `FINAL` không có khóa trong map.
- Nhiều dòng cùng một cặp `from → to` (Lịch hẹn #4 và #5) là **một cạnh**, nhưng là các thao tác khác nhau với guard và hệ quả khác nhau → các method khác nhau.
- Dòng `X → X` (đổi giờ, gán lại, gia hạn, ghi nhật ký…) là cạnh `X → X`; thao tác đó gọi `validateTransition(current, current)`. Dòng ghi `A/B → (giữ nguyên)` (Đặt chỗ #2, #8, #9) là hai cạnh `A → A` và `B → B`.
- Dòng `— → X` khai báo trong `initialStates()` và kiểm tra bằng `validateInitial(X)` lúc tạo.

## Trường hợp đặc biệt trong bảng 03

| Đối tượng | Điểm cần chú ý |
|---|---|
| Tài khoản (#1) | Hai chiều độc lập: `status` (#1–#6) nằm trong map; khóa/mở khóa (#7, #8) là cờ `is_locked`, **không** nằm trong map, kiểm tra bằng guard (BR-QT-11, 12). #3 là xóa cứng (BR-TK-08), không phải cạnh |
| Order (#5) | Trạng thái khởi tạo phụ thuộc `source`: `OPEN` với `VISIT`/`RETAIL`, `PENDING` với `BOARDING`. `initialStates()` = `{OPEN, PENDING}`, nên service phải có thêm guard theo `source` trước `validateInitial()`. Tương tự các dòng chỉ áp dụng cho một số nguồn (#4–#7, #9, #10) |
| Lịch hẹn, Order, Chuồng, Care Task | Có cạnh do `SYS ← <Đối tượng>#n` kích hoạt: không có actor gọi trực tiếp, service của aggregate phát sự kiện gọi tới trong cùng transaction ([07](07-transaction-management.md)). Cạnh do `ST…` kích hoạt chạy từ job định kỳ |
| Bài viết, Feedback | Không có bảng trong 03; vòng đời chỉ ở rule (BR-BV-02 `DRAFT → PUBLISHED ⇄ HIDDEN`, BR-DG-04 `NEW → SEEN → RESOLVED`). Nếu dùng `StateMachineBase` thì chép từ câu chữ của rule và ghi nguồn là mã BR |
| Bệnh án | Không có trạng thái riêng: khóa khi Visit `COMPLETED` (BR-KB-01) |

## Thao tác chuyển trạng thái

```java
/** Lịch hẹn#2 — đổi khung giờ (BR-LH-06). Tên method minh họa, tên thật chốt theo 03-naming-convention.md. */
@Transactional
public Appointment rescheduleAppointment(Long appointmentId, LocalDate newDate, LocalTime newStart) {
    Appointment appt = repository.findById(appointmentId)
            .orElseThrow(() -> new ResourceNotFoundException("Lịch hẹn", appointmentId));
    if (appt.getRescheduleCount() >= maxReschedules) {                                  // [CFG] 3
        throw new BusinessRuleViolationException("BR-LH-06", "Lịch hẹn đã hết lượt đổi");  // 1. guard nghiệp vụ
    }
    validateTransition(appt.getStatus(), AppointmentStatus.BOOKED);                     // 2. whitelist trạng thái
    ...                                                                                 // 3. ghi thay đổi
}
```

- Thứ tự: **guard nghiệp vụ trước** (`BusinessRuleViolationException`), **rồi mới** `validateTransition()` (`InvalidStateTransitionException`). Hai loại lỗi tách biệt để client phân biệt "vi phạm rule" (400) với "sai trạng thái" (409).
- Điều kiện ở cột *Điều kiện* là guard; hệ quả ở cột *Hệ quả* chạy sau khi đổi trạng thái, trong cùng transaction.
- Hệ quả sang aggregate khác không gọi từ TransitionHandler mà từ service ([02](02-layering-and-dto.md)).

---

[← 4. Exception & Error Handling](04-exception-handling.md) · [Backend Convention Index](INDEX.md) · [Tiếp: 6. Validation →](06-validation.md)
