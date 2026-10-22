package com.petcare.module.inventory.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;

/** Khoảng ngày đóng {@code [from, to]} của các danh sách phiếu; {@code to} trước {@code from} → 400 {@code VALIDATION_FAILED}. */
public record DateRange(LocalDate from, LocalDate to) {

    @AssertTrue(message = "Ngày kết thúc không được trước ngày bắt đầu")
    public boolean isOrdered() {
        return from == null || to == null || !from.isAfter(to);
    }
}
