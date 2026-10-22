package com.petcare.module.visit.fsm;

import static com.petcare.module.visit.entity.VisitStatus.CANCELLED;
import static com.petcare.module.visit.entity.VisitStatus.COMPLETED;
import static com.petcare.module.visit.entity.VisitStatus.IN_PROGRESS;
import static com.petcare.module.visit.entity.VisitStatus.WAITING;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.visit.entity.VisitStatus;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bảng "4. Visit" của docs/03-state-machines.md (L100–107). #2 (gán / gán lại lượt chưa gọi) và #4 (gán lại lượt đã
 * gọi) là cạnh {@code X → X}. {@code COMPLETED}, {@code CANCELLED} là FINAL nên không có khóa. Sắp lại hàng đợi và xác
 * định ca cấp cứu không phải dòng của bảng nên không dùng cạnh nào ở đây (docs/adr/0058).
 */
@Component
public class VisitTransitionHandler extends StateMachineBase<VisitStatus> {

    /** Tên nghiệp vụ trong message 409, dùng chung cho kiểm trạng thái tường minh ở service (docs/adr/0062). */
    public static final String FSM_NAME = "lượt tiếp nhận";

    @Override
    public Map<VisitStatus, Set<VisitStatus>> allowedTransitions() {
        return Map.of(
                WAITING, Set.of(WAITING,                // #2 gán / gán lại nhân viên
                        IN_PROGRESS,                    // #3 gọi lượt
                        CANCELLED),                     // #6 hủy lượt khách bỏ về
                IN_PROGRESS, Set.of(IN_PROGRESS,        // #4 gán lại lượt đã gọi
                        COMPLETED));                    // #5 hoàn tất lượt
    }

    @Override
    public Set<VisitStatus> initialStates() {
        return Set.of(WAITING);                         // #1 tiếp nhận
    }

    /** Tên hiện trong message 409 mà client nhận — tên nghiệp vụ, không lộ tên lớp (khuôn docs/adr/0033). */
    @Override
    protected String fsmName() {
        return FSM_NAME;
    }
}
