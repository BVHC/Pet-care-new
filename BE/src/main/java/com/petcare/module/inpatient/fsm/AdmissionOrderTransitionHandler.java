package com.petcare.module.inpatient.fsm;

import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.CANCELLED;
import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.DONE;
import static com.petcare.module.inpatient.entity.AdmissionOrderStatus.ORDERED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.inpatient.entity.AdmissionOrderStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "Máy trạng thái #25 — Y lệnh nội trú" của docs/09-tang3-be1-spec.md §C. #1 — → {@code ORDERED} (ra y lệnh);
 * #2 {@code ORDERED → DONE} (thực hiện); #3 {@code ORDERED → CANCELLED} (hủy). {@code DONE}, {@code CANCELLED} là FINAL
 * nên không có khóa; bảng này không có cạnh {@code X → X}.
 */
@Component
public class AdmissionOrderTransitionHandler extends StateMachineBase<AdmissionOrderStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "y lệnh nội trú";

    @Override
    public Map<AdmissionOrderStatus, Set<AdmissionOrderStatus>> allowedTransitions() {
        return Map.of(ORDERED, Set.of(DONE, CANCELLED));   // #2 | #3
    }

    @Override
    public Set<AdmissionOrderStatus> initialStates() {
        return Set.of(ORDERED);                            // #1
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
