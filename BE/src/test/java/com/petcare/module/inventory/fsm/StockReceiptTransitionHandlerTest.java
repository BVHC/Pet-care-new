package com.petcare.module.inventory.fsm;

import static com.petcare.module.inventory.entity.StockReceiptStatus.CANCELLED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.CONFIRMED;
import static com.petcare.module.inventory.entity.StockReceiptStatus.DRAFT;

import java.util.Map;
import java.util.Set;

import com.petcare.module.inventory.entity.StockReceiptStatus;
import com.petcare.platform.fsm.FsmTransitionTestBase;
import com.petcare.platform.fsm.StateMachineBase;

/**
 * Bản sao độc lập của bảng "9. Phiếu nhập kho" (docs/03-state-machines.md): #1 — → DRAFT, #2 DRAFT → CONFIRMED,
 * #3 DRAFT → CANCELLED, #4 CONFIRMED → CANCELLED. CANCELLED là trạng thái cuối.
 */
class StockReceiptTransitionHandlerTest extends FsmTransitionTestBase<StockReceiptStatus> {

    @Override
    protected StateMachineBase<StockReceiptStatus> handler() {
        return new StockReceiptTransitionHandler();
    }

    @Override
    protected Class<StockReceiptStatus> stateType() {
        return StockReceiptStatus.class;
    }

    @Override
    protected Map<StockReceiptStatus, Set<StockReceiptStatus>> expectedTransitions() {
        return Map.of(DRAFT, Set.of(CONFIRMED, CANCELLED), CONFIRMED, Set.of(CANCELLED));
    }

    @Override
    protected Set<StockReceiptStatus> expectedInitialStates() {
        return Set.of(DRAFT);
    }
}
