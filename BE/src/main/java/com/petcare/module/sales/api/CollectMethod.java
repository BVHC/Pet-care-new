package com.petcare.module.sales.api;

/**
 * Hình thức thu / hoàn tiền (erd {@code payments.method}, {@code refunds.method}; docs/08 §D, BR-TG-12): tại quầy
 * {@code CASH} hoặc {@code TRANSFER}, đơn online qua cổng {@code GATEWAY} (không đi qua ca thu ngân).
 */
public enum CollectMethod { CASH, TRANSFER, GATEWAY }
