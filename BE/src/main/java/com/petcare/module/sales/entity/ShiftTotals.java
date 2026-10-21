package com.petcare.module.sales.entity;

/** Tổng tiền hệ thống ghi nhận của một ca theo phương thức, tính lúc chốt (docs/adr/0064). */
public record ShiftTotals(long cash, long transfer) {
}
