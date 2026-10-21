package com.petcare.module.visit.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.petcare.module.visit.entity.PriorityClass;

/**
 * Hạng ưu tiên và khóa sắp xếp của một lượt khi tiếp nhận (BR-TN-03, BR-TN-04; docs/adr/0059). Lớp thuần, khuôn
 * {@code CheckInWindow}.
 * <ul>
 * <li>Đánh dấu cấp cứu → {@code EMERGENCY}, khóa = giờ check-in (kể cả khi có lịch hẹn).</li>
 * <li>Có lịch hẹn, đến sớm hoặc trễ tối đa {@code visit.late_priority_minutes} [CFG] (biên tính là giữ ưu tiên) →
 * {@code APPOINTMENT}, khóa = giờ hẹn.</li>
 * <li>Có lịch hẹn nhưng trễ hơn mức đó ("trễ 15–30 phút xếp như walk-in"), hoặc walk-in → {@code WALK_IN}, khóa = giờ
 * check-in.</li>
 * </ul>
 *
 * @param priorityClass hạng ưu tiên
 * @param queueSortKey  khóa sắp xếp trong cùng hạng
 */
public record QueuePlacement(PriorityClass priorityClass, Instant queueSortKey) {

    public QueuePlacement {
        Objects.requireNonNull(priorityClass, "priorityClass");
        Objects.requireNonNull(queueSortKey, "queueSortKey");
    }

    /**
     * @param emergency           lễ tân đánh dấu cấp cứu
     * @param scheduledAt         giờ hẹn; {@code null} = walk-in
     * @param now                 thời điểm tiếp nhận
     * @param latePriorityMinutes {@code visit.late_priority_minutes} [CFG]
     */
    public static QueuePlacement of(boolean emergency, Instant scheduledAt, Instant now, int latePriorityMinutes) {
        Objects.requireNonNull(now, "now");
        if (latePriorityMinutes < 0) {
            throw new IllegalArgumentException("Số phút âm: latePriority=" + latePriorityMinutes);
        }
        if (emergency) {
            return new QueuePlacement(PriorityClass.EMERGENCY, now);
        }
        if (scheduledAt != null && !now.isAfter(scheduledAt.plus(Duration.ofMinutes(latePriorityMinutes)))) {
            return new QueuePlacement(PriorityClass.APPOINTMENT, scheduledAt);
        }
        return new QueuePlacement(PriorityClass.WALK_IN, now);
    }
}
