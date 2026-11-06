package com.petcare.module.sales.service;

import org.springframework.stereotype.Component;

import com.petcare.module.sales.api.CashierShiftApi;
import com.petcare.module.sales.api.CollectMethod;

/**
 * Placeholder của {@link CashierShiftApi} (T55c, docs/06 §1, docs/adr/0117): ném {@link UnsupportedOperationException}
 * thay vì trả giá trị giả. <b>Xóa lớp này</b> khi T73 cài bản thật (hai bean cùng interface làm hỏng việc tiêm).
 */
@Component
public class CashierShiftApiPlaceholder implements CashierShiftApi {

    @Override
    public void recordRefund(Long shiftId, CollectMethod method, long amount) {
        throw new UnsupportedOperationException("CashierShiftApi.recordRefund chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở T73");
    }
}
