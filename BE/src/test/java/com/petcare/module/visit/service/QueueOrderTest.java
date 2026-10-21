package com.petcare.module.visit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;
import com.petcare.module.visit.entity.PriorityClass;
import com.petcare.module.visit.entity.Visit;

/**
 * {@link QueueOrder}: BR-TN-04 cấp cứu → lịch hẹn → walk-in, rồi {@code queueSortKey}, rồi id (docs/adr/0060 T1). Dữ
 * liệu chọn để sắp theo tên hằng số ({@code APPOINTMENT < EMERGENCY < WALK_IN}) hoặc bỏ tiêu chí phụ đều ra thứ tự khác.
 */
class QueueOrderTest {

    private static final Instant T = Instant.parse("2026-10-20T02:00:00Z");

    @Test
    void emergencyThenAppointmentThenWalkInThenSortKeyThenId() {
        Visit walkInEarly = visit(1L, PriorityClass.WALK_IN, T.minusSeconds(3600));
        Visit appointmentLate = visit(2L, PriorityClass.APPOINTMENT, T.plusSeconds(600));
        Visit emergencyLate = visit(3L, PriorityClass.EMERGENCY, T.plusSeconds(900));
        Visit appointmentEarlyHighId = visit(9L, PriorityClass.APPOINTMENT, T);
        Visit appointmentEarlyLowId = visit(4L, PriorityClass.APPOINTMENT, T);
        Visit emergencyEarly = visit(5L, PriorityClass.EMERGENCY, T.minusSeconds(60));

        List<Visit> rows = new ArrayList<>(List.of(walkInEarly, appointmentLate, emergencyLate, appointmentEarlyHighId,
                appointmentEarlyLowId, emergencyEarly));
        rows.sort(QueueOrder.BR_TN_04);

        assertThat(rows).extracting(Visit::getId).containsExactly(5L, 3L, 4L, 9L, 2L, 1L);
    }

    @Test
    void ranksAreExplicitNotTheEnumOrder() {
        assertThat(QueueOrder.rank(PriorityClass.EMERGENCY)).isLessThan(QueueOrder.rank(PriorityClass.APPOINTMENT));
        assertThat(QueueOrder.rank(PriorityClass.APPOINTMENT)).isLessThan(QueueOrder.rank(PriorityClass.WALK_IN));
    }

    private static Visit visit(long id, PriorityClass priorityClass, Instant sortKey) {
        Visit visit = Visit.checkIn("TN-2610-" + id, 3L, 88L, 12L, null, ServiceGroup.MEDICAL, priorityClass, sortKey,
                70L, sortKey);
        ReflectionTestUtils.setField(visit, "id", id);
        return visit;
    }
}
