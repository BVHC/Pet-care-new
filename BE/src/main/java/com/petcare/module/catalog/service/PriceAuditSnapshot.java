package com.petcare.module.catalog.service;

/** Snapshot audit khi đổi giá sản phẩm hoặc dịch vụ (VND). Record, không phải entity (ADR-0001). */
public record PriceAuditSnapshot(long price) {
}
