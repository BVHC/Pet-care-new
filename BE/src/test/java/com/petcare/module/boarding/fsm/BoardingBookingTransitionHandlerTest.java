package com.petcare.module.boarding.fsm;

import static com.petcare.module.boarding.entity.BoardingBookingStatus.BOOKED;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CANCELLED;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CHECKED_IN;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CHECKED_OUT;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.NO_SHOW;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.OVERDUE;

import java.util.Map;
import java.util.Set;

import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "6. Đặt chỗ lưu trú" (docs/03-state-machines.md): #1 — → BOOKED; #3 BOOKED → CHECKED_IN;
 * #4, #5 BOOKED → CANCELLED; #6 BOOKED → NO_SHOW; #7 CHECKED_IN → OVERDUE; #10, #11 CHECKED_IN / OVERDUE → CHECKED_OUT.
 * #2, #8, #9 "giữ nguyên" không phải cạnh.
 */
class BoardingBookingTransitionHandlerTest extends FsmTransitionTestBase<BoardingBookingStatus> {

    @Override
    protected StateMachineBase<BoardingBookingStatus> handler() {
        return new BoardingBookingTransitionHandler();
    }

    @Override
    protected Class<BoardingBookingStatus> stateType() {
        return BoardingBookingStatus.class;
    }

    @Override
    protected Map<BoardingBookingStatus, Set<BoardingBookingStatus>> expectedTransitions() {
        return Map.of(
                BOOKED, Set.of(CHECKED_IN, CANCELLED, NO_SHOW),
                CHECKED_IN, Set.of(OVERDUE, CHECKED_OUT),
                OVERDUE, Set.of(CHECKED_OUT));
    }

    @Override
    protected Set<BoardingBookingStatus> expectedInitialStates() {
        return Set.of(BOOKED);
    }
}
