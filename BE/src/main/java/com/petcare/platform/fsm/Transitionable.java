package com.petcare.platform.fsm;

import java.util.Map;
import java.util.Set;

/**
 * Whitelist chuyển trạng thái của một đối tượng, chép đúng bảng đánh số trong docs/03-state-machines.md.
 * Enum State + Transition Map, không dùng Spring StateMachine (docs/convention/backend/05-fsm-pattern.md).
 */
public interface Transitionable<S extends Enum<S>> {

    /**
     * Cạnh {@code from → to} được phép. Dòng {@code X → X} của bảng (đổi giờ, gán lại, gia hạn...)
     * là cạnh {@code X → X}; không tự suy diễn thêm cạnh nào ngoài bảng.
     */
    Map<S, Set<S>> allowedTransitions();

    /** Trạng thái được phép khi tạo mới: các dòng {@code — → X} của bảng. */
    Set<S> initialStates();
}
