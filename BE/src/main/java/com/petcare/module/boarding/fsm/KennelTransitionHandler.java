package com.petcare.module.boarding.fsm;

import static com.petcare.module.boarding.entity.KennelStatus.AVAILABLE;
import static com.petcare.module.boarding.entity.KennelStatus.MAINTENANCE;
import static com.petcare.module.boarding.entity.KennelStatus.OCCUPIED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.boarding.entity.KennelStatus;
import com.petcare.platform.fsm.StateMachineBase;

/** Bảng "7. Chuồng" (docs/03-state-machines.md). */
@Component
public class KennelTransitionHandler extends StateMachineBase<KennelStatus> {

    @Override
    public Map<KennelStatus, Set<KennelStatus>> allowedTransitions() {
        return Map.of(
                AVAILABLE, Set.of(OCCUPIED, MAINTENANCE),   // #2 nhận thú; #4 bảo trì
                OCCUPIED, Set.of(AVAILABLE),                // #3 kết thúc lưu trú
                MAINTENANCE, Set.of(AVAILABLE));            // #5 hết bảo trì
    }

    @Override
    public Set<KennelStatus> initialStates() {
        return Set.of(AVAILABLE);                           // #1
    }

    @Override
    protected String fsmName() {
        return "chuồng";
    }
}
