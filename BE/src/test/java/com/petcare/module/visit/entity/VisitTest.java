package com.petcare.module.visit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void completeMovesToCompletedStampsCompletedAtClearsTheFlagAndTouchesNothingElse() {
        Visit visit = assigned();
        visit.call(AT);
        ReflectionTestUtils.setField(visit, "needsReassign", true);
        Instant doneAt = AT.plusSeconds(600);

        visit.complete(doneAt);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.COMPLETED);
        assertThat(visit.getCompletedAt()).isEqualTo(doneAt);
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(visit.getAssigneeId()).isEqualTo(45L);
        assertThat(visit.getCalledAt()).isEqualTo(AT);
        assertThat(visit.getCancelledAt()).isNull();
        assertThat(visit.getCancelReason()).isNull();
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.APPOINTMENT);
        assertThat(visit.getQueueSortKey()).isEqualTo(CHECKED_IN);
        assertThat(visit.getAppointmentId()).isEqualTo(501L);
    }

    /** Visit#7 (docs/adr/0149 mục 12): thẳng IN_PROGRESS, VET vừa tiếp nhận vừa phụ trách, không lịch hẹn. */
    @Test
    void startTreatmentOpensAnInProgressVisitOwnedByTheVet() {
        Visit visit = Visit.startTreatment("TN-2610-00002", 3L, 88L, 12L, 45L, AT);

        assertThat(visit.getStatus()).isEqualTo(VisitStatus.IN_PROGRESS);
        assertThat(visit.getCode()).isEqualTo("TN-2610-00002");
        assertThat(visit.getBranchId()).isEqualTo(3L);
        assertThat(visit.getCustomerId()).isEqualTo(88L);
        assertThat(visit.getPetId()).isEqualTo(12L);
        assertThat(visit.getAssigneeId()).isEqualTo(45L);
        assertThat(visit.getCheckedInBy()).isEqualTo(45L);
        assertThat(visit.getCheckedInAt()).isEqualTo(AT);
        assertThat(visit.getCalledAt()).isEqualTo(AT);
        assertThat(visit.getQueueSortKey()).isEqualTo(AT);
        assertThat(visit.getServiceGroup()).isEqualTo(ServiceGroup.MEDICAL);
        assertThat(visit.getPriorityClass()).isEqualTo(PriorityClass.WALK_IN);
        assertThat(visit.getAppointmentId()).isNull();
        assertThat(visit.isNeedsReassign()).isFalse();
        assertThat(visit.getCompletedAt()).isNull();
        assertThat(visit.getCancelledAt()).isNull();
    }

    /** {@code ck_visits_assignee}: lượt IN_PROGRESS luôn có người phụ trách — thiếu bất kỳ tham số nào đều bị chặn. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void startTreatmentRejectsAMissingArgument(int missing) {
        Object[] args = {"TN-2610-00002", 3L, 88L, 12L, 45L, AT};
        args[missing] = null;

        assertThatThrownBy(() -> Visit.startTreatment((String) args[0], (Long) args[1], (Long) args[2],
                (Long) args[3], (Long) args[4], (Instant) args[5]))
                .isInstanceOf(IllegalArgumentException.class);
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
