package com.petcare.module.customer.entity;

/**
 * Nguồn của hồ sơ khách ({@code customers.created_channel}, BR-KH-01): {@code ONLINE} tạo kèm khi đăng ký (UC01, luôn
 * gắn tài khoản), {@code COUNTER} do lễ tân tạo tại quầy (UC22, bắt buộc có SĐT). Hồ sơ tại quầy đã liên kết tài khoản
 * (BR-TK-19) vẫn là {@code COUNTER}.
 */
public enum CustomerChannel {
    ONLINE,
    COUNTER
}
