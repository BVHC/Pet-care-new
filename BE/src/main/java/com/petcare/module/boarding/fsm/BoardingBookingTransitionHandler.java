package com.petcare.module.boarding.fsm;

import static com.petcare.module.boarding.entity.BoardingBookingStatus.BOOKED;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CANCELLED;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CHECKED_IN;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.CHECKED_OUT;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.NO_SHOW;
import static com.petcare.module.boarding.entity.BoardingBookingStatus.OVERDUE;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.BoardingBookingStatus;
import com.petcare.platform.fsm.StateMachineBase;

/** Bảng "6. Đặt chỗ lưu trú" (docs/03-state-machines.md); #2, #8, #9 giữ nguyên trạng thái nên không có cạnh. */
@Component
public class BoardingBookingTransitionHandler extends StateMachineBase<BoardingBookingStatus> {

    @Override
    public Map<BoardingBookingStatus, Set<BoardingBookingStatus>> allowedTransitions() {
        return Map.of(
                BOOKED, Set.of(CHECKED_IN, CANCELLED, NO_SHOW),   // #3; #4, #5; #6
                CHECKED_IN, Set.of(OVERDUE, CHECKED_OUT),         // #7; #10, #11
                OVERDUE, Set.of(CHECKED_OUT));                    // #10, #11
    }

    @Override
    public Set<BoardingBookingStatus> initialStates() {
        return Set.of(BOOKED);                                    // #1
    }

    @Override
    protected String fsmName() {
        return "đặt chỗ lưu trú";
    }
}
