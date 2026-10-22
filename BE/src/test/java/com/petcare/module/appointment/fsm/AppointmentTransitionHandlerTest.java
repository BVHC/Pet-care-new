package com.petcare.module.appointment.fsm;

import static com.petcare.module.appointment.entity.AppointmentStatus.BOOKED;
import static com.petcare.module.appointment.entity.AppointmentStatus.CANCELLED;
import static com.petcare.module.appointment.entity.AppointmentStatus.CHECKED_IN;
import static com.petcare.module.appointment.entity.AppointmentStatus.COMPLETED;
import static com.petcare.module.appointment.entity.AppointmentStatus.NO_SHOW;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.petcare.module.appointment.entity.AppointmentStatus;
import com.petcare.platform.exception.InvalidStateTransitionException;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "3. Lịch hẹn" (docs/03-state-machines.md L79–88): #1 — → BOOKED; #2 BOOKED → BOOKED;
 * #3 BOOKED → CHECKED_IN; #4, #5 BOOKED → CANCELLED; #6 BOOKED → NO_SHOW; #7 CHECKED_IN → COMPLETED;
 * #8 CHECKED_IN → CANCELLED. COMPLETED, CANCELLED, NO_SHOW là FINAL.
 */
class AppointmentTransitionHandlerTest extends FsmTransitionTestBase<AppointmentStatus> {

    @Override
    protected StateMachineBase<AppointmentStatus> handler() {
        return new AppointmentTransitionHandler();
    }

    @Override
    protected Class<AppointmentStatus> stateType() {
        return AppointmentStatus.class;
    }

    @Override
    protected Map<AppointmentStatus, Set<AppointmentStatus>> expectedTransitions() {
        return Map.of(
                BOOKED, Set.of(BOOKED, CHECKED_IN, CANCELLED, NO_SHOW),   // #2, #3, #4/#5, #6
                CHECKED_IN, Set.of(COMPLETED, CANCELLED));                 // #7, #8
    }

    @Override
    protected Set<AppointmentStatus> expectedInitialStates() {
        return Set.of(BOOKED);                                             // #1
    }

    /** Message 409 mà client nhận dùng tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Test
    void conflictMessageUsesBusinessName() {
        AppointmentTransitionHandler handler = new AppointmentTransitionHandler();

        assertThatThrownBy(() -> handler.validateTransition(CANCELLED, BOOKED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ CANCELLED sang BOOKED");
        assertThatThrownBy(() -> handler.validateInitial(CHECKED_IN))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessage("Không thể chuyển trạng thái lịch hẹn từ — sang CHECKED_IN");
    }
}
