package com.petcare.module.visit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;

/**
 * Sắp lại hàng đợi bằng "đặt lượt trước một lượt khác" (BR-TN-04, A3; docs/adr/0061 Q1). Lớp thuần, khuôn
 * {@link QueuePlacement}: chỉ tính thay đổi, service áp vào entity đã khóa.
 * <ul>
 * <li>Lượt được sắp nhận hạng của lượt đích; khóa = khóa đích − 1 µs, nhưng phải lớn hơn khóa lượt liền trước cùng
 * hạng (nếu có) ít nhất 1 µs.</li>
 * <li>Lượt cùng hạng từ lượt đích trở đi có khóa ≤ khóa vừa đặt thì dồn lên +1 µs, dừng ở lượt đầu tiên không phải đổi —
 * các lượt hẹn cùng khung giờ có cùng khóa (giờ hẹn) nên không thể chỉ lấy trung điểm.</li>
 * <li>Đã đứng ngay trước lượt đích và cùng hạng → không đổi gì (docs/adr/0061 Q4).</li>
 * </ul>
 * Ranh giới cấp cứu, trạng thái {@code WAITING} và chi nhánh do service kiểm trước; ở đây vi phạm là lỗi lập trình.
 */
final class QueueReorder {

    private static final long STEP_MICROS = 1;

    /** Một lượt đổi hạng / khóa. */
    record Change(Visit visit, PriorityClass priorityClass, Instant queueSortKey) {}

    private QueueReorder() {
    }

    /**
     * @param waiting mọi lượt {@code WAITING} của chi nhánh (đã khóa), thứ tự tùy ý; chứa {@code moved} và {@code target}
     * @param moved   lượt được sắp
     * @param target  lượt mà {@code moved} phải đứng ngay trước
     * @return các thay đổi, lượt được sắp đứng đầu; rỗng = không phải đổi gì
     */
    static List<Change> placeBefore(List<Visit> waiting, Visit moved, Visit target) {
        Objects.requireNonNull(waiting, "waiting");
        Objects.requireNonNull(moved, "moved");
        Objects.requireNonNull(target, "target");
        if (moved == target) {
            throw new IllegalArgumentException("Lượt không thể đặt trước chính nó");
        }
        if ((moved.getPriorityClass() == PriorityClass.EMERGENCY)
                != (target.getPriorityClass() == PriorityClass.EMERGENCY)) {
            throw new IllegalArgumentException("Sắp lại không được vượt ranh giới ca cấp cứu");
        }
        List<Visit> sorted = new ArrayList<>(waiting);
        sorted.sort(QueueOrder.BR_TN_04);
        int movedAt = indexOf(sorted, moved);
        int targetAt = indexOf(sorted, target);
        PriorityClass priorityClass = target.getPriorityClass();
        if (movedAt == targetAt - 1 && moved.getPriorityClass() == priorityClass) {
            return List.of();
        }

        sorted.remove(movedAt);
        targetAt = indexOf(sorted, target);
        Visit previous = targetAt > 0 ? sorted.get(targetAt - 1) : null;
        Instant key = target.getQueueSortKey().minus(STEP_MICROS, ChronoUnit.MICROS);
        if (previous != null && previous.getPriorityClass() == priorityClass) {
            Instant afterPrevious = previous.getQueueSortKey().plus(STEP_MICROS, ChronoUnit.MICROS);
            if (afterPrevious.isAfter(key)) {
                key = afterPrevious;
            }
        }

        List<Change> changes = new ArrayList<>();
        changes.add(new Change(moved, priorityClass, key));
        Instant last = key;
        for (int i = targetAt; i < sorted.size(); i++) {
            Visit next = sorted.get(i);
            if (next.getPriorityClass() != priorityClass || next.getQueueSortKey().isAfter(last)) {
                break;
            }
            last = last.plus(STEP_MICROS, ChronoUnit.MICROS);
            changes.add(new Change(next, priorityClass, last));
        }
        return changes;
    }

    private static int indexOf(List<Visit> visits, Visit visit) {
        for (int i = 0; i < visits.size(); i++) {
            if (visits.get(i) == visit) {
                return i;
            }
        }
        throw new IllegalArgumentException("Lượt #" + visit.getId() + " không nằm trong danh sách WAITING đã khóa");
    }
}
