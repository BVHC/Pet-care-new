package com.petcare.module.inpatient.fsm;

import static com.petcare.module.inpatient.entity.AdmissionStatus.ADMITTED;
import static com.petcare.module.inpatient.entity.AdmissionStatus.DISCHARGED;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.inpatient.entity.AdmissionStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "Máy trạng thái #24 — Nhập viện" của docs/09-tang3-be1-spec.md §C. #1 — → {@code ADMITTED} (nhập viện, đường
 * a hoặc b); #2 {@code ADMITTED → DISCHARGED} (xuất viện). {@code DISCHARGED} là FINAL nên không có khóa; bảng này không
 * có cạnh {@code X → X}.
 */
@Component
public class AdmissionTransitionHandler extends StateMachineBase<AdmissionStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "ca nội trú";

    @Override
    public Map<AdmissionStatus, Set<AdmissionStatus>> allowedTransitions() {
        return Map.of(ADMITTED, Set.of(DISCHARGED));   // #2
    }

    @Override
    public Set<AdmissionStatus> initialStates() {
        return Set.of(ADMITTED);                       // #1
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
