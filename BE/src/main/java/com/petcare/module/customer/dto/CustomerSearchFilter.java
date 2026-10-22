package com.petcare.module.customer.dto;

import java.util.Locale;

/**
 * Bộ lọc của {@code GET /api/customers} (customer-v1 #8, UC23; BR-KH-10; docs/adr/0041). Các điều kiện đã gửi nối bằng
 * AND. Giá trị rỗng / toàn khoảng trắng coi như không gửi (Spring truyền {@code ""} cho {@code ?phone=}). Cần ít nhất một
 * điều kiện: kiểm bằng {@link ValidCustomerSearchFilter} ở service. {@code email} đã chuẩn hóa chữ thường (email hồ sơ
 * luôn lưu chữ thường nên so khớp bằng index {@code ix_customers_email}).
 */
@ValidCustomerSearchFilter
public record CustomerSearchFilter(String phone, String name, String email, String petName) {

    public CustomerSearchFilter {
        phone = blankToNull(phone);
        name = blankToNull(name);
        email = blankToNull(email) == null ? null : email.strip().toLowerCase(Locale.ROOT);
        petName = blankToNull(petName);
    }

    public boolean hasAnyCondition() {
        return phone != null || name != null || email != null || petName != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
