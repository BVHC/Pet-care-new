package com.petcare.module.shop.service;

import org.springframework.stereotype.Component;

import com.petcare.module.shop.api.OnlineOrderApi;

/**
 * Placeholder của {@link OnlineOrderApi} (T55c, docs/06 §1, docs/adr/0117): ném {@link UnsupportedOperationException}
 * thay vì trả giá trị giả. <b>Xóa lớp này</b> khi T65 cài bản thật (hai bean cùng interface làm hỏng việc tiêm).
 */
@Component
public class OnlineOrderApiPlaceholder implements OnlineOrderApi {

    @Override
    public void markPaid(Long onlineOrderId, String transactionCode) {
        throw placeholder("markPaid");
    }

    @Override
    public void markPaymentCancelled(Long onlineOrderId) {
        throw placeholder("markPaymentCancelled");
    }

    private static UnsupportedOperationException placeholder(String method) {
        return new UnsupportedOperationException("OnlineOrderApi." + method + " chưa cài (placeholder T55c, docs/06 §6b); "
                + "bản thật ở T65");
    }
}
