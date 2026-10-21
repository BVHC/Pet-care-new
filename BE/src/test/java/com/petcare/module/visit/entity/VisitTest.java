package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.petcare.module.catalog.api.ServiceGroup;

/**
 * Hai command của T26 phần 4 trên {@link Visit} (docs/adr/0062): mỗi method chỉ đổi đúng cột của mình — người được gán,
 * hạng, khóa sắp xếp, cờ cần gán lại giữ nguyên.
 */
class VisitTest {

    private static final Instant CHECKED_IN = Instant.parse("2026-10-21T01:00:00Z");
    private static final Instant AT = Instant.parse("2026-10-21T02:15:30.123456Z");

    @Test
    void callMovesToInProgressAndStampsCalledAtOnly() {
        Visit visit = assigned();

        visit.call(AT);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getCalledAt()).isEqualTo(AT);
        assertThat(visit.getCancelledAt()).isNull();
        assertThat(visit.getCancelReason()).isNull();
        assertUntouched(visit);
    }

    @Test
    void cancelMovesToCancelledWithReasonAndKeepsTheAssignee() {
        Visit visit = assigned();

        visit.cancel("Khách bỏ về", AT);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.CANCELLED);
        assertThat(visit.getCancelledAt()).isEqualTo(AT);
        assertThat(visit.getCancelReason()).isEqualTo("Khách bỏ về");
        assertThat(visit.getCalledAt()).isNull();
        assertUntouched(visit);
    }

    @Test
    void cancelWithoutReasonStoresNull() {
        Visit visit = assigned();

        visit.cancel(null, AT);

        assertThat(visit.getCancelReason()).isNull();
    }

    @Test
    void reassignToReplacesTheAssigneeClearsTheFlagAndKeepsTheCall() {
        Visit visit = assigned();
        visit.call(AT);
        ReflectionTestUtils.setField(visit, "needsReassign", true);

        visit.reassignTo(46L);

        assertThat(visit.getAssigneeId()).isEqualTo(46L);
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getCalledAt()).isEqualTo(AT);
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(visit.getQueueSortKey()).isEqualTo(CHECKED_IN);
    }

    private static Visit assigned() {
        Visit visit = Visit.checkIn("TN-2610-00001", 3L, 88L, 12L, 501L, ServiceGroup.MEDICAL,
                PriorityClass.APPOINTMENT, CHECKED_IN, 70L, CHECKED_IN);
        visit.assignTo(45L);
        ReflectionTestUtils.setField(visit, "id", 9001L);
        return visit;
    }

    private static void assertUntouched(Visit visit) {
        assertThat(visit.getAssigneeId()).isEqualTo(45L);
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(visit.getQueueSortKey()).isEqualTo(CHECKED_IN);
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(visit.getAppointmentId()).isEqualTo(501L);
        assertThat(visit.getCompletedAt()).isNull();
    }
}
