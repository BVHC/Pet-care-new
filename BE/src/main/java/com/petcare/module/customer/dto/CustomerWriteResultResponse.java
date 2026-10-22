package com.petcare.module.customer.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kết quả tạo / sửa hồ sơ khách tại quầy (customer-v1 {@code CustomerWriteResult}). {@code duplicatePhoneProfiles} là các
 * hồ sơ <b>khác</b> cùng SĐT (BR-KH-10): cảnh báo, không chặn; rỗng khi không trùng hoặc khi SĐT không đổi. Tối đa
 * {@code CustomerWriteResultResponse.MAX_DUPLICATES} hồ sơ, theo mã tăng dần.
 */
public record CustomerWriteResultResponse(
        @Schema(description = "Hồ sơ sau khi ghi")
        CustomerProfileResponse customer,

        @Schema(description = "Hồ sơ khác cùng SĐT (cảnh báo nghi trùng, BR-KH-10)")
        List<CustomerSearchItemResponse> duplicatePhoneProfiles) {

    /** Giới hạn số hồ sơ nghi trùng trả về: một SĐT dùng chung (ví dụ số của phòng khám) có thể khớp hàng trăm hồ sơ. */
    public static final int MAX_DUPLICATES = 20;
}
