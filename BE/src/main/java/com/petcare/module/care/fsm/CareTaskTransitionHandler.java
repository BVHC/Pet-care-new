package com.petcare.module.care.fsm;

import static com.petcare.module.care.entity.CareTaskStatus.CANCELLED;
import static com.petcare.module.care.entity.CareTaskStatus.DONE;
import static com.petcare.module.care.entity.CareTaskStatus.OPEN;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.care.entity.CareTaskStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "10. Care Task" của docs/03-state-machines.md (L226–233). #1 — → {@code OPEN} (ST18); #2 {@code OPEN → DONE}
 * (lễ tân thực hiện); #3 {@code OPEN → CANCELLED} (lễ tân hủy), #4 (thú đã mất, ST19), #5 (mũi đã tiêm lại), #6 (nhắc
 * tái khám bị hủy) cùng một cạnh. {@code DONE}, {@code CANCELLED} là FINAL nên không có khóa; không có cạnh
 * {@code X → X}. Hệ quả hệ thống #4–#6 chạy bằng một câu UPDATE {@code WHERE status = 'OPEN'} ở repository, điều kiện
 * đúng bằng cạnh {@code OPEN → CANCELLED} này (docs/adr/0079).
 */
@Component
public class CareTaskTransitionHandler extends StateMachineBase<CareTaskStatus> {

    /** Tên nghiệp vụ trong message 409 (khuôn docs/adr/0033, không lộ tên lớp). */
    public static final String FSM_NAME = "việc chăm sóc khách";

    @Override
    public Map<CareTaskStatus, Set<CareTaskStatus>> allowedTransitions() {
        return Map.of(OPEN, Set.of(DONE, CANCELLED));  // #2 | #3, #4, #5, #6
    }

    @Override
    public Set<CareTaskStatus> initialStates() {
        return Set.of(OPEN);                            // #1 ST18 sinh task
    }

    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
