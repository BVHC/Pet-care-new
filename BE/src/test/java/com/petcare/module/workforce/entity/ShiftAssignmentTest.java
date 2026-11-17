package com.petcare.module.workforce.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * Mỗi cạnh của 03 #12 ghi đúng cột theo {@code ck_shift_assignments_status_changed} và {@code ck_shift_assignments_reason}
 * (V36), và mọi method chặn dòng đã ở trạng thái cuối mà không đổi cột nào.
 */
class ShiftAssignmentTest {

    private static final Instant AT = Instant.parse("2042-03-14T01:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2042, 3, 20);

    private static ShiftAssignment scheduled() {
        return ShiftAssignment.schedule(1L, 2L, 3L, DAY, ShiftAssignmentSource.SELF);
    }

    @Test
    void scheduleStartsScheduledWithoutChangeColumns() {
        ShiftAssignment assignment = scheduled();

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(assignment.getSource()).isEqualTo(ShiftAssignmentSource.SELF);
        assertThat(assignment.getReason()).isNull();
        assertThat(assignment.getStatusChangedBy()).isNull();
        assertThat(assignment.getStatusChangedAt()).isNull();
    }

    @Test
    void scheduleRequiresEveryArgument() {
        assertThatThrownBy(() -> ShiftAssignment.schedule(null, 2L, 3L, DAY, ShiftAssignmentSource.SELF))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftAssignment.schedule(1L, null, 3L, DAY, ShiftAssignmentSource.SELF))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftAssignment.schedule(1L, 2L, null, DAY, ShiftAssignmentSource.SELF))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftAssignment.schedule(1L, 2L, 3L, null, ShiftAssignmentSource.SELF))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftAssignment.schedule(1L, 2L, 3L, DAY, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void cancelByManagerWritesReasonActorAndTime() {
        ShiftAssignment assignment = scheduled();

        assignment.cancel("  Đổi lịch  ", 9L, AT);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
        assertThat(assignment.getReason()).isEqualTo("Đổi lịch");
        assertThat(assignment.getStatusChangedBy()).isEqualTo(9L);
        assertThat(assignment.getStatusChangedAt()).isEqualTo(AT);
    }

    @Test
    void cancelBySystemLeavesActorNull() {
        ShiftAssignment assignment = scheduled();

        assignment.cancel("Nhân viên rời chi nhánh", null, AT);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.CANCELLED);
        assertThat(assignment.getStatusChangedBy()).isNull();
        assertThat(assignment.getStatusChangedAt()).isEqualTo(AT);
    }

    @Test
    void markOnLeaveRecordsApproverAndCopiedReason() {
        ShiftAssignment assignment = scheduled();

        assignment.markOnLeave("Việc gia đình", 9L, AT);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.ON_LEAVE);
        assertThat(assignment.getReason()).isEqualTo("Việc gia đình");
        assertThat(assignment.getStatusChangedBy()).isEqualTo(9L);
        assertThat(assignment.getStatusChangedAt()).isEqualTo(AT);
    }

    @Test
    void markAbsentRecordsReasonAndActor() {
        ShiftAssignment assignment = scheduled();

        assignment.markAbsent("Không đến, không báo", 9L, AT);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.ABSENT);
        assertThat(assignment.getReason()).isEqualTo("Không đến, không báo");
        assertThat(assignment.getStatusChangedBy()).isEqualTo(9L);
        assertThat(assignment.getStatusChangedAt()).isEqualTo(AT);
    }

    @Test
    void swapChangesOnlyTheShiftAndLeavesNoStatusTrace() {
        ShiftAssignment assignment = scheduled();

        assignment.swapTo(4L);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(assignment.getShiftDefinitionId()).isEqualTo(4L);
        assertThat(assignment.getReason()).isNull();
        assertThat(assignment.getStatusChangedBy()).isNull();
        assertThat(assignment.getStatusChangedAt()).isNull();
    }

    @Test
    void reasonIsRequiredAndBoundedForEveryTransitionThatWritesIt() {
        String tooLong = "x".repeat(EntityText.NOTE_MAX_LENGTH + 1);
        for (String bad : new String[] {null, "", "   ", tooLong}) {
            ShiftAssignment assignment = scheduled();
            assertThatThrownBy(() -> assignment.cancel(bad, 9L, AT)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> assignment.markAbsent(bad, 9L, AT)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> assignment.markOnLeave(bad, 9L, AT)).isInstanceOf(RuntimeException.class);
            assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
            assertThat(assignment.getStatusChangedAt()).isNull();
        }
        ShiftAssignment atLimit = scheduled();
        atLimit.cancel("x".repeat(EntityText.NOTE_MAX_LENGTH), 9L, AT);
        assertThat(atLimit.getReason()).hasSize(EntityText.NOTE_MAX_LENGTH);
    }

    @Test
    void approverAndTimeAreRequiredWhenAHumanDecides() {
        ShiftAssignment assignment = scheduled();

        assertThatThrownBy(() -> assignment.markOnLeave("lý do", null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> assignment.markAbsent("lý do", null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> assignment.cancel("lý do", 9L, null)).isInstanceOf(NullPointerException.class);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.SCHEDULED);
        assertThat(assignment.getStatusChangedAt()).isNull();
    }

    @Test
    void everyTransitionIsRejectedOnAFinalAssignmentAndChangesNothing() {
        ShiftAssignment assignment = scheduled();
        assignment.markOnLeave("Việc gia đình", 9L, AT);

        assertThatThrownBy(() -> assignment.cancel("lý do", 9L, AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignment.markAbsent("lý do", 9L, AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignment.markOnLeave("lý do", 8L, AT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignment.swapTo(5L)).isInstanceOf(IllegalStateException.class);

        assertThat(assignment.getStatus()).isEqualTo(ShiftAssignmentStatus.ON_LEAVE);
        assertThat(assignment.getReason()).isEqualTo("Việc gia đình");
        assertThat(assignment.getStatusChangedBy()).isEqualTo(9L);
        assertThat(assignment.getShiftDefinitionId()).isEqualTo(3L);
    }
}
