package com.petcare.module.care.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.petcare.module.care.api.CareTaskApi.CareTaskType;
import com.petcare.module.care.api.CareTaskApi.NewCareTask;

/**
 * {@link CareTask#open} (docs/adr/0079): đúng một cột nguồn theo loại (CHECK {@code ck_care_tasks_target}), {@code OPEN},
 * năm cột xử lý NULL; mọi trường bắt buộc.
 */
class CareTaskTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);

    private static NewCareTask of(CareTaskType type) {
        return new NewCareTask(type, 500L, 11L, 22L, 33L, DUE);
    }

    @ParameterizedTest
    @EnumSource(CareTaskType.class)
    void opensWithTheSourceInExactlyOneColumnAndNoHandlingData(CareTaskType type) {
        CareTask task = CareTask.open(of(type));

        assertThat(task.getTaskType()).isEqualTo(type);
        assertThat(task.getStatus()).isEqualTo(CareTaskStatus.OPEN);
        assertThat(task.getBranchId()).isEqualTo(11L);
        assertThat(task.getCustomerId()).isEqualTo(22L);
        assertThat(task.getPetId()).isEqualTo(33L);
        assertThat(task.getDueDate()).isEqualTo(DUE);
        assertThat(task.getId()).isNull();
        assertThat(task.getResult()).isNull();
        assertThat(task.getNote()).isNull();
        assertThat(task.getCancelReason()).isNull();
        assertThat(task.getHandledBy()).isNull();
        assertThat(task.getHandledAt()).isNull();
        boolean vaccine = type == CareTaskType.VACCINE_DUE || type == CareTaskType.VACCINE_OVERDUE;
        assertThat(task.getVaccinationId()).isEqualTo(vaccine ? 500L : null);
        assertThat(task.getMedicalRecordId()).isEqualTo(type == CareTaskType.FOLLOW_UP_DUE ? 500L : null);
        assertThat(task.getBoardingBookingId()).isEqualTo(type == CareTaskType.PICKUP_OVERDUE ? 500L : null);
    }

    private static final Instant AT = Instant.parse("2026-10-27T03:05:00.123456Z");

    @ParameterizedTest
    @EnumSource(CareTaskResult.class)
    void completeWritesExactlyTheDoneColumnsAndLeavesTheCancelReasonNull(CareTaskResult result) {
        CareTask task = CareTask.open(of(CareTaskType.VACCINE_DUE));

        task.complete(result, "Khách hẹn tuần sau", 21L, AT);

        assertThat(task.getStatus()).isEqualTo(CareTaskStatus.DONE);
        assertThat(task.getResult()).isEqualTo(result);
        assertThat(task.getNote()).isEqualTo("Khách hẹn tuần sau");
        assertThat(task.getHandledBy()).isEqualTo(21L);
        assertThat(task.getHandledAt()).isEqualTo(AT);
        assertThat(task.getCancelReason()).isNull();
        assertThat(task.getCustomerId()).isEqualTo(22L);
        assertThat(task.getVaccinationId()).isEqualTo(500L);
    }

    @Test
    void cancelWritesExactlyTheCancelledColumnsAndLeavesResultAndNoteNull() {
        CareTask task = CareTask.open(of(CareTaskType.PICKUP_OVERDUE));

        task.cancel("Khách đã chuyển đi", 21L, AT);

        assertThat(task.getStatus()).isEqualTo(CareTaskStatus.CANCELLED);
        assertThat(task.getCancelReason()).isEqualTo("Khách đã chuyển đi");
        assertThat(task.getHandledBy()).isEqualTo(21L);
        assertThat(task.getHandledAt()).isEqualTo(AT);
        assertThat(task.getResult()).isNull();
        assertThat(task.getNote()).isNull();
        assertThat(task.getBoardingBookingId()).isEqualTo(500L);
    }

    @Test
    void completeAndCancelRejectAnyNullArgumentAndLeaveTheTaskOpen() {
        CareTask task = CareTask.open(of(CareTaskType.FOLLOW_UP_DUE));

        assertThatThrownBy(() -> task.complete(null, "n", 21L, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.complete(CareTaskResult.REACHED, null, 21L, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.complete(CareTaskResult.REACHED, "n", null, AT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.complete(CareTaskResult.REACHED, "n", 21L, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.cancel(null, 21L, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.cancel("r", null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> task.cancel("r", 21L, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTaskOrAnyNullFieldIsAProgrammingError() {
        assertThatThrownBy(() -> CareTask.open(null)).isInstanceOf(NullPointerException.class);
        for (NewCareTask bad : new NewCareTask[] {
                new NewCareTask(null, 1L, 11L, 22L, 33L, DUE),
                new NewCareTask(CareTaskType.VACCINE_DUE, null, 11L, 22L, 33L, DUE),
                new NewCareTask(CareTaskType.VACCINE_DUE, 1L, null, 22L, 33L, DUE),
                new NewCareTask(CareTaskType.VACCINE_DUE, 1L, 11L, null, 33L, DUE),
                new NewCareTask(CareTaskType.VACCINE_DUE, 1L, 11L, 22L, null, DUE),
                new NewCareTask(CareTaskType.VACCINE_DUE, 1L, 11L, 22L, 33L, null)}) {
            assertThatThrownBy(() -> CareTask.open(bad)).isInstanceOf(NullPointerException.class);
        }
    }
}
