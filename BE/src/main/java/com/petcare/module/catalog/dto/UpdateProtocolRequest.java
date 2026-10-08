package com.petcare.module.catalog.dto;

/**
 * Body của {@code PATCH /api/vaccination-protocols/{id}}; trường {@code null} là không đổi. Chỉ áp dụng cho mũi
 * tiêm thực hiện sau thời điểm sửa (BR-SP-03).
 */
public record UpdateProtocolRequest(
        Integer intervalDays,
        Integer minAgeWeeks,
        Boolean requiredForBoarding,
        Boolean isActive) {
}
