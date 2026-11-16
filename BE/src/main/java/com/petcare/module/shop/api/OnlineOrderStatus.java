package com.petcare.module.shop.api;

/** FSM #14 (08 §B), shop-v1 {@code OnlineOrderStatus}: trạng thái đơn hàng online. */
public enum OnlineOrderStatus {
    PENDING_PAYMENT, PAID, PROCESSING, SHIPPED, DELIVERY_FAILED, DELIVERED, COMPLETED, CANCELLED
}
