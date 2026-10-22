package com.petcare.module.inventory.fsm;

import static com.petcare.module.inventory.entity.StockReceiptStatus.CANCELLED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.CONFIRMED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.DRAFT;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.petcare.module.inventory.entity.StockReceiptStatus;
import com.petcare.platform.fsm.StateMachineBase;

/** Bảng "9. Phiếu nhập kho" của docs/03-state-machines.md: #1 — → DRAFT, #2 DRAFT → CONFIRMED, #3 DRAFT → CANCELLED, #4 CONFIRMED → CANCELLED. */
@Component
public class StockReceiptTransitionHandler extends StateMachineBase<StockReceiptStatus> {

    @Override
    public Map<StockReceiptStatus, Set<StockReceiptStatus>> allowedTransitions() {
        return Map.of(
                DRAFT, Set.of(CONFIRMED, CANCELLED),       // #2 xác nhận, #3 hủy phiếu nháp
                CONFIRMED, Set.of(CANCELLED));             // #4 hủy phiếu đã xác nhận
    }

    @Override
    public Set<StockReceiptStatus> initialStates() {
        return Set.of(DRAFT);                              // #1 tạo phiếu
    }

    @Override
    protected String fsmName() {
        return "phiếu nhập kho";
    }
}
