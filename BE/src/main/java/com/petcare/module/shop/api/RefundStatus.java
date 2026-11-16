package com.petcare.module.shop.api;

/** Trạng thái hoàn tiền (03 #17, returns-v1 / shop-v1 {@code RefundStatus}); đơn mới đặt luôn null. */
public enum RefundStatus {
    REQUESTED, SUCCEEDED, FAILED
}
