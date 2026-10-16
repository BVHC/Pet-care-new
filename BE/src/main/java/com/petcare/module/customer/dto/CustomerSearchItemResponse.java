package com.petcare.module.customer.dto;

import java.util.List;

import com.petcare.module.customer.entity.CustomerChannel;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Một dòng kết quả tra cứu khách (customer-v1 {@code CustomerSearchItem}, UC23, BR-KH-10). Một SĐT có thể ra nhiều hồ
 * sơ: nhân viên chọn đúng người theo họ tên, email đã che và danh sách thú cưng. Email luôn che, không bao giờ trả
 * nguyên văn ở danh sách.
 */
public record CustomerSearchItemResponse(
        @Schema(description = "Mã hồ sơ khách", example = "315")
        Long customerId,

        @Schema(description = "Họ tên", example = "Nguyễn Thị Lan")
        String fullName,

        @Schema(description = "SĐT; null nếu hồ sơ online chưa khai", example = "0901234567")
        String phone,

        @Schema(description = "Email đã che; null nếu hồ sơ không có email", example = "la***@gmail.com")
        String maskedEmail,

        @Schema(description = "Nguồn hồ sơ", example = "COUNTER")
        CustomerChannel createdChannel,

        @Schema(description = "Hồ sơ đã gắn tài khoản", example = "false")
        boolean hasAccount,

        @Schema(description = "Thú cưng của khách (kể cả đã mất), theo mã tăng dần")
        List<PetBriefResponse> pets) {
}
