package com.petcare.module.workforce.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Mỗi cạnh của 03 #11 ghi đúng cột theo {@code ck_shift_requests_closed_at}, {@code _rejected_note}, {@code _approved_by}
 * và {@code _swap_target} (V36); mọi method chặn yêu cầu đã đóng mà không đổi cột nào.
 */
class ShiftRequestTest {

    private static final Instant AT = Instant.parse("2042-03-14T01:00:00Z");

    private static ShiftRequest pendingLeave() {
        return ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.LEAVE, null, "Việc gia đình");
    }

    @Test
    void submitLeaveStartsPendingWithoutTarget() {
        ShiftRequest request = pendingLeave();

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
        assertThat(request.getRequestType()).isEqualTo(ShiftRequestType.LEAVE);
        assertThat(request.getTargetShiftDefinitionId()).isNull();
        assertThat(request.getReason()).isEqualTo("Việc gia đình");
        assertThat(request.getDecidedBy()).isNull();
        assertThat(request.getDecisionNote()).isNull();
        assertThat(request.getClosedAt()).isNull();
    }

    @Test
    void submitSwapKeepsTargetShift() {
        ShiftRequest request = ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.SWAP, 4L, "Đi học buổi sáng");

        assertThat(request.getRequestType()).isEqualTo(ShiftRequestType.SWAP);
        assertThat(request.getTargetShiftDefinitionId()).isEqualTo(4L);
    }

    @Test
    void submitKeepsTheShiftOfTheAssignmentAtSendingTimeAsASnapshotForBothTypes() {
        // V60 (ADR-0139 mục 17): duyệt SWAP đổi ca của phân ca, nên yêu cầu tự giữ ca lúc gửi.
        assertThat(pendingLeave().getCurrentShiftDefinitionId()).isEqualTo(3L);
        assertThat(ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.SWAP, 4L, "lý do").getCurrentShiftDefinitionId())
                .isEqualTo(3L);
    }

    @Test
    void submitEnforcesSwapTargetRule() {
        assertThatThrownBy(() -> ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.SWAP, null, "lý do"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.SWAP, 3L, "lý do"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.LEAVE, 4L, "lý do"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitRequiresBoundedReasonAndIds() {
        assertThatThrownBy(() -> ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.LEAVE, null, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftRequest.submit(10L, 2L, 3L, ShiftRequestType.LEAVE, null,
                "x".repeat(EntityText.NOTE_MAX_LENGTH + 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShiftRequest.submit(null, 2L, 3L, ShiftRequestType.LEAVE, null, "lý do"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ShiftRequest.submit(10L, null, 3L, ShiftRequestType.LEAVE, null, "lý do"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void withdrawClosesWithoutDecider() {
        ShiftRequest request = pendingLeave();

        request.withdraw(AT);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.WITHDRAWN);
        assertThat(request.getDecidedBy()).isNull();
        assertThat(request.getDecisionNote()).isNull();
        assertThat(request.getClosedAt()).isEqualTo(AT);
    }

    @Test
    void approveRecordsManager() {
        ShiftRequest request = pendingLeave();

        request.approve(9L, AT);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo(9L);
        assertThat(request.getClosedAt()).isEqualTo(AT);
    }

    @Test
    void approveRequiresManager() {
        ShiftRequest request = pendingLeave();

        assertThatThrownBy(() -> request.approve(null, AT)).isInstanceOf(NullPointerException.class);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
        assertThat(request.getClosedAt()).isNull();
    }

    @Test
    void rejectRecordsManagerAndNote() {
        ShiftRequest request = pendingLeave();

        request.reject(9L, "  Thiếu người  ", AT);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(request.getDecidedBy()).isEqualTo(9L);
        assertThat(request.getDecisionNote()).isEqualTo("Thiếu người");
        assertThat(request.getClosedAt()).isEqualTo(AT);
    }

    @Test
    void rejectRequiresNoteAndManager() {
        for (String bad : new String[] {null, "", "  ", "x".repeat(EntityText.NOTE_MAX_LENGTH + 1)}) {
            ShiftRequest request = pendingLeave();
            assertThatThrownBy(() -> request.reject(9L, bad, AT)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> request.rejectBySystem(bad, AT)).isInstanceOf(RuntimeException.class);
            assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.PENDING);
            assertThat(request.getClosedAt()).isNull();
        }
        assertThatThrownBy(() -> pendingLeave().reject(null, "lý do", AT)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectBySystemLeavesDeciderNullButWritesNote() {
        ShiftRequest request = pendingLeave();

        request.rejectBySystem("Phân ca không còn hiệu lực", AT);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.REJECTED);
        assertThat(request.getDecidedBy()).isNull();
        assertThat(request.getDecisionNote()).isEqualTo("Phân ca không còn hiệu lực");
        assertThat(request.getClosedAt()).isEqualTo(AT);
    }

    @Test
    void everyTransitionIsRejectedOnAClosedRequestAndChangesNothing() {
        ShiftRequest request = pendingLeave();
        request.approve(9L, AT);

        assertThatThrownBy(() -> request.withdraw(AT.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.approve(8L, AT.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.reject(8L, "lý do", AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.rejectBySystem("lý do", AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(request.getStatus()).isEqualTo(ShiftRequestStatus.APPROVED);
        assertThat(request.getDecidedBy()).isEqualTo(9L);
        assertThat(request.getClosedAt()).isEqualTo(AT);
    }
}
