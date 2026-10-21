package com.petcare.module.visit.service;

import java.util.Comparator;

import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;

/**
 * Thứ tự hàng đợi BR-TN-04 (visit-v1 A1; docs/adr/0060): ca cấp cứu → lịch hẹn → walk-in, cùng hạng thì theo
 * {@code queueSortKey} (giờ hẹn hoặc giờ check-in, lễ tân sắp lại thì sửa cột này), hòa thì theo {@code id}. Sắp trong bộ
 * nhớ: {@code ORDER BY priority_class} theo chữ cái sai ({@code APPOINTMENT} trước {@code EMERGENCY}); hạng ghi tường
 * minh thay vì {@code ordinal()} để đổi thứ tự hằng số không âm thầm đổi nghiệp vụ.
 */
final class QueueOrder {

    static final Comparator<Visit> BR_TN_04 = Comparator.comparingInt((Visit visit) -> rank(visit.getPriorityClass()))
            .thenComparing(Visit::getQueueSortKey)
            .thenComparing(Visit::getId);

    private QueueOrder() {
    }

    static int rank(PriorityClass priorityClass) {
        return switch (priorityClass) {
            case EMERGENCY -> 0;
            case APPOINTMENT -> 1;
            case WALK_IN -> 2;
        };
    }
}
